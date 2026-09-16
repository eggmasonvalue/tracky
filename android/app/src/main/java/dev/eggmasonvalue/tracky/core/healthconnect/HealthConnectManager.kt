package dev.eggmasonvalue.tracky.core.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(private val context: Context) {

    val healthConnectClient: HealthConnectClient? by lazy {
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null
    }

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(REQUIRED_PERMISSIONS)
    }

    suspend fun writeBodyComposition(
        time: Instant,
        zoneOffset: ZoneOffset,
        weightKg: Double,
        bodyFatPercentage: Double,
        leanBodyMassKg: Double,
    ) {
        val client = healthConnectClient ?: throw IllegalStateException("Health Connect is not available")

        val weightRecord = WeightRecord(
            weight = Mass.kilograms(weightKg),
            time = time,
            zoneOffset = zoneOffset,
        )
        val bodyFatRecord = BodyFatRecord(
            percentage = Percentage(bodyFatPercentage),
            time = time,
            zoneOffset = zoneOffset,
        )
        val leanMassRecord = LeanBodyMassRecord(
            mass = Mass.kilograms(leanBodyMassKg),
            time = time,
            zoneOffset = zoneOffset,
        )

        client.insertRecords(listOf(weightRecord))
        client.insertRecords(listOf(bodyFatRecord))
        client.insertRecords(listOf(leanMassRecord))
    }

    companion object {
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getWritePermission(WeightRecord::class),
            HealthPermission.getWritePermission(BodyFatRecord::class),
            HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        )
    }
}
