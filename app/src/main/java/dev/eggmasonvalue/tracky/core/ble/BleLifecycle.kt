package dev.eggmasonvalue.tracky.core.ble

/** Android-free lifecycle policy. Terminal/error transitions are accepted from any active state. */
object BleLifecycle {
    fun canTransition(from: BleConnectionState, to: BleConnectionState): Boolean {
        if (to is BleConnectionState.Failure ||
            to is BleConnectionState.PermissionDenied ||
            to is BleConnectionState.BluetoothOff
        ) return from !is BleConnectionState.Idle

        if (to is BleConnectionState.Stopping) return from.attemptId == to.attemptId
        if (to is BleConnectionState.Disconnected) return from.attemptId == to.attemptId

        if (to is BleConnectionState.Scanning) {
            return from === BleConnectionState.Idle ||
                from is BleConnectionState.Disconnected ||
                from is BleConnectionState.BluetoothOff ||
                from is BleConnectionState.PermissionDenied ||
                from is BleConnectionState.Failure
        }

        return when (from) {
            BleConnectionState.Idle -> false
            is BleConnectionState.Scanning -> to is BleConnectionState.DeviceFound
            is BleConnectionState.DeviceFound -> to is BleConnectionState.Connecting
            is BleConnectionState.Connecting -> to is BleConnectionState.DiscoveringServices
            is BleConnectionState.DiscoveringServices -> to is BleConnectionState.EnablingNotifications
            is BleConnectionState.EnablingNotifications -> to is BleConnectionState.Handshaking
            is BleConnectionState.Handshaking ->
                to is BleConnectionState.Handshaking || to is BleConnectionState.Measuring
            is BleConnectionState.Measuring -> to is BleConnectionState.Measuring
            is BleConnectionState.Stopping,
            is BleConnectionState.Disconnected,
            is BleConnectionState.BluetoothOff,
            is BleConnectionState.PermissionDenied,
            is BleConnectionState.Failure,
            -> false
        }
    }
}
