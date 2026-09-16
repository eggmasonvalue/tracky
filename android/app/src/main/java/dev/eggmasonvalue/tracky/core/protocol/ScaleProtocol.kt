package dev.eggmasonvalue.tracky.core.protocol

import java.util.Collections

/** Opcodes observed in the sanitized CS10B captures. */
enum class Opcode(val byte: Int) {
    MEASUREMENT(0x10),
    INFO(0x12),
    UNIT_CONFIG(0x13),
    STATUS_14(0x14),
    TIME_PUSH(0x02),
    TIME_SYNC(0x20),
    STATUS_21(0x21),
    A0(0xa0),
}

/** Unit selector bytes observed in unit-configuration writes. */
enum class WeightUnit(val byte: Int) {
    KG(0x01),
    LB(0x02),
}

/** The result of decoding one packet. Malformed packets retain every input byte. */
sealed interface DecodeResult {
    class Success(val frame: DecodedFrame) : DecodeResult

    class Malformed internal constructor(
        raw: ByteArray,
        val opcode: Int?,
        val checksumValid: Boolean?,
        val reason: String,
    ) : DecodeResult {
        private val rawSnapshot = raw.copyOf()

        val raw: ByteArray
            get() = rawSnapshot.copyOf()

        val rawHex: String
            get() = rawSnapshot.toHex()
    }
}

/** A byte-preserving typed protocol frame. */
sealed class DecodedFrame protected constructor(
    raw: ByteArray,
    val opcode: Int,
    val checksumValid: Boolean?,
) {
    private val rawSnapshot = raw.copyOf()

    val raw: ByteArray
        get() = rawSnapshot.copyOf()

    val rawBytes: ByteArray
        get() = raw

    val rawHex: String
        get() = rawSnapshot.toHex()

    val frameHex: String
        get() = rawHex

    val checksumByte: Int?
        get() = rawSnapshot.lastOrNull()?.toInt()?.and(0xff)

    val checksum: Int?
        get() = checksumByte

    val isChecksumBearing: Boolean
        get() = opcode in ScaleProtocol.CHECKSUM_BEARING_OPCODES

    val payload: ByteArray
        get() = if (checksumValid == null) {
            rawSnapshot.copyOfRange(1, rawSnapshot.size)
        } else {
            rawSnapshot.copyOfRange(1, rawSnapshot.lastIndex)
        }

    val opcodeName: String
        get() = when (opcode) {
            Opcode.MEASUREMENT.byte -> "measurement_0x10"
            Opcode.INFO.byte -> "info_0x12"
            Opcode.UNIT_CONFIG.byte -> "unit_config_0x13"
            Opcode.STATUS_14.byte -> "status_0x14"
            Opcode.TIME_PUSH.byte -> "time_push"
            Opcode.TIME_SYNC.byte -> "time_sync_0x20"
            Opcode.STATUS_21.byte -> "status_0x21"
            Opcode.A0.byte -> "a0"
            else -> "unknown_0x${opcode.toString(16).padStart(2, '0')}"
        }
}

class InfoFrame internal constructor(
    raw: ByteArray,
    checksumValid: Boolean,
    val protocolType: Int,
    infoPayload: ByteArray,
    val lengthField: Int,
) : DecodedFrame(raw, Opcode.INFO.byte, checksumValid) {
    private val infoPayloadSnapshot = infoPayload.copyOf()

    val infoPayload: ByteArray
        get() = infoPayloadSnapshot.copyOf()

    val data: ByteArray
        get() = infoPayload
}

class StatusFrame internal constructor(
    raw: ByteArray,
    opcode: Int,
    checksumValid: Boolean,
    val protocolType: Int,
    val statusByte: Int,
    statusPayload: ByteArray,
    val lengthField: Int,
) : DecodedFrame(raw, opcode, checksumValid) {
    private val statusPayloadSnapshot = statusPayload.copyOf()

    val status: Int
        get() = statusByte

    val statusPayload: ByteArray
        get() = statusPayloadSnapshot.copyOf()

    val data: ByteArray
        get() = statusPayload
}

