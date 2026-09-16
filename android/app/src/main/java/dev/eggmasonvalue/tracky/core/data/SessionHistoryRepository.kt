package dev.eggmasonvalue.tracky.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

interface SessionHistoryRepository {
    /** Creates an OPEN session. The insert is atomic and duplicate IDs fail rather than replace. */
    suspend fun createSession(
        startedAt: Instant,
        requestedDisplayUnit: DisplayUnit,
        safeAdvertisedName: String,
        id: String = UUID.randomUUID().toString(),
    ): Session

    /** Atomically verifies that the session is OPEN and inserts a never-replaced evidence frame. */
    suspend fun appendFrame(sessionId: String, frame: NewRawFrame): RawFrameEvidence

    /** Marks an OPEN session COMPLETE without changing or deleting any evidence. */
    suspend fun completeSession(sessionId: String, endedAt: Instant)

    /** Marks an OPEN session ABORTED without changing or deleting any evidence. */
    suspend fun abortSession(sessionId: String, endedAt: Instant)

    /** Reconciles sessions interrupted by a prior process death, excluding newer attempts. */
    suspend fun abortOpenSessionsStartedBefore(cutoff: Instant): Int

    fun observeRecentSessions(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<SessionSummary>>

    fun observeSessionWithFrames(sessionId: String): Flow<SessionWithFrames?>

    companion object {
        const val DEFAULT_RECENT_LIMIT: Int = 50
    }
}

class RoomSessionHistoryRepository internal constructor(
    private val persistence: SessionPersistence,
) : SessionHistoryRepository {
    constructor(database: TrackyDatabase) : this(database.trackyDao())

    override suspend fun createSession(
        startedAt: Instant,
        requestedDisplayUnit: DisplayUnit,
        safeAdvertisedName: String,
        id: String,
    ): Session {
        val canonicalId = SessionRules.canonicalUuid(id, "session id")
        startedAt.toEpochMilli()
        SessionRules.requireSafeAdvertisedName(safeAdvertisedName)

        val entity = SessionEntity(
            id = canonicalId,
            status = SessionStatus.OPEN,
            startedAt = startedAt,
            endedAt = null,
            requestedDisplayUnit = requestedDisplayUnit,
            safeAdvertisedName = safeAdvertisedName,
            selectedFrameSequence = null,
        )
        persistence.createSession(entity)
        return entity.toDomain()
    }

    override suspend fun appendFrame(sessionId: String, frame: NewRawFrame): RawFrameEvidence {
        val canonicalId = SessionRules.canonicalUuid(sessionId, "session id")
        val entity = frame.toEntity(canonicalId)
        persistence.appendFrame(entity)
        return entity.toDomain()
    }

    override suspend fun completeSession(sessionId: String, endedAt: Instant) {
        finish(sessionId, SessionStatus.COMPLETE, endedAt)
    }

    override suspend fun abortSession(sessionId: String, endedAt: Instant) {
        finish(sessionId, SessionStatus.ABORTED, endedAt)
    }

    override suspend fun abortOpenSessionsStartedBefore(cutoff: Instant): Int {
        cutoff.toEpochMilli()
        return persistence.abortOpenSessionsStartedBefore(cutoff)
    }

    private suspend fun finish(sessionId: String, status: SessionStatus, endedAt: Instant) {
        val canonicalId = SessionRules.canonicalUuid(sessionId, "session id")
        endedAt.toEpochMilli()
        persistence.finishSession(canonicalId, status, endedAt)
    }

    override fun observeRecentSessions(limit: Int): Flow<List<SessionSummary>> {
        require(limit > 0) { "limit must be positive" }
        return persistence.observeRecentSessions(limit).map { rows ->
            rows.map { row -> SessionSummary(row.session.toDomain(), row.frameCount) }
        }
    }

    override fun observeSessionWithFrames(sessionId: String): Flow<SessionWithFrames?> {
        val canonicalId = SessionRules.canonicalUuid(sessionId, "session id")
        return combine(
            persistence.observeSession(canonicalId),
            persistence.observeFrames(canonicalId),
        ) { session, frames ->
            session?.let {
                SessionWithFrames(
                    session = it.toDomain(),
                    frames = frames.sortedBy(RawFrameEntity::sequence).map(RawFrameEntity::toDomain),
                )
            }
        }
    }
}
