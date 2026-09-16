package dev.eggmasonvalue.tracky.core.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImmutableBytesTest {
    @Test
    fun `copies input and output arrays`() {
        val input = byteArrayOf(1, 2, 3)
        val immutable = ImmutableBytes.copyOf(input)
        input[0] = 9

        val output = immutable.toByteArray()
        output[1] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), immutable.toByteArray())
    }

    @Test
    fun `uses content equality without printing raw bytes`() {
        assertEquals(ImmutableBytes.copyOf(byteArrayOf(1)), ImmutableBytes.copyOf(byteArrayOf(1)))
        assertEquals("ImmutableBytes(size=1)", ImmutableBytes.copyOf(byteArrayOf(99)).toString())
    }
}
