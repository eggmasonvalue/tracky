package dev.eggmasonvalue.tracky.core.sync

import dev.eggmasonvalue.tracky.core.data.ImportDisposition
import dev.eggmasonvalue.tracky.core.data.RoomImportLedger
import dev.eggmasonvalue.tracky.core.data.SyncStateEntity
import dev.eggmasonvalue.tracky.core.data.TestTrackyDao
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRunnerTest {
    private val dao = TestTrackyDao()
    private val ledger = RoomImportLedger(dao)
    private val health = FakeHealth()
    private val fetchedCursors = mutableListOf<String?>()

    @Test
    fun `cursor advances only after import or quarantine and stores every returned ID`() = runTest {
        val runner = runner { page(testObservation("ok"), testObservation("bad").copy(weight = null)) }
        assertEquals(SyncRunResult.Success(1, 0, 1), runner.run())
        assertEquals("next-cursor", ledger.cursor())
        val entry = ledger.find(ObservationValidator.clientRecordId("ok"))!!
        assertEquals(ImportDisposition.IMPORTED, entry.disposition)
        assertEquals(health.returnedIds, entry.healthConnectRecordIds)
        assertEquals(2L, entry.derivationVersion)
        assertEquals(ImportDisposition.QUARANTINED, ledger.find(ObservationValidator.clientRecordId("bad"))!!.disposition)
    }

    @Test
    fun `cursor is not advanced when a Health Connect operation fails`() = runTest {
        health.failWrites = true
        val runner = runner { page(testObservation("retry")) }
        assertEquals(SyncRunResult.Retry("health_connect_write_failed"), runner.run())
        assertNull(ledger.cursor())
        val entry = ledger.find(ObservationValidator.clientRecordId("retry"))!!
        assertEquals(ImportDisposition.PENDING, entry.disposition)
        assertEquals("health_connect_write_failed", entry.lastErrorCode)
    }

    @Test
    fun `retry skips prior terminal items but retries pending item`() = runTest {
        health.failOnId = ObservationValidator.clientRecordId("second")
        val runner = runner { page(testObservation("first"), testObservation("second")) }
        assertEquals(SyncRunResult.Retry("health_connect_write_failed"), runner.run())
        assertNull(ledger.cursor())
        health.failOnId = null
        assertEquals(SyncRunResult.Success(1, 1, 0), runner.run())
        assertEquals(3, health.written.size)
        assertEquals("next-cursor", ledger.cursor())
    }

    @Test
    fun `replay of same version is a no-op`() = runTest {
        val runner = runner { page(testObservation("replay")) }
        assertEquals(SyncRunResult.Success(1, 0, 0), runner.run())
        assertEquals(SyncRunResult.Success(0, 1, 0), runner.run())
        assertEquals(1, health.written.size)
        assertEquals(2L, ledger.derivationVersion())
    }

    @Test
    fun `higher page version clears persisted cursor before refetch and reimports old entries`() = runTest {
        val observation = testObservation("old").copy(composition = testComposition)
        runner { page(observation) }.run()
        health.written.clear()
        fetchedCursors.clear()
        val runner = runner { cursor ->
            if (cursor != null) {
                // Discovery page must be discarded, not imported before replaying from the start.
                page(testObservation("discard", 3), version = 3)
            } else {
                assertEquals(3L, ledger.derivationVersion())
                assertNull(ledger.cursor())
                page(observation.copy(versions = testVersions(3), composition = null), version = 3)
            }
        }
        assertEquals(SyncRunResult.Success(1, 0, 0), runner.run())
        assertEquals(listOf("next-cursor", null), fetchedCursors)
        assertEquals("old", health.written.single().sourceSessionId)
        assertNull(health.written.single().composition)
        assertEquals(3L, health.written.single().clientRecordVersion)
        assertEquals(3L, ledger.find(ObservationValidator.clientRecordId("old"))!!.clientRecordVersion)
        assertEquals(3L, ledger.derivationVersion())
    }

    @Test
    fun `empty discovery page triggers full replay on version bump`() = runTest {
        dao.state = SyncStateEntity(cursor = "end", derivationVersion = 2)
        val runner = runner { cursor ->
            if (cursor == "end") page(version = 3, nextCursor = null)
            else page(testObservation("historical", 3), version = 3)
        }
        assertEquals(SyncRunResult.Success(1, 0, 0), runner.run())
        assertEquals(listOf("end", null), fetchedCursors)
    }

    @Test
    fun `version reset survives a failure fetching the replay page`() = runTest {
        dao.state = SyncStateEntity(cursor = "end", derivationVersion = 2)
        val runner = runner { cursor ->
            if (cursor != null) page(version = 3, nextCursor = null)
            else throw SyncTransportException(true, "offline", "test")
        }
        assertEquals(SyncRunResult.Retry("offline"), runner.run())
        assertNull(ledger.cursor())
        assertEquals(3L, ledger.derivationVersion())
    }

    @Test
    fun `lower version cannot advance cursor or downgrade records`() = runTest {
        dao.state = SyncStateEntity(cursor = "end", derivationVersion = 3)
        assertEquals(SyncRunResult.Failure("derivation_version_regressed"), runner { page() }.run())
        assertEquals("end", ledger.cursor())
        assertTrue(health.written.isEmpty())
    }

    @Test
    fun `Linux composition is forwarded without derivation and null is forwarded for deletion`() = runTest {
        val runner = runner {
            page(testObservation("derived").copy(composition = testComposition), testObservation("weight-only"))
        }
        assertEquals(SyncRunResult.Success(2, 0, 0), runner.run())
        assertEquals(testComposition, health.written[0].composition)
        assertNull(health.written[1].composition)
    }

    @Test
    fun `weight permission is the only runner permission prerequisite`() = runTest {
        health.authorized = false
        assertEquals(SyncRunResult.HealthConnectPermissionsMissing, runner { page() }.run())
        assertTrue(fetchedCursors.isEmpty())
        health.authorized = true
        assertEquals(SyncRunResult.Success(1, 0, 0), runner { page(testObservation()) }.run())
    }

    @Test
    fun `cancellation leaves item pending and cursor unchanged`() = runTest {
        health.cancelWrites = true
        try {
            runner { page(testObservation()) }.run()
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertNull(ledger.cursor())
            assertEquals(ImportDisposition.PENDING, ledger.find(ObservationValidator.clientRecordId("session-1"))!!.disposition)
        }
    }

    private fun runner(fetch: suspend (String?) -> ObservationPage) = SyncRunner(
        configSource = object : SyncConfigSource {
            override fun load() = SyncEndpointValidator.validate("https://appliance.example", "test-token")
        },
        ledger = ledger,
        transport = object : ObservationTransport {
            override suspend fun fetch(config: SyncEndpointConfig, cursor: String?): ObservationPage {
                fetchedCursors.add(cursor)
                return fetch(cursor)
            }
        },
        healthConnect = health,
        clock = Clock.fixed(testInstant, ZoneOffset.UTC),
    )

    private fun page(vararg observations: RemoteObservation, version: Long = 2, nextCursor: String? = "next-cursor") =
        ObservationPage(observations.toList(), nextCursor, testVersions(version), PageSourceState("ready", null))
}

private class FakeHealth : HealthConnectProjector {
    var failWrites = false
    var failOnId: String? = null
    var cancelWrites = false
    var authorized = true
    val returnedIds = listOf("hc-weight", "hc-fat", "hc-lean", "hc-water", "hc-bone", "hc-bmr")
    val written = mutableListOf<SessionProjection>()
    override fun isAvailable() = true
    override suspend fun hasRequiredPermissions() = authorized
    override suspend fun writeSession(projection: SessionProjection): List<String> {
        written.add(projection)
        if (cancelWrites) throw CancellationException("test")
        if (failWrites || projection.clientRecordId == failOnId) error("test failure")
        return returnedIds
    }
}