class MeasurementFrame internal constructor(
    raw: ByteArray,
    checksumValid: Boolean,
    val protocolType: Int,
    val weightRawU16Be: Int,
    val b5: Int,
    val b6: Int,
    resistanceRaw: List<Int>,
) : DecodedFrame(raw, Opcode.MEASUREMENT.byte, checksumValid) {
    private val resistanceSnapshot = Collections.unmodifiableList(resistanceRaw.toList())

    val weightRaw: Int
        get() = weightRawU16Be

    val weightKg: Double
        get() = weightRawU16Be / WEIGHT_SCALE_DIVISOR.toDouble()

    val weightFormula: String
        get() = "int.from_bytes(frame[3:5], 'big') / 100"

    val weightProvenance: String
        get() = "High-confidence CS10B capture layout: big-endian uint16 at bytes 3..4 divided by 100 gives kilograms."

    val weightUnit: String
        get() = "kg"

    val weightScaleDivisor: Int
        get() = WEIGHT_SCALE_DIVISOR

    val resistanceRaw: List<Int>
        get() = resistanceSnapshot

    val resistanceValues: List<Int>
        get() = resistanceSnapshot

    val hasResistance: Boolean
        get() = resistanceSnapshot.isNotEmpty()

    val resistanceLayout: String?
        get() = if (hasResistance) {
            "five big-endian u16 values at bytes 7..16 when b5=0x01 and b6=0x21"
        } else {
            null
        }

    /** No unit has been established by the captures. */
    val resistanceUnit: String?
        get() = null

    val resistanceProvenance: String
        get() = "Opaque five-value block; field boundaries are observed, but units, frequencies, and segment meaning are unproven."

    companion object {
        const val WEIGHT_SCALE_DIVISOR: Int = 100
    }
}

class CommandFrame internal constructor(
    raw: ByteArray,
    opcode: Int,
    checksumValid: Boolean?,
    val commandName: String,
    commandPayload: ByteArray,
    val protocolType: Int?,
    val unitByte: Int?,
    val qnEpochSeconds: Long?,
) : DecodedFrame(raw, opcode, checksumValid) {
    private val commandPayloadSnapshot = commandPayload.copyOf()

    val name: String
        get() = commandName

    val commandPayload: ByteArray
        get() = commandPayloadSnapshot.copyOf()

    val data: ByteArray
        get() = commandPayload
}

class UnknownFrame internal constructor(raw: ByteArray, opcode: Int) :
    DecodedFrame(raw, opcode, checksumValid = null)

/** Pure CS10B checksums, command builders, and decoder. */
object ScaleProtocol {
    const val QN_EPOCH_UNIX_SECONDS: Long = 946_684_800L
    const val DECODER_ID: String = "tracky-cs10b-capture-layout"
    const val DECODER_VERSION: Int = 1

    val CHECKSUM_BEARING_OPCODES: Set<Int> = Collections.unmodifiableSet(
        setOf(
            Opcode.MEASUREMENT.byte,
            Opcode.INFO.byte,
            Opcode.UNIT_CONFIG.byte,
            Opcode.STATUS_14.byte,
            Opcode.TIME_SYNC.byte,
            Opcode.STATUS_21.byte,
            Opcode.A0.byte,
        ),
    )

    /** Additive one-byte checksum: sum(body) modulo 256. */
    fun checksum(body: ByteArray): Int = body.fold(0) { sum, byte ->
        (sum + (byte.toInt() and 0xff)) and 0xff
    }

    fun appendChecksum(body: ByteArray): ByteArray {
        val result = body.copyOf(body.size + 1)
        result[result.lastIndex] = checksum(body).toByte()
        return result
    }

    fun hasValidChecksum(frame: ByteArray): Boolean =
        frame.isNotEmpty() && checksum(frame.copyOf(frame.lastIndex)) == (frame.last().toInt() and 0xff)

