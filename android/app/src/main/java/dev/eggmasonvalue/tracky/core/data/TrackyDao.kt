package dev.eggmasonvalue.tracky.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.Instant

internal interface SessionPersistence {
    suspend fun createSession(session: SessionEntity)
    suspend fun appendFrame(frame: RawFrameEntity)
    suspend fun finishSession(id: String, status: SessionStatus, endedAt: Instant)
    suspend fun abortOpenSessionsStartedBefore(cutoff: Instant): Int
    fun observeRecentSessions(limit: Int): Flow<List<SessionSummaryRow>>
    fun observeSession(id: String): Flow<SessionEntity?>
    fun observeFrames(id: String): Flow<List<RawFrameEntity>>
}

@Dao
internal abstract class TrackyDao : SessionPersistence {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertFrame(frame: RawFrameEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    protected abstract suspend fun getSession(id: String): SessionEntity?

    @Query(
        """
        UPDATE sessions
        SET status = :status, ended_at_epoch_ms = :endedAt
        WHERE id = :id AND status = 'OPEN'
        """,
    )
    protected abstract suspend fun updateOpenSession(
        id: String,
        status: SessionStatus,
        endedAt: Instant,
    ): Int

    @Transaction
    override open suspend fun createSession(session: SessionEntity) {
        insertSession(session)
    }

    @Transaction
    override open suspend fun appendFrame(frame: RawFrameEntity) {
        val session = getSession(frame.sessionId)
            ?: throw SessionNotFoundException(frame.sessionId)
        SessionRules.requireOpen(session.status, "append")
        insertFrame(frame)
    }

    @Transaction
    override open suspend fun finishSession(id: String, status: SessionStatus, endedAt: Instant) {
        require(status == SessionStatus.COMPLETE || status == SessionStatus.ABORTED) {
            "finish status must be COMPLETE or ABORTED"
        }
        val session = getSession(id) ?: throw SessionNotFoundException(id)
        SessionRules.requireOpen(session.status, "finish")
        SessionRules.requireEndTime(session.startedAt, endedAt)
        check(updateOpenSession(id, status, endedAt) == 1) {
            "session $id changed while it was being finished"
        }
    }

    @Query(
        """
        UPDATE sessions
        SET status = 'ABORTED', ended_at_epoch_ms = :cutoff
        WHERE status = 'OPEN' AND started_at_epoch_ms < :cutoff
        """,
    )
    abstract override suspend fun abortOpenSessionsStartedBefore(cutoff: Instant): Int

    @Query(
        """
        SELECT s.*, COUNT(f.session_id) AS frame_count
        FROM sessions AS s
        LEFT JOIN raw_frames AS f ON f.session_id = s.id
        GROUP BY s.id
        ORDER BY s.started_at_epoch_ms DESC, s.id DESC
        LIMIT :limit
        """,
    )
    abstract override fun observeRecentSessions(limit: Int): Flow<List<SessionSummaryRow>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    abstract override fun observeSession(id: String): Flow<SessionEntity?>

    @Query(
        """
        SELECT * FROM raw_frames
        WHERE session_id = :id
        ORDER BY sequence ASC
        """,
    )
    abstract override fun observeFrames(id: String): Flow<List<RawFrameEntity>>
}

class SessionNotFoundException(id: String) :
    IllegalStateException("session $id does not exist")
