package dev.eggmasonvalue.tracky.core.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLifecycleTest {
    private val id = 7L

    @Test
    fun `happy path lifecycle is accepted`() {
        val states = listOf(
            BleConnectionState.Idle,
            BleConnectionState.Scanning(id),
            BleConnectionState.DeviceFound(id, "QN-Scale", -45, listOf("fff0")),
            BleConnectionState.Connecting(id),
            BleConnectionState.DiscoveringServices(id),
            BleConnectionState.EnablingNotifications(id),
            BleConnectionState.Handshaking(id),
            BleConnectionState.Measuring(id),
            BleConnectionState.Stopping(id),
            BleConnectionState.Disconnected(id, DisconnectReason.Requested),
        )

        states.zipWithNext().forEach { (from, to) ->
            assertTrue("$from -> $to", BleLifecycle.canTransition(from, to))
        }
    }

    @Test
    fun `out of order transition is rejected`() {
        assertFalse(
            BleLifecycle.canTransition(
                BleConnectionState.Scanning(id),
                BleConnectionState.Measuring(id),
            ),
        )
        assertFalse(
            BleLifecycle.canTransition(
                BleConnectionState.Connecting(id),
                BleConnectionState.EnablingNotifications(id),
            ),
        )
    }

    @Test
    fun `stop and disconnect reject another generation`() {
        assertFalse(
            BleLifecycle.canTransition(
                BleConnectionState.Measuring(id),
                BleConnectionState.Stopping(id + 1),
            ),
        )
        assertFalse(
            BleLifecycle.canTransition(
                BleConnectionState.Measuring(id),
                BleConnectionState.Disconnected(id + 1, DisconnectReason.Remote),
            ),
        )
    }

    @Test
    fun `terminal state can start a fresh attempt`() {
        assertTrue(
            BleLifecycle.canTransition(
                BleConnectionState.Disconnected(id, DisconnectReason.Requested),
                BleConnectionState.Scanning(id + 1),
            ),
        )
        assertTrue(
            BleLifecycle.canTransition(
                BleConnectionState.Failure(id, BleStage.Scan, BleFailureReason.ScanFailed),
                BleConnectionState.Scanning(id + 1),
            ),
        )
    }

    @Test
    fun `failure is accepted from active state but not idle`() {
        val failure = BleConnectionState.Failure(id, BleStage.Connect, BleFailureReason.ConnectTimedOut)
        assertTrue(BleLifecycle.canTransition(BleConnectionState.Connecting(id), failure))
        assertFalse(BleLifecycle.canTransition(BleConnectionState.Idle, failure))
    }
}
