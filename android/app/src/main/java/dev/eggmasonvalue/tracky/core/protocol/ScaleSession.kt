package dev.eggmasonvalue.tracky.core.protocol

import java.util.Collections

/** Handshake phases after the transport has enabled FFF1 notifications. */
enum class SessionState {
    WAITING_INFO,
    WAITING_14,
    WAITING_21,
    LIVE,
}

/** Result of one [ScaleSession.feed] call. Write bytes are defensively copied. */
class SessionOutput internal constructor(
    val decodeResult: DecodeResult,
    writes: List<ByteArray>,
    val state: SessionState,
) {
    private val writeSnapshots = writes.map(ByteArray::copyOf)

    val writes: List<ByteArray>
        get() = Collections.unmodifiableList(writeSnapshots.map(ByteArray::copyOf))

    val txWrites: List<ByteArray>
        get() = writes
}

/**
 * Pure state machine for the fixture-backed CS10B handshake.
 *
 * BLE connection, notification setup, and characteristic writes remain transport concerns. A new
 * session starts after notifications have been enabled and waits for a valid 0x12 info frame.
 */
class ScaleSession(
    unitByte: Int = WeightUnit.KG.byte,
    qnEpochSeconds: Long? = null,
) {
    private val fixedQnEpochSeconds = qnEpochSeconds

    val unitByte: Int = unitByte.also {
        require(it in 0..0xff) { "unitByte must be an unsigned byte" }
    }

    var state: SessionState = SessionState.WAITING_INFO
        private set

    var protocolType: Int? = null
        private set

    var configured: Boolean = false
        private set

    private val receivedResultSnapshots = mutableListOf<DecodeResult>()
    private val measurementSnapshots = mutableListOf<MeasurementFrame>()

    val receivedResults: List<DecodeResult>
        get() = Collections.unmodifiableList(receivedResultSnapshots.toList())

    val measurements: List<MeasurementFrame>
        get() = Collections.unmodifiableList(measurementSnapshots.toList())

    val waitingForInfo: Boolean
        get() = state == SessionState.WAITING_INFO

    /** The state machine begins at the point where the BLE layer has enabled notifications. */
    val notificationsEnabled: Boolean
        get() = true

    constructor(unitByte: Int, qnEpochSeconds: Int) :
        this(unitByte, qnEpochSeconds.toLong())

    /** Feed one RX packet and return its decode result and immediate transition writes. */
    fun feed(raw: ByteArray, qnEpochSeconds: Long? = null): SessionOutput {
        val decoded = ScaleProtocol.decode(raw)
        receivedResultSnapshots += decoded
        val writes = mutableListOf<ByteArray>()

        val frame = (decoded as? DecodeResult.Success)?.frame
        if (frame == null || frame.checksumValid == false) {
            return SessionOutput(decoded, writes, state)
        }

        when {
            frame is InfoFrame && state == SessionState.WAITING_INFO -> {
                protocolType = frame.protocolType
                val epoch = resolveEpoch(qnEpochSeconds)
                writes += ScaleProtocol.buildUnitConfig(frame.protocolType, unitByte)
                writes += ScaleProtocol.buildTimePush(epoch)
                configured = true
                state = SessionState.WAITING_14
            }

            frame is StatusFrame &&
                frame.opcode == Opcode.STATUS_14.byte &&
                state == SessionState.WAITING_14 -> {
                val type = protocolType ?: frame.protocolType.also { protocolType = it }
                writes += ScaleProtocol.buildTimeSync(type, resolveEpoch(qnEpochSeconds))
                state = SessionState.WAITING_21
            }

            frame is StatusFrame &&
                frame.opcode == Opcode.STATUS_21.byte &&
                state == SessionState.WAITING_21 -> {
                writes += ScaleProtocol.buildA0Writes()
                state = SessionState.LIVE
            }

            frame is MeasurementFrame -> measurementSnapshots += frame
        }

        return SessionOutput(decoded, writes, state)
    }

    fun feed(raw: ByteArray, qnEpochSeconds: Int): SessionOutput =
        feed(raw, qnEpochSeconds.toLong())

    fun reset() {
        state = SessionState.WAITING_INFO
        protocolType = null
        configured = false
        receivedResultSnapshots.clear()
        measurementSnapshots.clear()
    }

    private fun resolveEpoch(override: Long?): Long =
        override ?: fixedQnEpochSeconds
        ?: ScaleProtocol.qnEpochFromUnix(System.currentTimeMillis() / 1_000L)
}
