package dev.eggmasonvalue.tracky.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleProtocolTest {
    @Test
    fun `additive checksum is unsigned modulo 256 and append does not mutate input`() {
        val body = byteArrayOf(0xff.toByte(), 0x02, 0x01)
        val original = body.copyOf()

        assertEquals(0x02, ScaleProtocol.checksum(body))
        assertArrayEquals(hex("ff020102"), ScaleProtocol.appendChecksum(body))
        assertArrayEquals(original, body)
        assertTrue(ScaleProtocol.hasValidChecksum(hex("ff020102")))
        assertFalse(ScaleProtocol.hasValidChecksum(byteArrayOf()))
        assertFalse(ScaleProtocol.hasValidChecksum(hex("ff020103")))
    }

    @Test
    fun `opcode and unit constants match captures`() {
        assertEquals(0x10, Opcode.MEASUREMENT.byte)
        assertEquals(0x12, Opcode.INFO.byte)
        assertEquals(0x13, Opcode.UNIT_CONFIG.byte)
        assertEquals(0x14, Opcode.STATUS_14.byte)
        assertEquals(0x02, Opcode.TIME_PUSH.byte)
        assertEquals(0x20, Opcode.TIME_SYNC.byte)
        assertEquals(0x21, Opcode.STATUS_21.byte)
        assertEquals(0xa0, Opcode.A0.byte)
        assertEquals(0x01, WeightUnit.KG.byte)
        assertEquals(0x02, WeightUnit.LB.byte)
        assertFalse(Opcode.TIME_PUSH.byte in ScaleProtocol.CHECKSUM_BEARING_OPCODES)
    }

    @Test
    fun `command builders reproduce both capture goldens exactly`() {
        assertArrayEquals(hex("13092002100000004e"), ScaleProtocol.buildUnitConfig(0x20, WeightUnit.LB))
        assertArrayEquals(hex("02e4820b32"), ScaleProtocol.buildTimePush(839_615_204L))
        assertArrayEquals(hex("200820e4820b32eb"), ScaleProtocol.buildTimeSync(0x20, 839_615_204L))

        assertArrayEquals(hex("13092001100000004d"), ScaleProtocol.buildUnitConfig(0x20, WeightUnit.KG))
        assertArrayEquals(hex("028b8d0b32"), ScaleProtocol.buildTimePush(839_617_931))
        assertArrayEquals(hex("2008208b8d0b329d"), ScaleProtocol.buildTimeSync(0x20, 839_617_931))

        assertArrayEquals(hex("a00d04fe0000000000000000af"), ScaleProtocol.buildA0First())
        assertArrayEquals(hex("a00d02010008002106b804029d"), ScaleProtocol.buildA0Second())
        val a0 = ScaleProtocol.buildA0Writes()
        assertEquals(2, a0.size)
        assertArrayEquals(ScaleProtocol.buildA0First(), a0[0])
        assertArrayEquals(ScaleProtocol.buildA0Second(), a0[1])
    }

    @Test
    fun `QN epoch conversion and unsigned range are exact`() {
        assertEquals(0L, ScaleProtocol.qnEpochFromUnix(946_684_800L))
        assertEquals(839_615_204L, ScaleProtocol.qnEpochFromUnix(1_786_300_004L))
        assertArrayEquals(hex("02ffffffff"), ScaleProtocol.buildTimePush(0xffff_ffffL))
        assertThrows(IllegalArgumentException::class.java) {
            ScaleProtocol.qnEpochFromUnix(946_684_799L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScaleProtocol.buildTimePush(0x1_0000_0000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScaleProtocol.buildUnitConfig(unitByte = 256)
        }
    }

    @Test
    fun `all four sanitized decoder fixtures match their goldens`() {
        val expected = listOf(
            Fixture("cs10b_20260809T182636Z_expected_decode.json", 7_910, 79.1, emptyList()),
            Fixture("cs10b_20260809T191051Z_expected_decode.json", 7_900, 79.0, emptyList()),
            Fixture("cs10b_20260809T194335Z_expected_decode.json", 7_890, 78.9, listOf(4549, 4215, 2190, 2039, 133)),
            Fixture("cs10b_20260809T194641Z_expected_decode.json", 7_890, 78.9, listOf(0, 0, 0, 0, 0)),
        )

        for (fixture in expected) {
            val json = resource("fixtures/${fixture.name}")
            val frameHex = Regex("\"frame_hex\": \"([0-9a-f]+)\"").find(json)!!.groupValues[1]
            val decoded = ScaleProtocol.decode(hex(frameHex)).successfulFrame() as MeasurementFrame

            assertEquals(frameHex, decoded.rawHex)
            assertEquals(fixture.rawWeight, decoded.weightRawU16Be)
            assertEquals(fixture.kg, decoded.weightKg, 0.0)
            assertEquals("int.from_bytes(frame[3:5], 'big') / 100", decoded.weightFormula)
            assertEquals(fixture.resistance, decoded.resistanceRaw)
            assertNull(decoded.resistanceUnit)
            assertTrue(decoded.checksumValid == true)
        }
    }

    @Test
    fun `measurement uses exact BE offsets and preserves an all-zero present block`() {
        val frame = hex("1012201ed201210000000000000000000054")
        val decoded = ScaleProtocol.decode(frame).successfulFrame() as MeasurementFrame

        assertEquals(0x20, decoded.protocolType)
        assertEquals(0x1ed2, decoded.weightRawU16Be)
        assertEquals(0x01, decoded.b5)
        assertEquals(0x21, decoded.b6)
        assertEquals(listOf(0, 0, 0, 0, 0), decoded.resistanceRaw)
        assertTrue(decoded.hasResistance)
        assertTrue(decoded.resistanceLayout!!.contains("bytes 7..16"))

        frame[3] = 0
        val exposed = decoded.raw
        exposed[4] = 0
        assertEquals("1012201ed201210000000000000000000054", decoded.rawHex)
    }

    @Test
    fun `weight-only marker does not invent resistance words`() {
        val decoded = ScaleProtocol.decode(
            hex("1012201ee600110000000000000000000057"),
        ).successfulFrame() as MeasurementFrame

        assertEquals(0x00, decoded.b5)
        assertEquals(0x11, decoded.b6)
        assertTrue(decoded.resistanceRaw.isEmpty())
        assertFalse(decoded.hasResistance)
        assertNull(decoded.resistanceLayout)
    }

    @Test
    fun `known notification and command forms decode to typed raw-preserving frames`() {
        val info = ScaleProtocol.decode(
            hex("121220d1e0460003ff079308400005030027"),
        ).successfulFrame() as InfoFrame
        assertEquals(0x12, info.lengthField)
        assertEquals(0x20, info.protocolType)
        assertArrayEquals(hex("d1e0460003ff0793084000050300"), info.infoPayload)
        assertTrue(info.checksumValid == true)

        val status14 = ScaleProtocol.decode(hex("140b200000010000000040")).successfulFrame() as StatusFrame
        assertEquals(Opcode.STATUS_14.byte, status14.opcode)
        assertEquals(0, status14.statusByte)
        assertArrayEquals(hex("00000100000000"), status14.statusPayload)

        val status21 = ScaleProtocol.decode(hex("2105200147")).successfulFrame() as StatusFrame
        assertEquals(Opcode.STATUS_21.byte, status21.opcode)
        assertEquals(1, status21.statusByte)

        val unit = ScaleProtocol.decode(hex("13092002100000004e")).successfulFrame() as CommandFrame
        assertEquals("unit_config_0x13", unit.commandName)
        assertEquals(0x20, unit.protocolType)
        assertEquals(WeightUnit.LB.byte, unit.unitByte)

        val push = ScaleProtocol.decode(hex("02e4820b32")).successfulFrame() as CommandFrame
        assertEquals(839_615_204L, push.qnEpochSeconds)
        assertNull(push.checksumValid)
        assertArrayEquals(hex("e4820b32"), push.payload)

        val sync = ScaleProtocol.decode(hex("200820e4820b32eb")).successfulFrame() as CommandFrame
        assertEquals(839_615_204L, sync.qnEpochSeconds)
        assertTrue(sync.checksumValid == true)

        val a0 = ScaleProtocol.decode(hex("a00d04fe0000000000000000af")).successfulFrame()
        assertTrue(a0 is CommandFrame)
        assertEquals("a0", (a0 as CommandFrame).name)
    }

    @Test
    fun `bad checksum remains a successful typed decode but is marked invalid`() {
        val bytes = hex("1012201ee600110000000000000000000057")
        bytes[bytes.lastIndex] = 0x56

        val frame = ScaleProtocol.decode(bytes).successfulFrame()

        assertTrue(frame is MeasurementFrame)
        assertEquals(false, frame.checksumValid)
        assertEquals("1012201ee600110000000000000000000056", frame.rawHex)
    }

    @Test
    fun `malformed known frames retain bytes opcode checksum status and reason`() {
        val cases = listOf(
            byteArrayOf() to "cannot decode an empty frame",
            hex("10") to "0x10 measurement frame must be 18 bytes",
            hex("1200") to "0x12 info frame is too short",
            hex("130000") to "0x13 unit config frame is too short",
            hex("140000") to "0x14 status frame is too short",
            hex("02aabb") to "0x02 time_push command must be 5 bytes",
            hex("200000") to "0x20 time sync frame is too short",
            hex("a0") to "0xA0 command frame is too short",
        )

        for ((raw, reasonFragment) in cases) {
            val expected = raw.copyOf()
            val result = ScaleProtocol.decode(raw)
            assertTrue(result is DecodeResult.Malformed)
            result as DecodeResult.Malformed
            assertArrayEquals(expected, result.raw)
            assertTrue(result.reason.contains(reasonFragment))
            raw.fill(0)
            assertArrayEquals(expected, result.raw)
        }

        val shortInfo = ScaleProtocol.decode(hex("1200")) as DecodeResult.Malformed
        assertEquals(Opcode.INFO.byte, shortInfo.opcode)
        assertEquals(false, shortInfo.checksumValid)
    }

    @Test
    fun `unknown opcode is represented and keeps checksum semantics unknown`() {
        val input = hex("7f010203")
        val frame = ScaleProtocol.decode(input).successfulFrame()

        assertTrue(frame is UnknownFrame)
        assertEquals(0x7f, frame.opcode)
        assertEquals("unknown_0x7f", frame.opcodeName)
        assertNull(frame.checksumValid)
        assertArrayEquals(hex("010203"), frame.payload)
    }

    private data class Fixture(
        val name: String,
        val rawWeight: Int,
        val kg: Double,
        val resistance: List<Int>,
    )

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader!!.getResource(path)) { "missing resource $path" }.readText()
}

private fun DecodeResult.successfulFrame(): DecodedFrame {
    assertTrue("expected DecodeResult.Success but was ${this::class.java.simpleName}", this is DecodeResult.Success)
    return (this as DecodeResult.Success).frame
}

private fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
