package dev.eggmasonvalue.tracky.core.data

import java.time.Instant
import java.util.UUID

internal object SessionRules {
    fun canonicalUuid(value: String, field: String): String {
        val canonical = try {
            UUID.fromString(value).toString()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("$field must be a UUID")
        }
        require(value.lowercase() == canonical) { "$field must be a canonical UUID" }
        return canonical
    }

    fun requireSafeAdvertisedName(value: String) {
        require(value.isNotBlank()) { "safeAdvertisedName must not be blank" }
        require(value.length <= 248) { "safeAdvertisedName is too long" }
        require(value.none(Char::isISOControl)) { "safeAdvertisedName contains control characters" }
    }

    fun requireOpen(status: SessionStatus, operation: String) {
        check(status == SessionStatus.OPEN) { "$operation requires an OPEN session" }
    }

    fun requireEndTime(startedAt: Instant, endedAt: Instant) {
        require(!endedAt.isBefore(startedAt)) { "endedAt must not be before startedAt" }
        endedAt.toEpochMilli()
    }

    fun validateNewFrame(frame: NewRawFrame): String {
        require(frame.sequence >= 0L) { "sequence must not be negative" }
        require(frame.elapsedMs >= 0L) { "elapsedMs must not be negative" }
        frame.observedAt.toEpochMilli()
        frame.opcode?.let { require(it in 0..0xff) { "opcode must be an unsigned byte" } }
        frame.checksum?.let { require(it in 0..0xff) { "checksum must be an unsigned byte" } }
        frame.frameKind?.let { require(it.isNotBlank()) { "frameKind must not be blank" } }
        frame.decodeError?.let { require(it.isNotBlank()) { "decodeError must not be blank" } }
        return canonicalUuid(frame.characteristicUuid, "characteristicUuid")
    }
}

internal fun SessionEntity.toDomain(): Session = Session(
    id = id,
    status = status,
    startedAt = startedAt,
    endedAt = endedAt,
    requestedDisplayUnit = requestedDisplayUnit,
    safeAdvertisedName = safeAdvertisedName,
    selectedFrameSequence = selectedFrameSequence,
)

internal fun RawFrameEntity.toDomain(): RawFrameEvidence {
    val measurementFields = listOf(
        measurementRawU16,
        measurementDivisor,
        measurementB5,
        measurementB6,
        measurementResistanceValues,
    )
    val measurement = when {
        measurementFields.all { it == null } -> null
        measurementFields.any { it == null } -> error("partially populated measurement for $sessionId/$sequence")
        else -> MeasurementInterpretation(
            rawU16 = requireNotNull(measurementRawU16),
            divisor = requireNotNull(measurementDivisor),
            b5 = requireNotNull(measurementB5),
            b6 = requireNotNull(measurementB6),
            resistanceValues = requireNotNull(measurementResistanceValues),
        )
    }

    return RawFrameEvidence(
        sessionId = sessionId,
        sequence = sequence,
        observedAt = observedAt,
        elapsedMs = elapsedMs,
        direction = direction,
        characteristicUuid = characteristicUuid,
        raw = raw.copyOf(),
        opcode = opcode,
        checksum = checksum,
        frameKind = frameKind,
        decodeError = decodeError,
        measurement = measurement,
    )
}

internal fun NewRawFrame.toEntity(sessionId: String): RawFrameEntity {
    val canonicalCharacteristicUuid = SessionRules.validateNewFrame(this)
    return RawFrameEntity(
        sessionId = sessionId,
        sequence = sequence,
        observedAt = observedAt,
        elapsedMs = elapsedMs,
        direction = direction,
        characteristicUuid = canonicalCharacteristicUuid,
        raw = rawCopy(),
        opcode = opcode,
        checksum = checksum,
        frameKind = frameKind,
        decodeError = decodeError,
        measurementRawU16 = measurement?.rawU16,
        measurementDivisor = measurement?.divisor,
        measurementB5 = measurement?.b5,
        measurementB6 = measurement?.b6,
        measurementResistanceValues = measurement?.resistanceValues?.toList(),
    )
}
