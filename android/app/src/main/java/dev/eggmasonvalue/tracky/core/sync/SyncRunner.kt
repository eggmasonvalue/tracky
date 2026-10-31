package dev.eggmasonvalue.tracky.core.sync

import dev.eggmasonvalue.tracky.core.data.ImportDisposition
import dev.eggmasonvalue.tracky.core.data.ImportLedger
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException

interface HealthConnectProjector {
    fun isAvailable(): Boolean
    suspend fun hasRequiredPermissions(): Boolean
    suspend fun writeSession(projection: SessionProjection): List<String>
}

/** The cursor is committed only after every item is imported or quarantined. */
class SyncRunner(
    private val configSource: SyncConfigSource,
    private val ledger: ImportLedger,
    private val transport: ObservationTransport,
    private val healthConnect: HealthConnectProjector,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun run(): SyncRunResult {
        val config = configSource.load() ?: return SyncRunResult.NeedsConfiguration
        if (!healthConnect.isAvailable()) return SyncRunResult.HealthConnectUnavailable
        if (!healthConnect.hasRequiredPermissions()) {
            return SyncRunResult.HealthConnectPermissionsMissing
        }

        var cursor = ledger.cursor()
        var version = ledger.derivationVersion()
        val page: ObservationPage
        while (true) {
            val fetched = try {
                transport.fetch(config, cursor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SyncTransportException) {
                return if (error.retryable) SyncRunResult.Retry(error.code) else SyncRunResult.Failure(error.code)
            } catch (_: Throwable) {
                return SyncRunResult.Retry("transport_failed")
            }
            val fetchedVersion = fetched.versions.derivationVersion
            if (fetchedVersion < version) return SyncRunResult.Failure("derivation_version_regressed")
            if (fetchedVersion > version) {
                ledger.resetForDerivationVersion(fetchedVersion)
                version = fetchedVersion
                if (cursor != null) {
                    cursor = null
                    continue
                }
            }
            page = fetched
            break
        }
        if (page.sourceState.kind == "retryable") {
            return SyncRunResult.Retry(page.sourceState.code ?: "source_state_retry")
        }

        var imported = 0
        var alreadyTerminal = 0
        var quarantined = 0
        for (observation in page.observations) {
            val validation = ObservationValidator.validate(observation, page.versions.derivationVersion)
            val projection = when (validation) {
                is ObservationValidation.Valid -> validation.projection
                is ObservationValidation.Invalid -> validation.projection
            }
            val existing = ledger.find(projection.clientRecordId)
            if (existing != null &&
                existing.clientRecordVersion >= projection.clientRecordVersion &&
                existing.disposition != ImportDisposition.PENDING
            ) {
                alreadyTerminal++
                continue
            }
            when (validation) {
                is ObservationValidation.Invalid -> {
                    ledger.markQuarantined(
                        projection, clock.instant(), validation.errorCode, validation.errorMessage,
                    )
                    quarantined++
                }
                is ObservationValidation.Valid -> {
                    ledger.markPending(projection, clock.instant())
                    val healthConnectIds = try {
                        healthConnect.writeSession(projection)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        ledger.markAttemptFailed(
                            clientRecordId = projection.clientRecordId,
                            now = clock.instant(),
                            errorCode = "health_connect_write_failed",
                            errorMessage = "Health Connect did not accept the session projection",
                            retryAfter = RETRY_AFTER,
                        )
                        return SyncRunResult.Retry("health_connect_write_failed")
                    }
                    ledger.markImported(projection.clientRecordId, healthConnectIds, clock.instant())
                    imported++
                }
            }
        }
        // A crash before this line repeats safely via client IDs, versions and terminal dispositions.
        ledger.advanceCursor(page.nextCursor)
        return SyncRunResult.Success(imported, alreadyTerminal, quarantined)
    }

    private companion object {
        val RETRY_AFTER: Duration = Duration.ofSeconds(30)
    }
}
