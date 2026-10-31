package dev.eggmasonvalue.tracky.core.data

import dev.eggmasonvalue.tracky.core.sync.testInstant
import dev.eggmasonvalue.tracky.core.sync.testProjection
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportLedgerModelTest {
    @Test
    fun `record ID converter round trips every ID including escaping`() {
        val converter = TrackyTypeConverters()
        val ids = listOf("hc-weight", "hc-fat", "hc-lean", "hc-water", "hc-bone", "hc-bmr", "hc-\"quoted\\id")
        assertEquals(ids, converter.storageToRecordIds(converter.recordIdsToStorage(ids)))
        assertEquals(emptyList<String>(), converter.storageToRecordIds("[]"))
    }

    @Test
    fun `ledger retains all IDs and retry metadata without source evidence or profile`() = runTest {
        val dao = TestTrackyDao()
        val ledger = RoomImportLedger(dao)
        val projection = testProjection()
        val ids = (1..6).map { "hc-$it" }
        ledger.markPending(projection, testInstant)
        ledger.markImported(projection.clientRecordId, ids, testInstant)
        val entry = ledger.find(projection.clientRecordId)!!
        assertEquals(ImportDisposition.IMPORTED, entry.disposition)
        assertEquals(ids, entry.healthConnectRecordIds)
        assertEquals(entry, entry.toEntity().toDomain())
        assertEquals(2L, entry.derivationVersion)
        val newer = projection.copy(clientRecordVersion = 3, derivationVersion = 3)
        ledger.markPending(newer, testInstant)
        assertEquals(ids, ledger.find(projection.clientRecordId)!!.healthConnectRecordIds)
        ledger.markAttemptFailed(projection.clientRecordId, testInstant, "failed", "Generic failure", Duration.ofSeconds(30))
        val pending = ledger.find(projection.clientRecordId)!!
        assertEquals(ImportDisposition.PENDING, pending.disposition)
        assertEquals(testInstant.plusSeconds(30), pending.nextRetryAt)
        assertEquals(ids, pending.healthConnectRecordIds)
        assertNull(pending.toEntity().javaClass.declaredFields.firstOrNull { it.name in setOf("weightKg", "composition", "heightCm", "opaqueBlockA", "opaqueBlockB") })
    }

    @Test
    fun `cursor advancement preserves derivation version and reset is atomic`() = runTest {
        val dao = TestTrackyDao()
        val ledger = RoomImportLedger(dao)
        assertEquals(0L, ledger.derivationVersion())
        ledger.resetForDerivationVersion(2)
        assertNull(ledger.cursor())
        ledger.advanceCursor("opaque")
        assertEquals(SyncStateEntity(cursor = "opaque", derivationVersion = 2), dao.state)
        ledger.resetForDerivationVersion(3)
        assertEquals(SyncStateEntity(cursor = null, derivationVersion = 3), dao.state)
    }
}
