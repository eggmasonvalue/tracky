package dev.eggmasonvalue.tracky.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SessionHistoryRepositoryTest {
    private val sessionId = UUID.randomUUID().toString()
    private val characteristicId = UUID.randomUUID().toString()

    @Test
    fun createAlwaysWritesOpenSessionWithUnsupportedSelection() = runTest {
        val store = FakeSessionPersistence()
        val repository = RoomSessionHistoryRepository(store)

        val created = repository.createSession(
            id = sessionId,
            startedAt = Instant.ofEpochMilli(100),
            requestedDisplayUnit = DisplayUnit.KG,
            safeAdvertisedName = "Tracky Scale",
        )

        assertEquals(SessionStatus.OPEN, created.status)
        assertNull(created.endedAt)
        assertNull(created.selectedFrameSequence)
        assertNull(store.sessions.getValue(sessionId).selectedFrameSequence)
    }

    @Test
    fun framesNeverOverwriteAndCompletedSessionRetainsOrderedEvidence() = runTest {
        val store = FakeSessionPersistence()
        val repository = RoomSessionHistoryRepository(store)
        repository.createSession(
            id = sessionId,
            startedAt = Instant.EPOCH,
            requestedDisplayUnit = DisplayUnit.LB,
            safeAdvertisedName = "Scale",
        )
        repository.appendFrame(sessionId, frame(sequence = 2, raw = byteArrayOf(2)))
        repository.appendFrame(sessionId, frame(sequence = 1, raw = byteArrayOf(1)))

        assertSuspendThrows<IllegalStateException> {
            repository.appendFrame(sessionId, frame(sequence = 1, raw = byteArrayOf(9)))
        }

        repository.completeSession(sessionId, Instant.ofEpochMilli(1_000))
        assertSuspendThrows<IllegalStateException> {
            repository.appendFrame(sessionId, frame(sequence = 3, raw = byteArrayOf(3)))
        }

        val history = repository.observeSessionWithFrames(sessionId).first()
        requireNotNull(history)
        assertEquals(SessionStatus.COMPLETE, history.session.status)
        assertEquals(Instant.ofEpochMilli(1_000), history.session.endedAt)
        assertEquals(listOf(1L, 2L), history.frames.map { it.sequence })
        assertEquals(2, history.frames.size)

        val summary = repository.observeRecentSessions(10).first().single()
        assertEquals(2L, summary.frameCount)
    }

    @Test
    fun abortRetainsEvidenceAndCannotBeRepeated() = runTest {
        val store = FakeSessionPersistence()
        val repository = RoomSessionHistoryRepository(store)
        repository.createSession(
            id = sessionId,
            startedAt = Instant.ofEpochMilli(10),
            requestedDisplayUnit = DisplayUnit.KG,
            safeAdvertisedName = "Scale",
        )
        repository.appendFrame(sessionId, frame(0, byteArrayOf(4, 5)))

        repository.abortSession(sessionId, Instant.ofEpochMilli(20))

        assertSuspendThrows<IllegalStateException> {
            repository.abortSession(sessionId, Instant.ofEpochMilli(30))
        }
        val history = requireNotNull(repository.observeSessionWithFrames(sessionId).first())
        assertEquals(SessionStatus.ABORTED, history.session.status)
        assertEquals(1, history.frames.size)
    }

    @Test
    fun startupReconciliationAbortsOnlyOlderOpenSessionsAndRetainsEvidence() = runTest {
        val store = FakeSessionPersistence()
        val repository = RoomSessionHistoryRepository(store)
        val olderId = UUID.randomUUID().toString()
        val newerId = UUID.randomUUID().toString()
        repository.createSession(Instant.ofEpochMilli(10), DisplayUnit.KG, "Scale", olderId)
        repository.appendFrame(olderId, frame(0, byteArrayOf(0x12)))
        repository.createSession(Instant.ofEpochMilli(30), DisplayUnit.KG, "Scale", newerId)

        assertEquals(1, repository.abortOpenSessionsStartedBefore(Instant.ofEpochMilli(20)))

        assertEquals(SessionStatus.ABORTED, store.sessions.getValue(olderId).status)
        assertEquals(SessionStatus.OPEN, store.sessions.getValue(newerId).status)
        assertEquals(1, requireNotNull(repository.observeSessionWithFrames(olderId).first()).frames.size)
    }

    @Test
    fun invalidSessionAndFrameMetadataAreRejectedBeforePersistence() = runTest {
        val store = FakeSessionPersistence()
        val repository = RoomSessionHistoryRepository(store)

        assertSuspendThrows<IllegalArgumentException> {
            repository.createSession(
                id = "not-a-uuid",
                startedAt = Instant.EPOCH,
                requestedDisplayUnit = DisplayUnit.KG,
                safeAdvertisedName = "Scale",
            )
        }
        assertTrue(store.sessions.isEmpty())

        repository.createSession(
            id = sessionId,
            startedAt = Instant.EPOCH,
            requestedDisplayUnit = DisplayUnit.KG,
            safeAdvertisedName = "Scale",
        )
        assertSuspendThrows<IllegalArgumentException> {
            repository.appendFrame(sessionId, frame(-1, byteArrayOf()))
        }
        assertTrue(store.frames.isEmpty())
    }

    private fun frame(sequence: Long, raw: ByteArray) = NewRawFrame(
        sequence = sequence,
        observedAt = Instant.ofEpochMilli(sequence.coerceAtLeast(0)),
        elapsedMs = sequence.coerceAtLeast(0),
        direction = FrameDirection.RX,
        characteristicUuid = characteristicId,
        raw = raw,
    )
}

