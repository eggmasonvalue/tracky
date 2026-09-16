package dev.eggmasonvalue.tracky.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "sessions",
    indices = [Index(value = ["started_at_epoch_ms"], name = "index_sessions_started_at")],
)
internal data class SessionEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "status")
    val status: SessionStatus,
    @ColumnInfo(name = "started_at_epoch_ms")
    val startedAt: java.time.Instant,
    @ColumnInfo(name = "ended_at_epoch_ms")
    val endedAt: java.time.Instant?,
    @ColumnInfo(name = "requested_display_unit")
    val requestedDisplayUnit: DisplayUnit,
    @ColumnInfo(name = "safe_advertised_name")
    val safeAdvertisedName: String,
    @ColumnInfo(name = "selected_frame_sequence")
    val selectedFrameSequence: Long?,
)

@Entity(
    tableName = "raw_frames",
    primaryKeys = ["session_id", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    // The primary-key index has session_id as its leftmost column, so no redundant index is needed.
)
internal data class RawFrameEntity(
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "sequence")
    val sequence: Long,
    @ColumnInfo(name = "observed_at_epoch_ms")
    val observedAt: java.time.Instant,
    @ColumnInfo(name = "elapsed_ms")
    val elapsedMs: Long,
    @ColumnInfo(name = "direction")
    val direction: FrameDirection,
    @ColumnInfo(name = "characteristic_uuid")
    val characteristicUuid: String,
    @ColumnInfo(name = "raw_bytes", typeAffinity = ColumnInfo.BLOB)
    val raw: ByteArray,
    @ColumnInfo(name = "opcode")
    val opcode: Int?,
    @ColumnInfo(name = "checksum")
    val checksum: Int?,
    @ColumnInfo(name = "frame_kind")
    val frameKind: String?,
    @ColumnInfo(name = "decode_error")
    val decodeError: String?,
    @ColumnInfo(name = "measurement_raw_u16")
    val measurementRawU16: Int?,
    @ColumnInfo(name = "measurement_divisor")
    val measurementDivisor: Int?,
    @ColumnInfo(name = "measurement_b5")
    val measurementB5: Int?,
    @ColumnInfo(name = "measurement_b6")
    val measurementB6: Int?,
    @ColumnInfo(name = "measurement_resistance_values", typeAffinity = ColumnInfo.BLOB)
    val measurementResistanceValues: List<Int>?,
)

internal data class SessionSummaryRow(
    @androidx.room.Embedded
    val session: SessionEntity,
    @ColumnInfo(name = "frame_count")
    val frameCount: Long,
)
