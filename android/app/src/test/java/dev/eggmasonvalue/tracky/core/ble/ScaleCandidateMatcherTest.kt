package dev.eggmasonvalue.tracky.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleCandidateMatcherTest {
    @Test
    fun `name prefix matches without service data`() {
        assertTrue(ScaleCandidateMatcher.matches(BleAdvertisement("QN-Scale-123", emptyList())))
        assertTrue(ScaleCandidateMatcher.matches(BleAdvertisement("qn-scale kitchen", emptyList())))
    }

    @Test
    fun `ffe0 or fff0 service matches without a name`() {
        assertTrue(ScaleCandidateMatcher.matches(BleAdvertisement(null, listOf("FFE0"))))
        assertTrue(
            ScaleCandidateMatcher.matches(
                BleAdvertisement(null, listOf("0000fff0-0000-1000-8000-00805F9B34FB")),
            ),
        )
    }

    @Test
    fun `name and service conditions are OR not AND`() {
        assertTrue(ScaleCandidateMatcher.matches(BleAdvertisement("Other", listOf("0xffe0"))))
        assertTrue(ScaleCandidateMatcher.matches(BleAdvertisement("QN-Scale", listOf("180f"))))
    }

    @Test
    fun `unrelated and malformed advertisements do not match`() {
        assertFalse(ScaleCandidateMatcher.matches(BleAdvertisement(null, emptyList())))
        assertFalse(ScaleCandidateMatcher.matches(BleAdvertisement("QN Scale", listOf("fff1"))))
        assertFalse(ScaleCandidateMatcher.matches(BleAdvertisement("Other", listOf("not-a-uuid"))))
        assertFalse(
            ScaleCandidateMatcher.matches(
                BleAdvertisement("Other", listOf("1234ffe0-0000-1000-8000-00805f9b34fb")),
            ),
        )
    }

    @Test
    fun `safe name strips controls trims and bounds advertisement text`() {
        assertEquals("QN-ScaleX", ScaleCandidateMatcher.safeName(" \u0000QN-Scale\nX "))
        assertEquals(64, ScaleCandidateMatcher.safeName("x".repeat(100))?.length)
        assertNull(ScaleCandidateMatcher.safeName(" \n\u0000 "))
    }
}