private class FakeSessionPersistence : SessionPersistence {
    private val sessionState = MutableStateFlow<Map<String, SessionEntity>>(emptyMap())
    private val frameState = MutableStateFlow<Map<Pair<String, Long>, RawFrameEntity>>(emptyMap())

    val sessions: Map<String, SessionEntity>
        get() = sessionState.value
    val frames: Map<Pair<String, Long>, RawFrameEntity>
        get() = frameState.value

    override suspend fun createSession(session: SessionEntity) {
        check(session.id !in sessionState.value) { "duplicate session" }
        sessionState.value = sessionState.value + (session.id to session)
    }

    override suspend fun appendFrame(frame: RawFrameEntity) {
        val session = sessionState.value[frame.sessionId]
            ?: throw SessionNotFoundException(frame.sessionId)
        SessionRules.requireOpen(session.status, "append")
        val key = frame.sessionId to frame.sequence
        check(key !in frameState.value) { "duplicate frame" }
        frameState.value = frameState.value + (key to frame.copy(raw = frame.raw.copyOf()))
    }

    override suspend fun finishSession(id: String, status: SessionStatus, endedAt: Instant) {
        val session = sessionState.value[id] ?: throw SessionNotFoundException(id)
        SessionRules.requireOpen(session.status, "finish")
        SessionRules.requireEndTime(session.startedAt, endedAt)
        sessionState.value = sessionState.value + (id to session.copy(status = status, endedAt = endedAt))
    }

    override suspend fun abortOpenSessionsStartedBefore(cutoff: Instant): Int {
        val interrupted = sessionState.value.values.filter {
            it.status == SessionStatus.OPEN && it.startedAt.isBefore(cutoff)
        }
        sessionState.value = sessionState.value + interrupted.associate {
            it.id to it.copy(status = SessionStatus.ABORTED, endedAt = cutoff)
        }
        return interrupted.size
    }

    override fun observeRecentSessions(limit: Int): Flow<List<SessionSummaryRow>> =
        sessionState.map { current ->
            current.values
                .sortedWith(compareByDescending<SessionEntity> { it.startedAt }.thenByDescending { it.id })
                .take(limit)
                .map { session ->
                    SessionSummaryRow(
                        session,
                        frameState.value.keys.count { it.first == session.id }.toLong(),
                    )
                }
        }

    override fun observeSession(id: String): Flow<SessionEntity?> =
        sessionState.map { it[id] }

    override fun observeFrames(id: String): Flow<List<RawFrameEntity>> =
        frameState.map { current -> current.values.filter { it.sessionId == id } }
}

private suspend inline fun <reified T : Throwable> assertSuspendThrows(
    crossinline block: suspend () -> Unit,
) {
    try {
        block()
        fail("expected ${T::class.java.simpleName}")
    } catch (actual: Throwable) {
        if (actual !is T) throw actual
    }
}
