package dev.eggmasonvalue.tracky.core.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import dev.eggmasonvalue.tracky.core.sync.HealthConnectProjector
import dev.eggmasonvalue.tracky.core.sync.WeightProjection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
) : HealthConnectProjector {
    private val appContext = context.applicationContext

    private val client: HealthConnectClient?
        get() = if (isAvailable()) HealthConnectClient.getOrCreate(appContext) else null

    override fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE

    override suspend fun hasRequiredPermissions(): Boolean {
        val healthConnect = client ?: return false
        return healthConnect.permissionController
            .getGrantedPermissions()
            .containsAll(REQUIRED_PERMISSIONS)
    }

    suspend fun hasHeightReadPermission(): Boolean {
        val healthConnect = client ?: return false
        return healthConnect.permissionController
            .getGrantedPermissions()
            .contains(HEIGHT_READ_PERMISSION)
    }

    suspend fun readLatestApplicableHeight(): HeightObservation? {
        val healthConnect = client ?: throw HeightReadException("Health Connect is unavailable")
        if (!hasHeightReadPermission()) {
            throw HeightReadException("Health Connect height read permission is not granted")
        }
        val now = clock.instant()
        val records = buildList {
            var pageToken: String? = null
            do {
                val response = healthConnect.readRecords(
                    ReadRecordsRequest(
                        recordType = HeightRecord::class,
                        timeRangeFilter = TimeRangeFilter.before(now),
                        ascendingOrder = false,
                        pageSize = HEIGHT_PAGE_SIZE,
                        pageToken = pageToken,
                    ),
                )
                addAll(response.records)
                pageToken = response.pageToken?.takeIf { it.isNotBlank() }
            } while (pageToken != null)
        }
        return records
            .asSequence()
            .filter { !it.time.isAfter(now) }
            .maxByOrNull(HeightRecord::time)
            ?.let { record ->
                HeightObservation(
                    recordedAt = record.time,
                    heightMeters = record.height.inMeters(),
                )
            }
    }

    override suspend fun insertWeight(projection: WeightProjection): String {
        val healthConnect = client
            ?: throw IllegalStateException("Health Connect is not available")
        val response = healthConnect.insertRecords(
            listOf(
                WeightRecord(
                    time = projection.observedAt,
                    zoneOffset = ZoneOffset.UTC,
                    weight = Mass.kilograms(projection.weightKg),
                    metadata = Metadata(
                        clientRecordId = projection.clientRecordId,
                        clientRecordVersion = projection.clientRecordVersion,
                        device = Device(
                            manufacturer = "Tracky",
                            model = "Linux acquisition projection",
                            type = Device.TYPE_SCALE,
                        ),
                        recordingMethod = Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                    ),
                ),
            ),
        )
        return response.recordIdsList.singleOrNull()
            ?: throw IllegalStateException("Health Connect returned no weight record ID")
    }

    companion object {
        val HEIGHT_READ_PERMISSION = HealthPermission.getReadPermission(HeightRecord::class)
        val REQUIRED_PERMISSIONS = setOf(
            HEIGHT_READ_PERMISSION,
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
            HealthPermission.getWritePermission(WeightRecord::class),
        )

        private const val HEIGHT_PAGE_SIZE = 1000
    }
}

class HeightReadException(message: String) : Exception(message)

data class HeightObservation(
    val recordedAt: Instant,
    val heightMeters: Double,
) {
    val heightCentimeters: Double
        get() = heightMeters * 100.0
}

private fun Length.inMeters(): Double = this.inMeters
