package dev.eggmasonvalue.tracky.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.UUID

class MappingsTest {
    @Test
    fun resistanceEncodingRoundTripsOrderAndUnsignedExtremes() {
        val values = listOf(0xffff, 0, 257, 42, 42)

        val encoded = ResistanceValuesCodec.encode(values)
        val decoded = ResistanceValuesCodec.decode(encoded)

        assertEquals(values, decoded)
        assertNotSame(values, decoded)
        encoded.fill(0)
        assertEquals(values, decoded)
    }

    @Test
    fun resistanceDecoderRejectsTruncationAndTrailingBytes() {
        val encoded = ResistanceValuesCodec.encode(listOf(1, 2))

        assertThrows(IllegalArgumentException::class.java) {
            ResistanceValuesCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResistanceValuesCodec.decode(encoded + 0)
        }
    }

    @Test
    fun frameMappingDefensivelyCopiesRawBytesInBothDirections() {
        val source = byteArrayOf(1, 2, 3)
        val frame = NewRawFrame(
            sequence = 7,
            observedAt = Instant.ofEpochMilli(1_234),
            elapsedMs = 234,
            direction = FrameDirection.RX,
            characteristicUuid = UUID.randomUUID().toString(),
            raw = source,
        )
        source[0] = 99

        val entity = frame.toEntity(UUID.randomUUID().toString())
        assertArrayEquals(byteArrayOf(1, 2, 3), entity.raw)

        val evidence = entity.toDomain()
        entity.raw[1] = 88
        val firstRead = evidence.raw
        firstRead[2] = 77

        assertArrayEquals(byteArrayOf(1, 2, 3), evidence.raw)
    }

    @Test
    fun measurementMappingIsLosslessAndUnitFree() {
        val measurement = MeasurementInterpretation(
            rawU16 = 0xabcd,
            b5 = 0xab,
            b6 = 0xcd,
            resistanceValues = listOf(500, 100, 500),
        )
        val frame = NewRawFrame(
            sequence = 0,
            observedAt = Instant.EPOCH,
            elapsedMs = 0,
            direction = FrameDirection.TX,
            characteristicUuid = UUID.randomUUID().toString(),
            raw = byteArrayOf(),
            opcode = 0xff,
            checksum = 0,
            frameKind = "measurement",
            measurement = measurement,
        )

        val mapped = frame.toEntity(UUID.randomUUID().toString()).toDomain()

        assertEquals(measurement, mapped.measurement)
        assertEquals(100, mapped.measurement?.divisor)
        assertEquals(listOf(500, 100, 500), mapped.measurement?.resistanceValues)
    }

    @Test
    fun partiallyPopulatedMeasurementIsRejected() {
        val entity = rawFrameEntity().copy(measurementRawU16 = 123)

        assertThrows(IllegalStateException::class.java) { entity.toDomain() }
    }

    @Test
    fun convertersPersistInstantsAsEpochMillis() {
        val converters = TrackyTypeConverters()
        val instant = Instant.ofEpochMilli(9_876_543_210)

        assertEquals(9_876_543_210L, converters.instantToEpochMillis(instant))
        assertEquals(instant, converters.epochMillisToInstant(9_876_543_210))
    }

    private fun rawFrameEntity() = RawFrameEntity(
        sessionId = UUID.randomUUID().toString(),
        sequence = 0,
        observedAt = Instant.EPOCH,
        elapsedMs = 0,
        direction = FrameDirection.RX,
        characteristicUuid = UUID.randomUUID().toString(),
        raw = byteArrayOf(),
        opcode = null,
        checksum = null,
        frameKind = null,
        decodeError = null,
        measurementRawU16 = null,
        measurementDivisor = null,
        measurementB5 = null,
        measurementB6 = null,
        measurementResistanceValues = null,
    )
}
