package dev.eggmasonvalue.tracky.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleSessionTest {
    private val info = hex("121220d1e0460003ff079308400005030027")
    private val status14 = hex("140b200000010000000040")
    private val status21 = hex("2105200147")
    private val measurement = hex("1012201ee600110000000000000000000057")
    private val epoch = 839_615_204L

    @Test
    fun `session replays exact captured handshake writes and states`() {
        val session = ScaleSession(unitByte = WeightUnit.LB.byte)
        assertEquals(SessionState.WAITING_INFO, session.state)
        assertTrue(session.waitingForInfo)
        assertTrue(session.notificationsEnabled)

        val afterInfo = session.feed(info, epoch)
        assertTrue(afterInfo.decodeResult is DecodeResult.Success)
        assertEquals(SessionState.WAITING_14, afterInfo.state)
        assertWrites(
            afterInfo.writes,
            "13092002100000004e",
            "02e4820b32",
        )
        assertEquals(0x20, session.protocolType)
        assertTrue(session.configured)

        val after14 = session.feed(status14, epoch)
        assertEquals(SessionState.WAITING_21, after14.state)
        assertWrites(after14.writes, "200820e4820b32eb")

        val after21 = session.feed(status21, epoch)
        assertEquals(SessionState.LIVE, after21.state)
        assertWrites(
            after21.writes,
            "a00d04fe0000000000000000af",
            "a00d02010008002106b804029d",
        )

        val live = session.feed(measurement, epoch)
        assertEquals(SessionState.LIVE, live.state)
        assertTrue(live.writes.isEmpty())
        assertEquals(1, session.measurements.size)
        assertEquals(4, session.receivedResults.size)
    }

    @Test
    fun `fixed epoch constructor is used by each time transition`() {
        val session = ScaleSession(WeightUnit.KG.byte, epoch)

        assertWrites(
            session.feed(info).writes,
            "13092001100000004d",
            "02e4820b32",
        )
        assertWrites(session.feed(status14).writes, "200820e4820b32eb")
    }

    @Test
    fun `out of order unknown and malformed frames do not advance`() {
        val session = ScaleSession()

        val status = session.feed(status14, epoch)
        assertEquals(SessionState.WAITING_INFO, status.state)
        assertTrue(status.writes.isEmpty())

        val unknown = session.feed(hex("7f0102"), epoch)
        assertEquals(SessionState.WAITING_INFO, unknown.state)
        assertTrue((unknown.decodeResult as DecodeResult.Success).frame is UnknownFrame)

        val malformed = session.feed(hex("12"), epoch)
        assertTrue(malformed.decodeResult is DecodeResult.Malformed)
        assertEquals(SessionState.WAITING_INFO, malformed.state)
        assertTrue(malformed.writes.isEmpty())
        assertEquals(3, session.receivedResults.size)
    }

    @Test
    fun `known bad checksum is emitted but cannot advance or become a measurement`() {
        val session = ScaleSession()
        val badInfo = info.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        val output = session.feed(badInfo, epoch)

        val frame = (output.decodeResult as DecodeResult.Success).frame
        assertEquals(false, frame.checksumValid)
        assertEquals(SessionState.WAITING_INFO, session.state)
        assertTrue(output.writes.isEmpty())

        val badMeasurement = measurement.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        session.feed(badMeasurement, epoch)
        assertTrue(session.measurements.isEmpty())
    }

    @Test
    fun `repeated or wrong status transitions do not duplicate writes`() {
        val session = ScaleSession(qnEpochSeconds = epoch)

        session.feed(info)
        assertTrue(session.feed(info).writes.isEmpty())
        assertTrue(session.feed(status21).writes.isEmpty())
        assertWrites(session.feed(status14).writes, "200820e4820b32eb")
        assertTrue(session.feed(status14).writes.isEmpty())
        assertEquals(SessionState.WAITING_21, session.state)
    }

    @Test
    fun `session output write arrays are defensive snapshots`() {
        val session = ScaleSession(qnEpochSeconds = epoch)
        val output = session.feed(info)
        val firstRead = output.writes
        firstRead[0][0] = 0
        firstRead[1].fill(0)

        assertWrites(
            output.writes,
            "13092001100000004d",
            "02e4820b32",
        )
        assertWrites(
            output.txWrites,
            "13092001100000004d",
            "02e4820b32",
        )
    }

    @Test
    fun `reset restores a fresh handshake while retaining options`() {
        val session = ScaleSession(unitByte = WeightUnit.LB.byte, qnEpochSeconds = epoch)
        session.feed(info)
        session.feed(status14)
        session.feed(status21)
        session.feed(measurement)

        session.reset()

        assertEquals(SessionState.WAITING_INFO, session.state)
        assertEquals(null, session.protocolType)
        assertFalse(session.configured)
        assertTrue(session.receivedResults.isEmpty())
        assertTrue(session.measurements.isEmpty())
        assertWrites(
            session.feed(info).writes,
            "13092002100000004e",
            "02e4820b32",
        )
    }

    private fun assertWrites(actual: List<ByteArray>, vararg expectedHex: String) {
        assertEquals(expectedHex.size, actual.size)
        expectedHex.forEachIndexed { index, expected ->
            assertArrayEquals(expected, hex(expected), actual[index])
        }
    }
}

private fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