    fun qnEpochFromUnix(unixEpochSeconds: Long): Long =
        requireU32(unixEpochSeconds - QN_EPOCH_UNIX_SECONDS, "QN epoch seconds")

    fun buildUnitConfig(
        protocolType: Int = 0x20,
        unitByte: Int = WeightUnit.KG.byte,
    ): ByteArray = appendChecksum(
        byteArrayOf(
            Opcode.UNIT_CONFIG.byte.toByte(),
            0x09,
            requireByte(protocolType, "protocolType").toByte(),
            requireByte(unitByte, "unitByte").toByte(),
            0x10,
            0x00,
            0x00,
            0x00,
        ),
    )

    fun buildUnitConfig(protocolType: Int, unit: WeightUnit): ByteArray =
        buildUnitConfig(protocolType, unit.byte)

    fun buildTimePush(qnEpochSeconds: Long = currentQnEpochSeconds()): ByteArray =
        byteArrayOf(Opcode.TIME_PUSH.byte.toByte()) + littleEndianU32(qnEpochSeconds)

    fun buildTimePush(qnEpochSeconds: Int): ByteArray = buildTimePush(qnEpochSeconds.toLong())

    fun buildTimeSync(
        protocolType: Int = 0x20,
        qnEpochSeconds: Long = currentQnEpochSeconds(),
    ): ByteArray = appendChecksum(
        byteArrayOf(
            Opcode.TIME_SYNC.byte.toByte(),
            0x08,
            requireByte(protocolType, "protocolType").toByte(),
        ) + littleEndianU32(qnEpochSeconds),
    )

    fun buildTimeSync(protocolType: Int, qnEpochSeconds: Int): ByteArray =
        buildTimeSync(protocolType, qnEpochSeconds.toLong())

    fun buildA0First(): ByteArray = appendChecksum(
        byteArrayOf(
            0xa0.toByte(), 0x0d, 0x04, 0xfe.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        ),
    )

    fun buildA0Second(): ByteArray = appendChecksum(
        byteArrayOf(
            0xa0.toByte(), 0x0d, 0x02, 0x01, 0x00, 0x08,
            0x00, 0x21, 0x06, 0xb8.toByte(), 0x04, 0x02,
        ),
    )

    fun buildA0Writes(): List<ByteArray> = listOf(buildA0First(), buildA0Second())

