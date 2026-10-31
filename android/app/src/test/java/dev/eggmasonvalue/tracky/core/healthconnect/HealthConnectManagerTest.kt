package dev.eggmasonvalue.tracky.core.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import dev.eggmasonvalue.tracky.core.sync.testComposition
import dev.eggmasonvalue.tracky.core.sync.testInstant
import dev.eggmasonvalue.tracky.core.sync.testProjection
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectManagerTest {
    private val projection = testProjection().copy(composition = testComposition)
    private val allPermissions = HealthConnectManager.REQUESTED_PERMISSIONS
    private val weightPermission = HealthConnectManager.REQUIRED_PERMISSIONS

    @Test
    fun `composition produces exactly six records with Linux values stable IDs version and scale metadata`() {
        val records = HealthConnectManager.buildRecords(projection, allPermissions)
        assertEquals(6, records.size)
        assertEquals(78.9, (records[0] as WeightRecord).weight.inKilograms, 0.0)
        assertEquals(24.4, (records[1] as BodyFatRecord).percentage.value, 0.0)
        assertEquals(57.8, (records[2] as LeanBodyMassRecord).mass.inKilograms, 0.0)
        assertEquals(42.1, (records[3] as BodyWaterMassRecord).mass.inKilograms, 0.0)
        assertEquals(3.41, (records[4] as BoneMassRecord).mass.inKilograms, 0.0)
        assertEquals(1618.0, (records[5] as BasalMetabolicRateRecord).basalMetabolicRate.inKilocaloriesPerDay, 0.0)
        val suffixes = listOf("weight", "body-fat", "lean-body-mass", "body-water-mass", "bone-mass", "bmr")
        records.zip(suffixes).forEach { (record, suffix) ->
            assertEquals(projection.clientRecordIdFor(suffix), record.metadata.clientRecordId)
            assertEquals(2L, record.metadata.clientRecordVersion)
            assertEquals("Tracky", record.metadata.device?.manufacturer)
            assertEquals("TS-CS10B", record.metadata.device?.model)
            assertEquals(androidx.health.connect.client.records.metadata.Device.TYPE_SCALE, record.metadata.device?.type)
        }
    }

    @Test
    fun `only weight permission is required and no reads are requested`() {
        assertEquals(setOf(HealthPermission.getWritePermission(WeightRecord::class)), weightPermission)
        assertEquals(6, allPermissions.size)
        assertTrue(allPermissions.all { it.startsWith("android.permission.health.WRITE_") })
        val records = HealthConnectManager.buildRecords(projection, weightPermission)
        assertEquals(1, records.size)
        assertTrue(records.single() is WeightRecord)
    }

    @Test
    fun `every derived type is independently permission gated`() {
        val types = listOf(BodyFatRecord::class, LeanBodyMassRecord::class, BodyWaterMassRecord::class, BoneMassRecord::class, BasalMetabolicRateRecord::class)
        for (type in types) {
            val permission = HealthPermission.getWritePermission(type)
            val without = HealthConnectManager.buildRecords(projection, allPermissions - permission)
            assertEquals(5, without.size)
            assertFalse(without.any { it::class == type })
            val only = HealthConnectManager.buildRecords(projection, weightPermission + permission)
            assertEquals(listOf(WeightRecord::class, type), only.map { it::class })
        }
        assertThrows(IllegalStateException::class.java) { HealthConnectManager.buildRecords(projection, emptySet()) }
    }

    @Test
    fun `null composition deletes all five derived IDs before inserting weight and is replay safe`() = runTest {
        val deletions = mutableListOf<Pair<KClass<out Record>, String>>()
        val stored = HealthConnectManager.buildRecords(projection, allPermissions).associateBy { it.metadata.clientRecordId }.toMutableMap()
        repeat(2) {
            val ids = HealthConnectManager.projectSession(
                projection.copy(composition = null, clientRecordVersion = 3, derivationVersion = 3),
                allPermissions,
                insert = { records ->
                    assertEquals(1, records.size)
                    assertEquals(1, stored.size)
                    records.forEach { stored[it.metadata.clientRecordId] = it }
                    listOf("hc-weight")
                },
                delete = { type, id -> deletions.add(type to id); stored.remove(id) },
            )
            assertEquals(listOf("hc-weight"), ids)
        }
        assertEquals(10, deletions.size)
        assertEquals(setOf("body-fat", "lean-body-mass", "body-water-mass", "bone-mass", "bmr").map(projection::clientRecordIdFor).toSet(), deletions.take(5).map { it.second }.toSet())
        assertEquals(setOf(projection.clientRecordId), stored.keys)
    }

    @Test
    fun `weight-only sync does not attempt unpermitted derived operations`() = runTest {
        for (composition in listOf(testComposition, null)) {
            val ids = HealthConnectManager.projectSession(
                projection.copy(composition = composition), weightPermission,
                insert = { records -> assertTrue(records.single() is WeightRecord); listOf("hc-weight") },
                delete = { _, _ -> error("Unpermitted deletion") },
            )
            assertEquals(listOf("hc-weight"), ids)
        }
    }

    @Test
    fun `composition insertion returns every ID and incomplete responses fail`() = runTest {
        val expected = (1..6).map { "hc-$it" }
        assertEquals(expected, HealthConnectManager.projectSession(
            projection, allPermissions,
            insert = { expected }, delete = { _, _ -> error("Unexpected deletion") },
        ))
        try {
            HealthConnectManager.projectSession(projection, allPermissions, insert = { listOf("hc-one") }, delete = { _, _ -> })
            error("Expected incomplete response rejection")
        } catch (error: IllegalStateException) {
            assertEquals("Health Connect did not return every record ID", error.message)
        }
    }

    @Test
    fun `all records use local zone offset at the observation instant including daylight saving`() {
        val zone = ZoneId.of("America/New_York")
        for ((time, offset) in listOf(
            Instant.parse("2026-07-01T10:00:00Z") to ZoneOffset.ofHours(-4),
            Instant.parse("2026-01-01T10:00:00Z") to ZoneOffset.ofHours(-5),
        )) {
            val records = HealthConnectManager.buildRecords(projection.copy(observedAt = time), allPermissions, zone)
            records.forEach { record ->
                val actual = when (record) {
                    is WeightRecord -> record.time to record.zoneOffset
                    is BodyFatRecord -> record.time to record.zoneOffset
                    is LeanBodyMassRecord -> record.time to record.zoneOffset
                    is BodyWaterMassRecord -> record.time to record.zoneOffset
                    is BoneMassRecord -> record.time to record.zoneOffset
                    is BasalMetabolicRateRecord -> record.time to record.zoneOffset
                    else -> error("Unexpected record type")
                }
                assertEquals(time to offset, actual)
            }
        }
        val india = HealthConnectManager.buildRecords(projection, allPermissions, ZoneId.of("Asia/Kolkata"))
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), (india.first() as WeightRecord).zoneOffset)
        assertEquals(testInstant, india.first().let { it as WeightRecord }.time)
    }
}
