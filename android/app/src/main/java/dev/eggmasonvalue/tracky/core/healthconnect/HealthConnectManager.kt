package dev.eggmasonvalue.tracky.core.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import dev.eggmasonvalue.tracky.core.sync.HealthConnectProjector
import dev.eggmasonvalue.tracky.core.sync.SessionProjection
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthConnectManager(context: Context) : HealthConnectProjector {
    private val appContext = context.applicationContext
    private val client: HealthConnectClient?
        get() = if (isAvailable()) HealthConnectClient.getOrCreate(appContext) else null

    override fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE

    override suspend fun hasRequiredPermissions(): Boolean =
        grantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    suspend fun grantedPermissions(): Set<String> =
        client?.permissionController?.getGrantedPermissions().orEmpty()

    override suspend fun writeSession(projection: SessionProjection): List<String> {
        val healthConnect = client ?: error("Health Connect is not available")
        return projectSession(
            projection = projection,
            permissions = healthConnect.permissionController.getGrantedPermissions(),
            insert = { records -> healthConnect.insertRecords(records).recordIdsList },
            delete = { type, id ->
                healthConnect.deleteRecords(type, recordIdsList = emptyList(), clientRecordIdsList = listOf(id))
            },
        )
    }

    companion object {
        val REQUIRED_PERMISSIONS = setOf(HealthPermission.getWritePermission(WeightRecord::class))
        private val DERIVED_RECORD_TYPES = linkedMapOf<KClass<out Record>, String>(
            BodyFatRecord::class to "body-fat",
            LeanBodyMassRecord::class to "lean-body-mass",
            BodyWaterMassRecord::class to "body-water-mass",
            BoneMassRecord::class to "bone-mass",
            BasalMetabolicRateRecord::class to "bmr",
        )
        val REQUESTED_PERMISSIONS = REQUIRED_PERMISSIONS +
            DERIVED_RECORD_TYPES.keys.map(HealthPermission::getWritePermission)

        /** One terminal write includes removal of any no-longer-valid derived projection. */
        internal suspend fun projectSession(
            projection: SessionProjection,
            permissions: Set<String>,
            insert: suspend (List<Record>) -> List<String>,
            delete: suspend (KClass<out Record>, String) -> Unit,
        ): List<String> {
            check(permissions.containsAll(REQUIRED_PERMISSIONS)) { "Weight write permission is required" }
            if (projection.composition == null) {
                for ((type, suffix) in DERIVED_RECORD_TYPES) {
                    // Health Connect requires the type's write permission even for deletion.
                    if (HealthPermission.getWritePermission(type) in permissions) {
                        delete(type, projection.clientRecordIdFor(suffix))
                    }
                }
            }
            val records = buildRecords(projection, permissions)
            val ids = insert(records)
            check(ids.size == records.size && ids.all { it.isNotBlank() }) {
                "Health Connect did not return every record ID"
            }
            return ids
        }

        fun buildRecords(
            projection: SessionProjection,
            permissions: Set<String>,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): List<Record> {
            check(permissions.containsAll(REQUIRED_PERMISSIONS)) { "Weight write permission is required" }
            val time = projection.observedAt
            val offset = zoneId.rules.getOffset(time)
            fun metadata(suffix: String) = Metadata(
                clientRecordId = projection.clientRecordIdFor(suffix),
                clientRecordVersion = projection.clientRecordVersion,
                device = Device(manufacturer = "Tracky", model = "TS-CS10B", type = Device.TYPE_SCALE),
                recordingMethod = Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
            )
            fun allowed(type: KClass<out Record>) = HealthPermission.getWritePermission(type) in permissions
            return buildList {
                add(WeightRecord(time, offset, Mass.kilograms(projection.weightKg), metadata("weight")))
                projection.composition?.let { composition ->
                    if (allowed(BodyFatRecord::class)) {
                        add(BodyFatRecord(time, offset, Percentage(composition.bodyFatPct), metadata("body-fat")))
                    }
                    if (allowed(LeanBodyMassRecord::class)) {
                        add(LeanBodyMassRecord(time, offset, Mass.kilograms(composition.leanBodyMassKg), metadata("lean-body-mass")))
                    }
                    if (allowed(BodyWaterMassRecord::class)) {
                        add(BodyWaterMassRecord(time, offset, Mass.kilograms(composition.bodyWaterKg), metadata("body-water-mass")))
                    }
                    if (allowed(BoneMassRecord::class)) {
                        add(BoneMassRecord(time, offset, Mass.kilograms(composition.boneMassKg), metadata("bone-mass")))
                    }
                    if (allowed(BasalMetabolicRateRecord::class)) {
                        add(BasalMetabolicRateRecord(
                            time, offset, Power.kilocaloriesPerDay(composition.basalMetabolicRateKcalPerDay), metadata("bmr"),
                        ))
                    }
                }
            }
        }
    }
}