    /** Decode without discarding malformed or checksum-failing bytes. */
    fun decode(raw: ByteArray): DecodeResult {
        val bytes = raw.copyOf()
        if (bytes.isEmpty()) {
            return DecodeResult.Malformed(bytes, null, null, "cannot decode an empty frame")
        }

        val opcode = bytes[0].toInt() and 0xff
        val checksumValid = if (opcode in CHECKSUM_BEARING_OPCODES) hasValidChecksum(bytes) else null

        fun malformed(reason: String): DecodeResult.Malformed =
            DecodeResult.Malformed(bytes, opcode, checksumValid, reason)

        fun requireMinimum(minimum: Int, name: String): DecodeResult.Malformed? =
            if (bytes.size < minimum) {
                malformed("$name frame is too short: expected at least $minimum bytes")
            } else {
                null
            }

        return when (opcode) {
            Opcode.MEASUREMENT.byte -> {
                if (bytes.size != 18) {
                    malformed("0x10 measurement frame must be 18 bytes, got ${bytes.size}")
                } else {
                    val b5 = bytes.u8(5)
                    val b6 = bytes.u8(6)
                    val resistance = if (b5 == 0x01 && b6 == 0x21) {
                        (7 until 17 step 2).map { bytes.u16Be(it) }
                    } else {
                        emptyList()
                    }
                    DecodeResult.Success(
                        MeasurementFrame(
                            raw = bytes,
                            checksumValid = checksumValid!!,
                            protocolType = bytes.u8(2),
                            weightRawU16Be = bytes.u16Be(3),
                            b5 = b5,
                            b6 = b6,
                            resistanceRaw = resistance,
                        ),
                    )
                }
            }

            Opcode.INFO.byte -> requireMinimum(4, "0x12 info") ?: DecodeResult.Success(
                InfoFrame(
                    raw = bytes,
                    checksumValid = checksumValid!!,
                    protocolType = bytes.u8(2),
                    infoPayload = bytes.copyOfRange(3, bytes.lastIndex),
                    lengthField = bytes.u8(1),
                ),
            )

            Opcode.STATUS_14.byte, Opcode.STATUS_21.byte ->
                requireMinimum(4, "0x${opcode.toString(16)} status") ?: DecodeResult.Success(
                    StatusFrame(
                        raw = bytes,
                        opcode = opcode,
                        checksumValid = checksumValid!!,
                        protocolType = bytes.u8(2),
                        statusByte = bytes.u8(3),
                        statusPayload = bytes.copyOfRange(3, bytes.lastIndex),
                        lengthField = bytes.u8(1),
                    ),
                )

            Opcode.UNIT_CONFIG.byte -> requireMinimum(4, "0x13 unit config") ?: DecodeResult.Success(
                CommandFrame(
                    raw = bytes,
                    opcode = opcode,
                    checksumValid = checksumValid,
                    commandName = "unit_config_0x13",
                    commandPayload = bytes.copyOfRange(1, bytes.lastIndex),
                    protocolType = bytes.u8(2),
                    unitByte = bytes.u8(3),
                    qnEpochSeconds = null,
                ),
            )

            Opcode.TIME_PUSH.byte -> {
                if (bytes.size != 5) {
                    malformed("0x02 time_push command must be 5 bytes, got ${bytes.size}")
                } else {
                    DecodeResult.Success(
                        CommandFrame(
                            raw = bytes,
                            opcode = opcode,
                            checksumValid = null,
                            commandName = "time_push",
                            commandPayload = bytes.copyOfRange(1, bytes.size),
                            protocolType = null,
                            unitByte = null,
                            qnEpochSeconds = bytes.u32Le(1),
                        ),
                    )
                }
            }

            Opcode.TIME_SYNC.byte -> requireMinimum(8, "0x20 time sync") ?: DecodeResult.Success(
                CommandFrame(
                    raw = bytes,
                    opcode = opcode,
                    checksumValid = checksumValid,
                    commandName = "time_sync_0x20",
                    commandPayload = bytes.copyOfRange(1, bytes.lastIndex),
                    protocolType = bytes.u8(2),
                    unitByte = null,
                    qnEpochSeconds = bytes.u32Le(3),
                ),
            )

            Opcode.A0.byte -> requireMinimum(2, "0xA0 command") ?: DecodeResult.Success(
                CommandFrame(
                    raw = bytes,
                    opcode = opcode,
                    checksumValid = checksumValid,
                    commandName = "a0",
                    commandPayload = bytes.copyOfRange(1, bytes.lastIndex),
                    protocolType = null,
                    unitByte = null,
                    qnEpochSeconds = null,
                ),
            )

            else -> DecodeResult.Success(UnknownFrame(bytes, opcode))
        }
    }

    private fun currentQnEpochSeconds(): Long = qnEpochFromUnix(System.currentTimeMillis() / 1_000L)

    private fun littleEndianU32(value: Long): ByteArray {
        val checked = requireU32(value, "QN epoch seconds")
        return ByteArray(4) { index -> ((checked ushr (index * 8)) and 0xff).toByte() }
    }

    private fun requireByte(value: Int, name: String): Int {
        require(value in 0..0xff) { "$name must be an unsigned byte, got $value" }
        return value
    }

    private fun requireU32(value: Long, name: String): Long {
        require(value in 0..0xffff_ffffL) { "$name must fit in an unsigned 32-bit value, got $value" }
        return value
    }
}

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

private fun ByteArray.u16Be(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

private fun ByteArray.u32Le(index: Int): Long =
    u8(index).toLong() or
        (u8(index + 1).toLong() shl 8) or
        (u8(index + 2).toLong() shl 16) or
        (u8(index + 3).toLong() shl 24)

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
