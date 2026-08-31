package dev.eggmasonvalue.tracky.core.ble

import dev.eggmasonvalue.tracky.core.protocol.DecodeResult
import java.time.Instant

/** Public states intentionally contain no Bluetooth address or [android.bluetooth.BluetoothDevice]. */
sealed interface BleConnectionState {
    val attemptId: Long?

    data object Idle : BleConnectionState {
        override val attemptId: Long? = null
    }

    data class Scanning(override val attemptId: Long) : BleConnectionState

    data class DeviceFound(
        override val attemptId: Long,
        val advertisedName: String?,
        val rssi: Int,
        val advertisedServices: List<String>,
    ) : BleConnectionState

    data class Connecting(override val attemptId: Long) : BleConnectionState
    data class DiscoveringServices(override val attemptId: Long) : BleConnectionState
    data class EnablingNotifications(override val attemptId: Long) : BleConnectionState
    data class Handshaking(override val attemptId: Long) : BleConnectionState
    data class Measuring(override val attemptId: Long) : BleConnectionState
    data class Stopping(override val attemptId: Long) : BleConnectionState

    data class Disconnected(
        override val attemptId: Long,
        val reason: DisconnectReason,
    ) : BleConnectionState

    data class BluetoothOff(override val attemptId: Long) : BleConnectionState

    data class PermissionDenied(
        override val attemptId: Long,
        val missing: Set<BlePermission>,
    ) : BleConnectionState

    data class Failure(
        override val attemptId: Long,
        val stage: BleStage,
        val reason: BleFailureReason,
        /** Optional Android/GATT status. It never contains device-identifying information. */
        val status: Int? = null,
    ) : BleConnectionState
}

enum class BlePermission { Scan, Connect }
enum class DisconnectReason { Requested, Remote, ForegroundLost }
enum class BleStage { Foreground, Scan, Connect, ServiceDiscovery, NotificationSetup, Handshake, Measurement, Stop }
enum class BleFailureReason {
    ScanFailed,
    ScanTimedOut,
    ConnectRejected,
    ConnectTimedOut,
    Disconnected,
    ServiceDiscoveryRejected,
    ServiceDiscoveryTimedOut,
    RequiredServiceMissing,
    RequiredCharacteristicMissing,
    RequiredCharacteristicPropertyMissing,
    NotificationSetupRejected,
    NotificationSetupTimedOut,
    WriteRejected,
    WriteTimedOut,
    ProtocolError,
    EvidenceSinkFailed,
    Closed,
    Unexpected,
}

enum class WireDirection { Rx, Tx }

enum class ScaleCharacteristic(val uuid: String) {
    Notify("0000fff1-0000-1000-8000-00805f9b34fb"),
    Write("0000fff2-0000-1000-8000-00805f9b34fb"),
}

/** A defensively copied byte sequence; unlike ByteArray it is safe to expose from immutable state. */
class ImmutableBytes private constructor(private val value: ByteArray) {
    val size: Int get() = value.size
    operator fun get(index: Int): Byte = value[index]
    fun toByteArray(): ByteArray = value.copyOf()
    fun toList(): List<Byte> = value.asList()

    override fun equals(other: Any?): Boolean = other is ImmutableBytes && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "ImmutableBytes(size=$size)"

    companion object {
        fun copyOf(value: ByteArray): ImmutableBytes = ImmutableBytes(value.copyOf())
    }
}

sealed interface WireDecode {
    data class Decoded(val result: DecodeResult) : WireDecode
    data object DecoderThrew : WireDecode
}

/**
 * One chronological wire crossing. RX is delivered to the authoritative sink before protocol code
 * runs, then its bounded diagnostic projection is annotated with [decode]. TX is delivered only
 * after Android reports a successful characteristic write.
 */
data class WireEvent(
    val attemptId: Long,
    val sequence: Long,
    val at: Instant,
    val direction: WireDirection,
    val characteristic: ScaleCharacteristic,
    val raw: ImmutableBytes,
    val decode: WireDecode? = null,
)

data class MeasurementEvent(
    val attemptId: Long,
    val wireSequence: Long,
    val at: Instant,
    val frame: dev.eggmasonvalue.tracky.core.protocol.MeasurementFrame,
)

fun interface WireEventSink {
    /** Must return only after the event is durably accepted; throwing ends the attempt. */
    suspend fun record(event: WireEvent)
}

fun interface MeasurementSink {
    suspend fun accept(event: MeasurementEvent)
}

fun interface BleForegroundGate {
    /** Must become false whenever the owning screen/app is no longer foreground-visible. */
    fun isForeground(): Boolean
}

interface BlePermissionGate {
    fun canScan(): Boolean
    fun canConnect(): Boolean
}

data class BleTimeouts(
    val scanMillis: Long = 15_000,
    val connectMillis: Long = 12_000,
    val serviceDiscoveryMillis: Long = 10_000,
    val descriptorWriteMillis: Long = 8_000,
    val characteristicWriteMillis: Long = 8_000,
    val foregroundPollMillis: Long = 250,
)
