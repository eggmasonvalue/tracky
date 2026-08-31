package dev.eggmasonvalue.tracky.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import dev.eggmasonvalue.tracky.core.protocol.DecodeResult
import dev.eggmasonvalue.tracky.core.protocol.MeasurementFrame
import dev.eggmasonvalue.tracky.core.protocol.ScaleProtocol
import dev.eggmasonvalue.tracky.core.protocol.ScaleSession
import dev.eggmasonvalue.tracky.core.protocol.SessionState
import dev.eggmasonvalue.tracky.core.protocol.WeightUnit
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

class AndroidBlePermissionGate(private val context: Context) : BlePermissionGate {
    override fun canScan(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    override fun canConnect(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

/**
 * Explicitly started, foreground-only BLE acquisition. It does not auto-reconnect or own a service.
 * The owner must supply a foreground gate backed by its process/screen lifecycle.
 */
class AndroidBleAcquisitionClient(
    context: Context,
    private val permissionGate: BlePermissionGate,
    private val foregroundGate: BleForegroundGate,
    private val unit: WeightUnit,
    private val timeouts: BleTimeouts = BleTimeouts(),
    private val clock: Clock = Clock.systemUTC(),
    private val wireEventSink: WireEventSink = WireEventSink { },
    private val measurementSink: MeasurementSink = MeasurementSink { },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val attemptCounter = AtomicLong(0)
    private val currentAttempt = AtomicLong(NO_ATTEMPT)
    private val activeGatt = AtomicReference<GattResources?>()
    private var activeJob: Job? = null
    private var closed = false

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _evidence = MutableStateFlow<List<WireEvent>>(emptyList())
    val evidence: StateFlow<List<WireEvent>> = _evidence.asStateFlow()

    private val _measurements = MutableSharedFlow<MeasurementEvent>(extraBufferCapacity = 16)
    val measurements: SharedFlow<MeasurementEvent> = _measurements.asSharedFlow()

    /** Starts a fresh generation and returns its identity before callbacks can be consumed. */
    fun start(): Long {
        val id: Long
        val previous: Job?
        synchronized(lock) {
            if (closed) return NO_ATTEMPT
            id = attemptCounter.incrementAndGet()
            previous = activeJob
            currentAttempt.set(id)
            _evidence.value = emptyList()
            _state.value = BleConnectionState.Scanning(id)
            activeJob = scope.launch {
                previous?.cancelAndJoin()
                if (!isCurrent(id)) return@launch
                runAttempt(id)
            }
        }
        return id
    }

    /** Stops accepting callbacks, drains accepted notifications, then closes the GATT boundary. */
    fun stop(reason: DisconnectReason = DisconnectReason.Requested) {
        val id: Long
        val job: Job?
        synchronized(lock) {
            id = currentAttempt.get()
            if (id == NO_ATTEMPT) return
            job = activeJob
            _state.value = BleConnectionState.Stopping(id)
            val resources = activeGatt.get()?.takeIf { it.attemptId == id }
            val canDrain = _state.value.let { it is BleConnectionState.Stopping } &&
                resources != null && resources.notificationsActive
            if (canDrain) {
                resources.requestStop(reason)
                return
            }
            currentAttempt.compareAndSet(id, NO_ATTEMPT)
        }
        closeActiveGatt(id)
        job?.cancel()
        scope.launch {
            job?.join()
            synchronized(lock) {
                if (!closed && currentAttempt.get() == NO_ATTEMPT) {
                    _state.value = BleConnectionState.Disconnected(id, reason)
                    activeJob = null
                }
            }
        }
    }

    override fun close() {
        val id: Long
        val job: Job?
        synchronized(lock) {
            if (closed) return
            closed = true
            id = currentAttempt.getAndSet(NO_ATTEMPT)
            job = activeJob
            if (id != NO_ATTEMPT) _state.value = BleConnectionState.Stopping(id)
        }
        if (id != NO_ATTEMPT) closeActiveGatt(id)
        job?.cancel()
        scope.cancel()
        if (id != NO_ATTEMPT) {
            _state.value = BleConnectionState.Disconnected(id, DisconnectReason.Requested)
        }
    }

    private suspend fun runAttempt(id: Long) {
        try {
            coroutineScope {
                val foregroundWatchdog = launch {
                    while (isActive) {
                        delay(timeouts.foregroundPollMillis)
                        if (!foregroundGate.isForeground()) throw ForegroundLostException()
                    }
                }
                try {
                    acquire(id)
                } finally {
                    foregroundWatchdog.cancel()
                }
            }
        } catch (_: CancellationException) {
            // stop(), close(), and a superseding start own the final state.
        } catch (stop: RequestedStopException) {
            if (isCurrent(id)) {
                currentAttempt.compareAndSet(id, NO_ATTEMPT)
                publishUnconditionallyIfAttempt(id, BleConnectionState.Disconnected(id, stop.reason))
            }
        } catch (_: ForegroundLostException) {
            if (isCurrent(id)) {
                publish(id, BleConnectionState.Stopping(id))
                currentAttempt.compareAndSet(id, NO_ATTEMPT)
                publishUnconditionallyIfAttempt(id, BleConnectionState.Disconnected(id, DisconnectReason.ForegroundLost))
            }
        } catch (error: BleTransportException) {
            if (isCurrent(id)) {
                if (error.reason == BleFailureReason.Disconnected) {
                    currentAttempt.compareAndSet(id, NO_ATTEMPT)
                    publishUnconditionallyIfAttempt(id, BleConnectionState.Disconnected(id, DisconnectReason.Remote))
                } else {
                    publish(id, BleConnectionState.Failure(id, error.stage, error.reason, error.status))
                    currentAttempt.compareAndSet(id, NO_ATTEMPT)
                }
            }
        } catch (_: SecurityException) {
            if (isCurrent(id)) {
                publish(id, BleConnectionState.PermissionDenied(id, missingPermissions()))
                currentAttempt.compareAndSet(id, NO_ATTEMPT)
            }
        } catch (_: Throwable) {
            if (isCurrent(id)) {
                publish(id, BleConnectionState.Failure(id, stageOf(_state.value), BleFailureReason.Unexpected))
                currentAttempt.compareAndSet(id, NO_ATTEMPT)
            }
        } finally {
            closeActiveGatt(id)
        }
    }

    private suspend fun acquire(id: Long) {
        if (!foregroundGate.isForeground()) throw ForegroundLostException()
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            publish(id, BleConnectionState.PermissionDenied(id, missing))
            currentAttempt.compareAndSet(id, NO_ATTEMPT)
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            publish(id, BleConnectionState.BluetoothOff(id))
            currentAttempt.compareAndSet(id, NO_ATTEMPT)
            return
        }

        val found = scan(id, adapter)
        publish(
            id,
            BleConnectionState.DeviceFound(
                id,
                ScaleCandidateMatcher.safeName(found.advertisedName),
                found.rssi,
                found.services.map(UUID::toString).sorted(),
            ),
        )
        publish(id, BleConnectionState.Connecting(id))
        useGatt(id, found.device)
    }

    @SuppressLint("MissingPermission") // Every call site is preceded by the injected scan permission gate.
    private suspend fun scan(id: Long, adapter: BluetoothAdapter): FoundDevice {
        val scanner = adapter.bluetoothLeScanner
            ?: throw BleTransportException(BleStage.Scan, BleFailureReason.ScanFailed)
        val result = CompletableDeferred<FoundDevice>()
        val failure = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scanResult: ScanResult) {
                if (!isCurrent(id)) return
                val record = scanResult.scanRecord ?: return
                val services = record.serviceUuids.orEmpty().map(ParcelUuid::getUuid)
                val name = record.deviceName
                if (ScaleCandidateMatcher.matches(BleAdvertisement(name, services.map(UUID::toString)))) {
                    result.complete(FoundDevice(scanResult.device, name, scanResult.rssi, services))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                if (isCurrent(id)) failure.complete(errorCode)
            }
        }

        scanner.startScan(callback)
        try {
            return try {
                withTimeout(timeouts.scanMillis) {
                    select {
                        result.onAwait { it }
                        failure.onAwait { code ->
                            throw BleTransportException(BleStage.Scan, BleFailureReason.ScanFailed, code)
                        }
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                throw BleTransportException(BleStage.Scan, BleFailureReason.ScanTimedOut)
            }
        } finally {
            scanner.stopScan(callback)
        }
    }

    @SuppressLint("MissingPermission") // The injected connect gate is checked before acquire/connect.
    private suspend fun useGatt(id: Long, device: BluetoothDevice) {
        val events = GattEvents()
        val callback = callbackFor(id, events)
        val settings = BluetoothGattConnectionSettings.Builder()
            .setAutoConnectEnabled(false)
            .setAutomaticMtuEnabled(true)
            .setTransport(BluetoothDevice.TRANSPORT_LE)
            .build()
        val gatt = device.connectGatt(settings, appContext.mainExecutor, callback)
            ?: throw BleTransportException(BleStage.Connect, BleFailureReason.ConnectRejected)
        val resources = GattResources(id, gatt, events)
        activeGatt.set(resources)

        try {
            awaitStatus(events.connected, events.disconnected, timeouts.connectMillis, BleStage.Connect,
                BleFailureReason.ConnectTimedOut).requireSuccess(BleStage.Connect, BleFailureReason.ConnectRejected)

            publish(id, BleConnectionState.DiscoveringServices(id))
            if (!gatt.discoverServices()) {
                throw BleTransportException(BleStage.ServiceDiscovery, BleFailureReason.ServiceDiscoveryRejected)
            }
            awaitStatus(events.services, events.disconnected, timeouts.serviceDiscoveryMillis,
                BleStage.ServiceDiscovery, BleFailureReason.ServiceDiscoveryTimedOut)
                .requireSuccess(BleStage.ServiceDiscovery, BleFailureReason.ServiceDiscoveryRejected)

            val service = gatt.getService(SERVICE_UUID)
                ?: throw BleTransportException(BleStage.ServiceDiscovery, BleFailureReason.RequiredServiceMissing)
            val notify = service.getCharacteristic(NOTIFY_UUID)
                ?: throw BleTransportException(BleStage.ServiceDiscovery, BleFailureReason.RequiredCharacteristicMissing)
            val write = service.getCharacteristic(WRITE_UUID)
                ?: throw BleTransportException(BleStage.ServiceDiscovery, BleFailureReason.RequiredCharacteristicMissing)
            validateProperties(notify, write)

            publish(id, BleConnectionState.EnablingNotifications(id))
            val cccd = notify.getDescriptor(CCCD_UUID)
                ?: throw BleTransportException(BleStage.NotificationSetup, BleFailureReason.RequiredCharacteristicMissing)
            if (!gatt.setCharacteristicNotification(notify, true)) {
                throw BleTransportException(BleStage.NotificationSetup, BleFailureReason.NotificationSetupRejected)
            }
            val cccdValue = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (gatt.writeDescriptor(cccd, cccdValue) != BluetoothStatusCodes.SUCCESS) {
                throw BleTransportException(BleStage.NotificationSetup, BleFailureReason.NotificationSetupRejected)
            }
            awaitStatus(events.descriptorWrites, events.disconnected, timeouts.descriptorWriteMillis,
                BleStage.NotificationSetup, BleFailureReason.NotificationSetupTimedOut)
                .requireSuccess(BleStage.NotificationSetup, BleFailureReason.NotificationSetupRejected)

            publish(id, BleConnectionState.Handshaking(id))
            resources.notificationsActive = true
            measure(id, gatt, write, events)
        } finally {
            resources.close()
            activeGatt.compareAndSet(resources, null)
        }
    }

    private suspend fun measure(
        id: Long,
        gatt: BluetoothGatt,
        write: BluetoothGattCharacteristic,
        events: GattEvents,
    ) {
        val session = ScaleSession(unit.byte)
        var sequence = -1L
        while (isCurrent(id)) {
            val queued = events.notifications.tryReceive().getOrNull()
            val requestedStop = events.requestedStopReason()
            val packet = queued ?: if (requestedStop != null) {
                throw RequestedStopException(requestedStop)
            } else {
                select {
                    events.notifications.onReceive { it }
                    events.stopRequested.onAwait { reason ->
                        events.notifications.tryReceive().getOrNull()
                            ?: throw RequestedStopException(reason)
                    }
                    events.disconnected.onAwait {
                        throw BleTransportException(BleStage.Measurement, BleFailureReason.Disconnected, it)
                    }
                }
            }
            val raw = ImmutableBytes.copyOf(packet)
            val rxSequence = ++sequence
            val observedAt = clock.instant()
            recordWireEvent(
                WireEvent(id, rxSequence, observedAt, WireDirection.Rx, ScaleCharacteristic.Notify, raw),
            )

            val decoded = try {
                ScaleProtocol.decode(packet.copyOf())
            } catch (_: Throwable) {
                annotateEvidence(id, rxSequence, WireDecode.DecoderThrew)
                continue
            }
            annotateEvidence(id, rxSequence, WireDecode.Decoded(decoded))
            if (events.stopRequested.isCompleted || events.disconnected.isCompleted) continue

            val output = try {
                val qnEpochSeconds = ScaleProtocol.qnEpochFromUnix(clock.instant().epochSecond)
                session.feed(packet.copyOf(), qnEpochSeconds)
            } catch (_: Throwable) {
                throw BleTransportException(BleStage.Measurement, BleFailureReason.ProtocolError)
            }
            for (bytes in output.writes) {
                writeAndAwait(gatt, write, bytes, events)
                recordWireEvent(
                    WireEvent(
                        id,
                        ++sequence,
                        clock.instant(),
                        WireDirection.Tx,
                        ScaleCharacteristic.Write,
                        ImmutableBytes.copyOf(bytes),
                    ),
                )
            }
            val frame = (output.decodeResult as? DecodeResult.Success)?.frame
            if (frame is MeasurementFrame && frame.checksumValid == true && session.state == SessionState.LIVE) {
                val measurement = MeasurementEvent(id, rxSequence, observedAt, frame)
                measurementSink.accept(measurement)
                _measurements.emit(measurement)
            }
            if (session.state == SessionState.LIVE) {
                publish(id, BleConnectionState.Measuring(id))
            }
        }
    }

    @SuppressLint("MissingPermission") // Connection permission remains the permission gate's responsibility.
    private suspend fun writeAndAwait(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
        events: GattEvents,
    ) {
        val accepted = gatt.writeCharacteristic(
            characteristic,
            bytes.copyOf(),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        if (accepted != BluetoothStatusCodes.SUCCESS) {
            throw BleTransportException(BleStage.Measurement, BleFailureReason.WriteRejected, accepted)
        }
        awaitStatus(events.characteristicWrites, events.disconnected, timeouts.characteristicWriteMillis,
            BleStage.Measurement, BleFailureReason.WriteTimedOut)
            .requireSuccess(BleStage.Measurement, BleFailureReason.WriteRejected)
    }

    private fun callbackFor(id: Long, events: GattEvents) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrent(id)) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> events.connected.trySend(status)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    events.stopAcceptingNotifications()
                    events.disconnected.complete(status)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (isCurrent(id)) events.services.trySend(status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (isCurrent(id) && descriptor.uuid == CCCD_UUID) events.descriptorWrites.trySend(status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (isCurrent(id) && characteristic.uuid == WRITE_UUID) events.characteristicWrites.trySend(status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (isCurrent(id) && characteristic.uuid == NOTIFY_UUID) {
                events.offerNotification(value)
            }
        }
    }

    private suspend fun awaitStatus(
        statuses: Channel<Int>,
        disconnected: CompletableDeferred<Int>,
        timeoutMillis: Long,
        stage: BleStage,
        timeoutReason: BleFailureReason,
    ): Int = try {
        withTimeout(timeoutMillis) {
            select {
                statuses.onReceive { it }
                disconnected.onAwait { status ->
                    throw BleTransportException(stage, BleFailureReason.Disconnected, status)
                }
            }
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        throw BleTransportException(stage, timeoutReason)
    }

    private fun Int.requireSuccess(stage: BleStage, reason: BleFailureReason) {
        if (this != BluetoothGatt.GATT_SUCCESS) throw BleTransportException(stage, reason, this)
    }

    private fun validateProperties(
        notify: BluetoothGattCharacteristic,
        write: BluetoothGattCharacteristic,
    ) {
        val canNotify = notify.properties and
            (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        val canWrite = write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        if (!canNotify || !canWrite) {
            throw BleTransportException(
                BleStage.ServiceDiscovery,
                BleFailureReason.RequiredCharacteristicPropertyMissing,
            )
        }
    }

    private fun missingPermissions(): Set<BlePermission> = buildSet {
        if (!permissionGate.canScan()) add(BlePermission.Scan)
        if (!permissionGate.canConnect()) add(BlePermission.Connect)
    }

    private fun publish(id: Long, next: BleConnectionState) {
        if (!isCurrent(id)) return
        val prior = _state.value
        if (BleLifecycle.canTransition(prior, next)) _state.value = next
    }

    private fun publishUnconditionallyIfAttempt(id: Long, next: BleConnectionState) {
        if (_state.value.attemptId == id) _state.value = next
    }

    private suspend fun recordWireEvent(event: WireEvent) {
        try {
            wireEventSink.record(event)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw BleTransportException(BleStage.Measurement, BleFailureReason.EvidenceSinkFailed)
        }
        _evidence.update { old -> (old + event).takeLast(MAX_EVIDENCE_EVENTS) }
    }

    private fun annotateEvidence(attemptId: Long, sequence: Long, decode: WireDecode) {
        _evidence.update { events ->
            events.map { event ->
                if (event.attemptId == attemptId && event.sequence == sequence) {
                    event.copy(decode = decode)
                } else {
                    event
                }
            }
        }
    }

    private fun isCurrent(id: Long): Boolean = currentAttempt.get() == id

    private fun closeActiveGatt(id: Long) {
        activeGatt.get()?.takeIf { it.attemptId == id }?.close()
    }

    private fun stageOf(state: BleConnectionState): BleStage = when (state) {
        is BleConnectionState.Scanning, is BleConnectionState.DeviceFound -> BleStage.Scan
        is BleConnectionState.Connecting -> BleStage.Connect
        is BleConnectionState.DiscoveringServices -> BleStage.ServiceDiscovery
        is BleConnectionState.EnablingNotifications -> BleStage.NotificationSetup
        is BleConnectionState.Handshaking -> BleStage.Handshake
        is BleConnectionState.Measuring -> BleStage.Measurement
        is BleConnectionState.Stopping -> BleStage.Stop
        else -> BleStage.Connect
    }

    private class GattEvents {
        val connected = Channel<Int>(Channel.UNLIMITED)
        val services = Channel<Int>(Channel.UNLIMITED)
        val descriptorWrites = Channel<Int>(Channel.UNLIMITED)
        val characteristicWrites = Channel<Int>(Channel.UNLIMITED)
        // The callback cannot block. Preserve every accepted packet for this one known scale;
        // graceful stop closes the producer boundary and drains this queue before finalization.
        val notifications = Channel<ByteArray>(Channel.UNLIMITED)
        val disconnected = CompletableDeferred<Int>()
        val stopRequested = CompletableDeferred<DisconnectReason>()
        private val notificationBoundaryLock = Any()
        private var acceptingNotifications = true

        fun offerNotification(value: ByteArray) = synchronized(notificationBoundaryLock) {
            if (acceptingNotifications) notifications.trySend(value.copyOf())
        }

        fun requestStop(reason: DisconnectReason) = synchronized(notificationBoundaryLock) {
            acceptingNotifications = false
            stopRequested.complete(reason)
        }

        fun stopAcceptingNotifications() = synchronized(notificationBoundaryLock) {
            acceptingNotifications = false
        }

        suspend fun requestedStopReason(): DisconnectReason? =
            if (stopRequested.isCompleted) stopRequested.await() else null
    }

    private class GattResources(
        val attemptId: Long,
        private val gatt: BluetoothGatt,
        private val events: GattEvents,
    ) {
        private val closed = AtomicBoolean(false)
        @Volatile var notificationsActive: Boolean = false

        fun requestStop(reason: DisconnectReason) {
            events.requestStop(reason)
        }

        @SuppressLint("MissingPermission") // Creation is gated; this is deterministic best-effort cleanup.
        fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    gatt.disconnect()
                } catch (_: SecurityException) {
                    // Permission can be revoked mid-attempt; close itself still releases native resources.
                } finally {
                    gatt.close()
                }
            }
        }
    }

    private data class FoundDevice(
        val device: BluetoothDevice,
        val advertisedName: String?,
        val rssi: Int,
        val services: List<UUID>,
    )

    private class BleTransportException(
        val stage: BleStage,
        val reason: BleFailureReason,
        val status: Int? = null,
    ) : Exception()

    private class ForegroundLostException : Exception()

    private class RequestedStopException(val reason: DisconnectReason) : Exception()

    private companion object {
        const val NO_ATTEMPT = 0L
        const val MAX_EVIDENCE_EVENTS = 512
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
