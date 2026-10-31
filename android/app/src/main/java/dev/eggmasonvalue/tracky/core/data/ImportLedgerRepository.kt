package dev.eggmasonvalue.tracky.core.data

import dev.eggmasonvalue.tracky.core.sync.SessionProjection
import java.time.Instant

/**
 * The only durable Android record store. It intentionally contains no raw frames,
 * source measurements, profile values, or backup copies of the Linux database.
 */
interface ImportLedger {
    suspend fun find(clientRecordId: String): ImportLedgerEntry?
    suspend fun markPending(projection: SessionProjection, now: Instant)
    suspend fun markImported(
        clientRecordId: String,
        healthConnectRecordIds: List<String>,
        now: Instant,
    )
    suspend fun markAttemptFailed(
        clientRecordId: String,
        now: Instant,
        errorCode: String,
        errorMessage: String,
        retryAfter: java.time.Duration,
    )
    suspend fun markQuarantined(
        projection: SessionProjection,
        now: Instant,
        errorCode: String,
        errorMessage: String,
    )
    suspend fun cursor(): String?
    suspend fun derivationVersion(): Long
    /** Persist the new version and clear the cursor in one atomic state-row write. */
    suspend fun resetForDerivationVersion(version: Long)
    suspend fun advanceCursor(cursor: String?)
}

class RoomImportLedger internal constructor(
    private val dao: TrackyDao,
) : ImportLedger {
    constructor(database: TrackyDatabase) : this(database.trackyDao())

    override suspend fun find(clientRecordId: String): ImportLedgerEntry? =
        dao.findImport(clientRecordId)?.toDomain()

    override suspend fun markPending(projection: SessionProjection, now: Instant) {
        val previous = dao.findImport(projection.clientRecordId)?.toDomain()
        val attempts = previous?.attemptCount ?: 0
        dao.upsertImport(
            ImportLedgerEntity(
                clientRecordId = projection.clientRecordId,
                sourceSessionId = projection.sourceSessionId,
                clientRecordVersion = projection.clientRecordVersion,
                contractVersion = projection.contractVersion,
                selectionPolicyVersion = projection.selectionPolicyVersion,
                derivationVersion = projection.derivationVersion,
                disposition = ImportDisposition.PENDING,
                healthConnectRecordIds = previous?.healthConnectRecordIds.orEmpty(),
                attemptCount = attempts,
                lastAttemptEpochMs = previous?.lastAttemptAt?.toEpochMilli(),
                nextRetryEpochMs = null,
                lastErrorCode = null,
                lastErrorMessage = null,
                updatedAtEpochMs = now.toEpochMilli(),
            ),
        )
    }

    override suspend fun markImported(
        clientRecordId: String,
        healthConnectRecordIds: List<String>,
        now: Instant,
    ) {
        require(healthConnectRecordIds.isNotEmpty() && healthConnectRecordIds.all { it.isNotBlank() }) {
            "Health Connect record IDs must not be empty or blank"
        }
        val previous = requireNotNull(dao.findImport(clientRecordId)) {
            "cannot import an unknown ledger entry"
        }
        dao.upsertImport(
            previous.copy(
                disposition = ImportDisposition.IMPORTED,
                healthConnectRecordIds = healthConnectRecordIds,
                attemptCount = previous.attemptCount + 1,
                lastAttemptEpochMs = now.toEpochMilli(),
                nextRetryEpochMs = null,
                lastErrorCode = null,
                lastErrorMessage = null,
                updatedAtEpochMs = now.toEpochMilli(),
            ),
        )
    }

    override suspend fun markAttemptFailed(
        clientRecordId: String,
        now: Instant,
        errorCode: String,
        errorMessage: String,
        retryAfter: java.time.Duration,
    ) {
        require(errorCode.isNotBlank()) { "errorCode must not be blank" }
        require(errorMessage.isNotBlank()) { "errorMessage must not be blank" }
        require(!retryAfter.isNegative) { "retryAfter must not be negative" }
        val previous = requireNotNull(dao.findImport(clientRecordId)) {
            "cannot fail an unknown ledger entry"
        }
        dao.upsertImport(
            previous.copy(
                disposition = ImportDisposition.PENDING,
                attemptCount = previous.attemptCount + 1,
                lastAttemptEpochMs = now.toEpochMilli(),
                nextRetryEpochMs = now.plus(retryAfter).toEpochMilli(),
                lastErrorCode = errorCode.take(MAX_ERROR_CODE_LENGTH),
                lastErrorMessage = errorMessage.take(MAX_ERROR_MESSAGE_LENGTH),
                updatedAtEpochMs = now.toEpochMilli(),
            ),
        )
    }

    override suspend fun markQuarantined(
        projection: SessionProjection,
        now: Instant,
        errorCode: String,
        errorMessage: String,
    ) {
        require(errorCode.isNotBlank()) { "errorCode must not be blank" }
        require(errorMessage.isNotBlank()) { "errorMessage must not be blank" }
        dao.upsertImport(
            ImportLedgerEntity(
                clientRecordId = projection.clientRecordId,
                sourceSessionId = projection.sourceSessionId,
                clientRecordVersion = projection.clientRecordVersion,
                contractVersion = projection.contractVersion,
                selectionPolicyVersion = projection.selectionPolicyVersion,
                derivationVersion = projection.derivationVersion,
                disposition = ImportDisposition.QUARANTINED,
                healthConnectRecordIds = emptyList(),
                attemptCount = 0,
                lastAttemptEpochMs = now.toEpochMilli(),
                nextRetryEpochMs = null,
                lastErrorCode = errorCode.take(MAX_ERROR_CODE_LENGTH),
                lastErrorMessage = errorMessage.take(MAX_ERROR_MESSAGE_LENGTH),
                updatedAtEpochMs = now.toEpochMilli(),
            ),
        )
    }

    override suspend fun cursor(): String? =
        dao.findSyncState(SyncStateEntity.STATE_ID)?.cursor

    override suspend fun derivationVersion(): Long =
        dao.findSyncState(SyncStateEntity.STATE_ID)?.derivationVersion ?: 0L

    override suspend fun resetForDerivationVersion(version: Long) {
        require(version > 0L) { "derivation version must be positive" }
        dao.upsertSyncState(SyncStateEntity(cursor = null, derivationVersion = version))
    }

    override suspend fun advanceCursor(cursor: String?) {
        require(cursor == null || cursor.length <= MAX_CURSOR_LENGTH) {
            "sync cursor is too long"
        }
        dao.updateCursor(SyncStateEntity.STATE_ID, cursor)
    }

    private companion object {
        const val MAX_ERROR_CODE_LENGTH = 64
        const val MAX_ERROR_MESSAGE_LENGTH = 256
        const val MAX_CURSOR_LENGTH = 1024
    }
}
