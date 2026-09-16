package dev.eggmasonvalue.tracky.core.data

import java.time.Instant
import java.util.Collections

/** The unit the user asked the scale UI to display. Measurements remain stored unit-free. */
enum class DisplayUnit {
    KG,
    LB,
}

enum class SessionStatus {
    OPEN,
    COMPLETE,
    ABORTED,
}

enum class FrameDirection {
    RX,
    TX,
}

data class Session(
    val id: String,
    val status: SessionStatus,
    val startedAt: Instant,
    val endedAt: Instant?,
    val requestedDisplayUnit: DisplayUnit,
    val safeAdvertisedName: String,
    /** Reserved for a future selection feature. This data layer always writes null. */
    val selectedFrameSequence: Long?,
)

data class SessionSummary(
    val session: Session,
    val frameCount: Long,
)

/**
 * A protocol interpretation, not a converted weight. Resistance readings intentionally have no
 * unit because the wire protocol does not establish one.
 */
class MeasurementInterpretation(
    val rawU16: Int,
    val divisor: Int = DIVISOR,
    val b5: Int,
    val b6: Int,
    resistanceValues: List<Int>,
) {
    private val resistanceSnapshot = immutableListCopy(resistanceValues)

    val resistanceValues: List<Int>
        get() = resistanceSnapshot

    init {
        require(rawU16 in 0..0xffff) { "rawU16 must be an unsigned 16-bit value" }
        require(divisor == DIVISOR) { "measurement divisor must be $DIVISOR" }
        require(b5 in 0..0xff) { "b5 must be an unsigned byte" }
        require(b6 in 0..0xff) { "b6 must be an unsigned byte" }
        require(resistanceSnapshot.all { it in 0..0xffff }) {
            "resistance values must be unsigned 16-bit values"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is MeasurementInterpretation &&
            rawU16 == other.rawU16 &&
            divisor == other.divisor &&
            b5 == other.b5 &&
            b6 == other.b6 &&
            resistanceSnapshot == other.resistanceSnapshot

    override fun hashCode(): Int {
        var result = rawU16
        result = 31 * result + divisor
        result = 31 * result + b5
        result = 31 * result + b6
        result = 31 * result + resistanceSnapshot.hashCode()
        return result
    }

    override fun toString(): String =
        "MeasurementInterpretation(rawU16=$rawU16, divisor=$divisor, b5=$b5, b6=$b6, " +
            "resistanceValues=$resistanceSnapshot)"

    companion object {
        const val DIVISOR: Int = 100
    }
}

/** Input for an evidence frame. The payload is copied on construction and whenever it is read. */
class NewRawFrame(
    val sequence: Long,
    val observedAt: Instant,
    val elapsedMs: Long,
    val direction: FrameDirection,
    val characteristicUuid: String,
    raw: ByteArray,
    val opcode: Int? = null,
    val checksum: Int? = null,
    val frameKind: String? = null,
    val decodeError: String? = null,
    val measurement: MeasurementInterpretation? = null,
) {
    private val rawSnapshot = raw.copyOf()

    val raw: ByteArray
        get() = rawSnapshot.copyOf()

    internal fun rawCopy(): ByteArray = rawSnapshot.copyOf()
}

/** Immutable persisted frame evidence. */
class RawFrameEvidence(
    val sessionId: String,
    val sequence: Long,
    val observedAt: Instant,
    val elapsedMs: Long,
    val direction: FrameDirection,
    val characteristicUuid: String,
    raw: ByteArray,
    val opcode: Int?,
    val checksum: Int?,
    val frameKind: String?,
    val decodeError: String?,
    val measurement: MeasurementInterpretation?,
) {
    private val rawSnapshot = raw.copyOf()

    val raw: ByteArray
        get() = rawSnapshot.copyOf()

    internal fun rawCopy(): ByteArray = rawSnapshot.copyOf()

    override fun equals(other: Any?): Boolean =
        other is RawFrameEvidence &&
            sessionId == other.sessionId &&
            sequence == other.sequence &&
            observedAt == other.observedAt &&
            elapsedMs == other.elapsedMs &&
            direction == other.direction &&
            characteristicUuid == other.characteristicUuid &&
            rawSnapshot.contentEquals(other.rawSnapshot) &&
            opcode == other.opcode &&
            checksum == other.checksum &&
            frameKind == other.frameKind &&
            decodeError == other.decodeError &&
            measurement == other.measurement

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + observedAt.hashCode()
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + characteristicUuid.hashCode()
        result = 31 * result + rawSnapshot.contentHashCode()
        result = 31 * result + (opcode ?: 0)
        result = 31 * result + (checksum ?: 0)
        result = 31 * result + (frameKind?.hashCode() ?: 0)
        result = 31 * result + (decodeError?.hashCode() ?: 0)
        result = 31 * result + (measurement?.hashCode() ?: 0)
        return result
    }
}

class SessionWithFrames(
    val session: Session,
    frames: List<RawFrameEvidence>,
) {
    private val frameSnapshot = immutableListCopy(frames)

    val frames: List<RawFrameEvidence>
        get() = frameSnapshot

    init {
        require(frameSnapshot.all { it.sessionId == session.id }) {
            "all frames must belong to session ${session.id}"
        }
        require(frameSnapshot.zipWithNext().all { (first, second) -> first.sequence < second.sequence }) {
            "frames must have unique, increasing sequence numbers"
        }
    }
}

internal fun <T> immutableListCopy(source: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))
