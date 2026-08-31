package dev.eggmasonvalue.tracky

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.eggmasonvalue.tracky.core.ble.AndroidBleAcquisitionClient
import dev.eggmasonvalue.tracky.core.ble.AndroidBlePermissionGate
import dev.eggmasonvalue.tracky.core.ble.BleConnectionState
import dev.eggmasonvalue.tracky.core.ble.BleFailureReason
import dev.eggmasonvalue.tracky.core.ble.BlePermission
import dev.eggmasonvalue.tracky.core.ble.DisconnectReason
import dev.eggmasonvalue.tracky.core.ble.MeasurementEvent
import dev.eggmasonvalue.tracky.core.ble.MeasurementSink
import dev.eggmasonvalue.tracky.core.ble.WireDecode
import dev.eggmasonvalue.tracky.core.ble.WireEventSink
import dev.eggmasonvalue.tracky.core.ble.WireDirection
import dev.eggmasonvalue.tracky.core.ble.WireEvent
import dev.eggmasonvalue.tracky.core.data.DisplayUnit
import dev.eggmasonvalue.tracky.core.data.FrameDirection
import dev.eggmasonvalue.tracky.core.data.MeasurementInterpretation
import dev.eggmasonvalue.tracky.core.data.NewRawFrame
import dev.eggmasonvalue.tracky.core.data.RawFrameEvidence
import dev.eggmasonvalue.tracky.core.data.SessionHistoryRepository
import dev.eggmasonvalue.tracky.core.data.SessionStatus
import dev.eggmasonvalue.tracky.core.data.SessionWithFrames
import dev.eggmasonvalue.tracky.core.protocol.DecodeResult
import dev.eggmasonvalue.tracky.core.protocol.MeasurementFrame
import dev.eggmasonvalue.tracky.core.protocol.ScaleProtocol
import dev.eggmasonvalue.tracky.core.protocol.UnknownFrame
import dev.eggmasonvalue.tracky.core.protocol.WeightUnit
import dev.eggmasonvalue.tracky.ui.model.EvidenceDetailUi
import dev.eggmasonvalue.tracky.ui.model.RecentSessionUi
import dev.eggmasonvalue.tracky.ui.model.SessionDetailUi
import dev.eggmasonvalue.tracky.ui.model.TrackyStage
import dev.eggmasonvalue.tracky.ui.model.TrackyUiState
import dev.eggmasonvalue.tracky.ui.model.UiAction
import dev.eggmasonvalue.tracky.ui.model.UiDestination
import dev.eggmasonvalue.tracky.ui.model.UnitDisplay
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The application integration boundary. UI state only flows out, while all permission, BLE,
 * evidence, persistence, and navigation commands enter through [onAction].
 */
class MainViewModel(
    context: Context,
    private val repository: SessionHistoryRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val foreground = AtomicBoolean(false)
    private val permissionGate = AndroidBlePermissionGate(context.applicationContext)
    private val sessionMutex = Mutex()
    private val client by lazy(LazyThreadSafetyMode.NONE) {
        AndroidBleAcquisitionClient(
            context = context.applicationContext,
            permissionGate = permissionGate,
            foregroundGate = { foreground.get() },
            // The wire setting is deliberately fixed. KG/LB in UI state is presentation only.
            unit = WeightUnit.KG,
            clock = clock,
            wireEventSink = WireEventSink { event ->
                sessionMutex.withLock { persistWireEvent(event) }
            },
            measurementSink = MeasurementSink { event ->
                sessionMutex.withLock { consumeMeasurement(event) }
            },
        )
    }

    private val _uiState = MutableStateFlow(TrackyUiState())
    val uiState: StateFlow<TrackyUiState> = _uiState.asStateFlow()

    private val _history = MutableStateFlow<List<RecentSessionUi>>(emptyList())
    val history: StateFlow<List<RecentSessionUi>> = _history.asStateFlow()

    private val _selectedSession = MutableStateFlow<SessionDetailUi?>(null)
    val selectedSession: StateFlow<SessionDetailUi?> = _selectedSession.asStateFlow()

    private val _effects = MutableSharedFlow<HostEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<HostEffect> = _effects.asSharedFlow()

    @Volatile
    private var active: ActiveSession? = null
    private var selectedSessionJob: Job? = null

    init {
        viewModelScope.launch {
            client.state.collect { state ->
                sessionMutex.withLock { consumeBleState(state) }
            }
        }
        viewModelScope.launch {
            repository.observeRecentSessions().collectLatest { summaries ->
                // Summary rows intentionally have no selected/final value. Read each session's
                // evidence and project only its most recently observed measurement.
                _history.value = summaries.mapNotNull { summary ->
                    repository.observeSessionWithFrames(summary.session.id).first()?.toRecentUi()
                }
            }
        }
    }

    fun onAction(action: UiAction) {
        when (action) {
            UiAction.RequestPermissions ->
                _effects.tryEmit(HostEffect.RequestBluetoothPermissions)
            UiAction.StartScan,
            UiAction.Retry,
            UiAction.StartAnotherMeasurement,
            -> viewModelScope.launch {
                sessionMutex.withLock { beginAttempt() }
            }
            UiAction.CancelCurrentOperation -> viewModelScope.launch {
                sessionMutex.withLock {
                    val current = active
                    if (current == null) {
                        _uiState.value = _uiState.value.copy(stage = TrackyStage.Idle)
                    } else {
                        active = current.copy(finishIntent = FinishIntent.Cancel)
                        _uiState.value = _uiState.value.copy(
                            stage = TrackyStage.Stopping(current.latestGrams),
                        )
                        client.stop()
                    }
                }
            }
            UiAction.StopMeasurement -> viewModelScope.launch {
                sessionMutex.withLock {
                    active?.let { current ->
                        active = current.copy(finishIntent = FinishIntent.Complete)
                        _uiState.value = _uiState.value.copy(
                            stage = TrackyStage.Stopping(current.latestGrams),
                        )
                        client.stop()
                    }
                }
            }
            UiAction.OpenHistory -> navigate(UiDestination.History)
            UiAction.OpenInstrument -> navigate(UiDestination.Instrument)
            is UiAction.OpenSession -> openSession(action.sessionId)
            is UiAction.ChangeUnit ->
                _uiState.value = _uiState.value.copy(unitDisplay = action.unit)
        }
    }

    /** Called only by the Activity Result callback after requesting the two Bluetooth permissions. */
    fun onPermissionResult() {
        viewModelScope.launch {
            sessionMutex.withLock {
                if (hasBluetoothPermissions()) {
                    beginAttempt()
                } else {
                    showPermissionRequired(denied = true)
                }
            }
        }
    }

    /** Supplies the foreground gate used by the foreground-only BLE client. */
    fun setForeground(isForeground: Boolean) {
        foreground.set(isForeground)
        if (!isForeground && active != null) {
            client.stop(DisconnectReason.ForegroundLost)
        }
    }

    fun navigateBack() {
        when (_uiState.value.destination) {
            UiDestination.SessionDetail -> navigate(UiDestination.History)
            UiDestination.History -> navigate(UiDestination.Instrument)
            UiDestination.Instrument -> Unit
        }
    }

    private suspend fun beginAttempt() {
        if (active != null) return
        if (!foreground.get()) {
            _uiState.value = _uiState.value.copy(
                stage = TrackyStage.Failure(
                    title = "App is not in the foreground",
                    message = "Return to Tracky before searching for the scale.",
                ),
            )
            return
        }
        if (!hasBluetoothPermissions()) {
            showPermissionRequired(denied = false)
            return
        }

        val startedAt = clock.instant()
        val requestedUnit = _uiState.value.unitDisplay.toDataUnit()
        val session = try {
            repository.createSession(
                startedAt = startedAt,
                requestedDisplayUnit = requestedUnit,
                // Discovery happens after an attempt begins and this repository has no rename API.
                safeAdvertisedName = UNKNOWN_SCALE_NAME,
            )
        } catch (_: Throwable) {
            _uiState.value = _uiState.value.copy(
                stage = TrackyStage.Failure(
                    title = "Could not start a session",
                    message = "Tracky could not open local evidence storage. You can try again.",
                ),
            )
            return
        }

        active = ActiveSession(session.id, startedAt)
        _uiState.value = _uiState.value.copy(stage = TrackyStage.Scanning())
        val attemptId = client.start()
        active = requireNotNull(active).copy(attemptId = attemptId)
    }

    private fun hasBluetoothPermissions(): Boolean =
        permissionGate.canScan() && permissionGate.canConnect()

    private fun showPermissionRequired(denied: Boolean) {
        val missing = buildList {
            if (!permissionGate.canScan()) add("Bluetooth scan")
            if (!permissionGate.canConnect()) add("Bluetooth connect")
        }
        _uiState.value = _uiState.value.copy(
            stage = TrackyStage.PermissionRequired(
                permissionLabels = missing,
                rationale = if (denied) {
                    "Nearby access was not granted. Nothing was scanned; you can try again whenever you are ready."
                } else {
                    "Nearby device access is needed only while finding and connecting to the scale."
                },
            ),
        )
    }

    private suspend fun consumeBleState(state: BleConnectionState) {
        when (state) {
            BleConnectionState.Idle -> Unit
            is BleConnectionState.Scanning -> {
                val current = active ?: return
                if (current.attemptId != null && current.attemptId != state.attemptId) return
                active = current.copy(attemptId = state.attemptId)
                _uiState.value = _uiState.value.copy(stage = TrackyStage.Scanning())
            }
            is BleConnectionState.DeviceFound -> {
                val current = activeFor(state.attemptId) ?: return
                val name = state.advertisedName ?: UNKNOWN_SCALE_NAME
                if (!current.deviceFoundHapticSent) {
                    _effects.tryEmit(HostEffect.DeviceFoundHaptic)
                }
                active = current.copy(
                    deviceName = name,
                    deviceFoundHapticSent = true,
                )
                _uiState.value = _uiState.value.copy(stage = TrackyStage.Connecting(name))
            }
            is BleConnectionState.Connecting -> {
                val current = activeFor(state.attemptId) ?: return
                // DeviceFound and Connecting are published back-to-back by the transport's
                // StateFlow, so a slow collector may only observe Connecting. Connecting proves
                // discovery occurred; preserve the single restrained discovery haptic.
                if (!current.deviceFoundHapticSent) {
                    _effects.tryEmit(HostEffect.DeviceFoundHaptic)
                    active = current.copy(deviceFoundHapticSent = true)
                }
                _uiState.value = _uiState.value.copy(
                    stage = TrackyStage.Connecting((active ?: current).deviceName),
                )
            }
            is BleConnectionState.DiscoveringServices -> showHandshake(
                state.attemptId,
                "Checking scale services",
            )
            is BleConnectionState.EnablingNotifications -> showHandshake(
                state.attemptId,
                "Opening the reading channel",
            )
            is BleConnectionState.Handshaking -> showHandshake(
                state.attemptId,
                "Preparing measurement",
            )
            is BleConnectionState.Measuring -> {
                val current = activeFor(state.attemptId) ?: return
                if (!current.measuringHapticSent) {
                    _effects.tryEmit(HostEffect.MeasuringHaptic)
                    active = current.copy(measuringHapticSent = true)
                }
                val latest = active ?: current
                // TrackyStage.Measuring cannot represent “live, no observation yet”; do not invent
                // a zero. Stay in preparation until the first actual measurement frame arrives.
                _uiState.value = _uiState.value.copy(
                    stage = latest.latestGrams?.let {
                        measuringStage(latest)
                    } ?: TrackyStage.Handshaking(
                        latest.deviceName,
                        "Ready; waiting for the first scale reading",
                    ),
                )
            }
            is BleConnectionState.Stopping -> {
                val current = activeFor(state.attemptId) ?: return
                _uiState.value = _uiState.value.copy(
                    stage = TrackyStage.Stopping(current.latestGrams),
                )
            }
            is BleConnectionState.PermissionDenied -> {
                activeFor(state.attemptId) ?: return
                finishAborted(state.attemptId)
                showPermissionRequired(denied = true)
            }
            is BleConnectionState.BluetoothOff -> {
                activeFor(state.attemptId) ?: return
                finishAborted(state.attemptId)
                _uiState.value = _uiState.value.copy(
                    stage = TrackyStage.Failure(
                        title = "Bluetooth is off",
                        message = "Turn on Bluetooth, then try finding the scale again.",
                    ),
                )
            }
            is BleConnectionState.Failure -> {
                activeFor(state.attemptId) ?: return
                finishAborted(state.attemptId)
                _uiState.value = _uiState.value.copy(
                    stage = TrackyStage.Failure(
                        title = failureTitle(state.reason),
                        message = failureMessage(state.reason, state.status),
                    ),
                )
            }
            is BleConnectionState.Disconnected -> consumeDisconnect(state)
        }
    }

    private fun showHandshake(attemptId: Long, step: String) {
        val current = activeFor(attemptId) ?: return
        _uiState.value = _uiState.value.copy(
            stage = TrackyStage.Handshaking(current.deviceName, step),
        )
    }

    private fun consumeMeasurement(event: MeasurementEvent) {
        val current = activeFor(event.attemptId) ?: return
        val grams = event.frame.weightRawU16Be * 10L
        val updated = current.copy(
            latestGrams = grams,
            sampleCount = current.sampleCount + 1,
        )
        if (!updated.measuringHapticSent) {
            _effects.tryEmit(HostEffect.MeasuringHaptic)
        }
        active = updated.copy(measuringHapticSent = true)
        _uiState.value = _uiState.value.copy(stage = measuringStage(requireNotNull(active)))
    }

    private fun measuringStage(session: ActiveSession): TrackyStage.Measuring =
        TrackyStage.Measuring(
            grams = requireNotNull(session.latestGrams),
            elapsedLabel = formatDuration(session.startedAt, clock.instant()),
            sampleCount = session.sampleCount,
            evidenceStatus = "Latest live observation from the scale",
        )

    private suspend fun consumeDisconnect(state: BleConnectionState.Disconnected) {
        val current = activeFor(state.attemptId) ?: return
        if (state.reason == DisconnectReason.Requested && current.finishIntent == FinishIntent.Complete) {
            val endedAt = safeEnd(current.startedAt)
            val stored = runCatching {
                repository.observeSessionWithFrames(current.sessionId).first()
            }.getOrNull()
            val storedMeasurements = stored?.frames.orEmpty().mapNotNull { frame ->
                frame.measurement?.takeIf { frame.decodeError == null }
            }
            val storedLatestGrams = storedMeasurements.lastOrNull()?.rawU16?.times(10L)
            val saved = runCatching {
                repository.completeSession(current.sessionId, endedAt)
            }.isSuccess
            active = null
            if (!saved) {
                _uiState.value = _uiState.value.copy(
                    stage = TrackyStage.Failure(
                        title = "Could not finish saving",
                        message = "The evidence captured before the storage error was retained.",
                    ),
                )
            } else if (storedLatestGrams != null) {
                _uiState.value = _uiState.value.copy(
                    stage = TrackyStage.Saved(
                        sessionId = current.sessionId,
                        grams = storedLatestGrams,
                        durationLabel = formatDuration(current.startedAt, endedAt),
                        sampleCount = storedMeasurements.size,
                    ),
                )
                _effects.tryEmit(HostEffect.SavedHaptic)
            } else {
                // The data model requires a weight for Saved; preserve the COMPLETE session but do
                // not display a fabricated reading.
                _uiState.value = _uiState.value.copy(
                    stage = TrackyStage.Failure(
                        title = "Session saved without a reading",
                        message = "No weight observation arrived. The captured wire evidence was still saved.",
                    ),
                )
            }
            return
        }

        finishAborted(state.attemptId)
        _uiState.value = _uiState.value.copy(
            stage = when {
                state.reason == DisconnectReason.Requested && current.finishIntent == FinishIntent.Cancel ->
                    TrackyStage.Idle
                state.reason == DisconnectReason.ForegroundLost -> TrackyStage.Failure(
                    title = "Measurement stopped",
                    message = "Tracky left the foreground, so the foreground-only connection was closed. Partial evidence was kept.",
                )
                else -> TrackyStage.Failure(
                    title = "Scale disconnected",
                    message = "The scale ended the connection. Partial evidence was kept and you can try again.",
                )
            },
        )
    }

    private suspend fun finishAborted(attemptId: Long) {
        val current = activeFor(attemptId) ?: return
        runCatching { repository.abortSession(current.sessionId, safeEnd(current.startedAt)) }
        active = null
    }

    /**
     * Authoritative lossless boundary: the BLE loop waits for this Room insert before decoding RX
     * or continuing after a successful TX. A storage failure ends the attempt rather than allowing
     * a falsely complete session with missing evidence.
     */
    private suspend fun persistWireEvent(event: WireEvent) {
        val session = activeFor(event.attemptId) ?: return
        val decode = try {
            WireDecode.Decoded(ScaleProtocol.decode(event.raw.toByteArray()))
        } catch (_: Throwable) {
            WireDecode.DecoderThrew
        }
        val metadata = decodeMetadata(event, decode)
        val elapsed = Duration.between(session.startedAt, event.at).toMillis().coerceAtLeast(0L)
        repository.appendFrame(
            session.sessionId,
            NewRawFrame(
                sequence = event.sequence,
                observedAt = event.at,
                elapsedMs = elapsed,
                direction = if (event.direction == WireDirection.Rx) FrameDirection.RX else FrameDirection.TX,
                characteristicUuid = event.characteristic.uuid,
                raw = event.raw.toByteArray(),
                opcode = metadata.opcode,
                checksum = metadata.checksum,
                frameKind = metadata.kind,
                decodeError = metadata.error,
                measurement = metadata.measurement,
            ),
        )
    }

    private fun decodeMetadata(event: WireEvent, decode: WireDecode?): DecodeMetadata = when (decode) {
        is WireDecode.Decoded -> when (val result = decode.result) {
            is DecodeResult.Success -> {
                val frame = result.frame
                DecodeMetadata(
                    opcode = frame.opcode,
                    checksum = frame.checksumByte.takeIf { frame.isChecksumBearing },
                    kind = frame.opcodeName,
                    error = if (frame.checksumValid == false) "checksum mismatch" else null,
                    measurement = (frame as? MeasurementFrame)?.let {
                        MeasurementInterpretation(
                            rawU16 = it.weightRawU16Be,
                            divisor = it.weightScaleDivisor,
                            b5 = it.b5,
                            b6 = it.b6,
                            resistanceValues = it.resistanceRaw,
                        )
                    },
                )
            }
            is DecodeResult.Malformed -> DecodeMetadata(
                opcode = result.opcode,
                checksum = result.checksumValid?.let {
                    event.raw.toByteArray().lastOrNull()?.toInt()?.and(0xff)
                },
                kind = "malformed",
                error = buildString {
                    append(result.reason)
                    if (result.checksumValid == false) append("; checksum mismatch")
                },
            )
        }
        WireDecode.DecoderThrew -> DecodeMetadata(
            opcode = event.raw.toByteArray().firstOrNull()?.toInt()?.and(0xff),
            checksum = null,
            kind = "decoder_exception",
            error = "decoder threw while interpreting this frame",
        )
        null -> DecodeMetadata(
            opcode = event.raw.toByteArray().firstOrNull()?.toInt()?.and(0xff),
            checksum = null,
            kind = "undecoded",
            error = "decode metadata unavailable",
        )
    }

    private fun navigate(destination: UiDestination) {
        if (destination != UiDestination.SessionDetail) {
            selectedSessionJob?.cancel()
            _selectedSession.value = null
        }
        _uiState.value = _uiState.value.copy(destination = destination)
    }

    private fun openSession(sessionId: String) {
        selectedSessionJob?.cancel()
        _selectedSession.value = null
        _uiState.value = _uiState.value.copy(destination = UiDestination.SessionDetail)
        selectedSessionJob = viewModelScope.launch {
            repository.observeSessionWithFrames(sessionId).collect { session ->
                _selectedSession.value = session?.toDetailUi()
            }
        }
    }

    private fun SessionWithFrames.toRecentUi(): RecentSessionUi {
        val measurements = frames.mapNotNull { frame ->
            frame.measurement?.takeIf { frame.decodeError == null }
        }
        val latest = measurements.lastOrNull()
        val end = session.endedAt ?: clock.instant()
        return RecentSessionUi(
            id = session.id,
            endedAtLabel = session.endedAt?.let(::formatInstant) ?: "In progress",
            grams = latest?.rawU16?.times(10L),
            durationLabel = formatDuration(session.startedAt, end),
            sampleCount = measurements.size,
            evidenceSummary = if (latest == null) {
                "No valid weight observation • ${frames.size} wire frames retained"
            } else {
                "Most recent observation • ${measurements.size} measurement frames • ${frames.size} wire frames"
            },
        )
    }

    private fun SessionWithFrames.toDetailUi(): SessionDetailUi {
        val recent = toRecentUi()
        val facts = buildList {
            add(
                EvidenceDetailUi(
                    label = "Session status",
                    value = session.status.name.lowercase().replaceFirstChar(Char::uppercase),
                    supportingText = "Completion status of the evidence session",
                ),
            )
            add(
                EvidenceDetailUi(
                    label = "Most recent observation",
                    value = recent.grams?.let { "$it g" } ?: "None",
                    supportingText = "Latest valid measurement frame by wire sequence; not a final, stable, or selected value",
                ),
            )
            frames.forEach { frame ->
                add(frame.toEvidenceUi())
                frame.measurement?.resistanceValues?.takeIf(List<Int>::isNotEmpty)?.let { words ->
                    add(
                        EvidenceDetailUi(
                            label = "Raw five-word block (unitless)",
                            value = words.joinToString(prefix = "[", postfix = "]"),
                            supportingText = "Opaque unsigned 16-bit words from frame #${frame.sequence}; no physical unit is established",
                        ),
                    )
                }
            }
        }
        return SessionDetailUi(
            session = recent,
            startedAtLabel = formatInstant(session.startedAt),
            endedAtLabel = session.endedAt?.let(::formatInstant) ?: "In progress",
            deviceLabel = if (session.safeAdvertisedName == UNKNOWN_SCALE_NAME) {
                "Nearby scale (advertised name not retained)"
            } else {
                session.safeAdvertisedName
            },
            evidence = facts,
        )
    }

    private fun RawFrameEvidence.toEvidenceUi(): EvidenceDetailUi {
        val opcodeLabel = opcode?.let { "0x${it.toString(16).padStart(2, '0')}" } ?: "none"
        val checksumLabel = checksum?.let { "0x${it.toString(16).padStart(2, '0')}" } ?: "not present"
        val measurementLabel = measurement?.let {
            "; observation raw=${it.rawU16}, divisor=${it.divisor} (kg layout)"
        }.orEmpty()
        return EvidenceDetailUi(
            label = "#${sequence} ${direction.name} • ${frameKind ?: "unclassified"}",
            value = raw.joinToString(" ") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0').uppercase(Locale.ROOT)
            },
            supportingText = "${formatInstant(observedAt)} • opcode $opcodeLabel • checksum byte $checksumLabel" +
                measurementLabel + decodeError?.let { "; $it" }.orEmpty(),
        )
    }

    private fun activeFor(attemptId: Long): ActiveSession? =
        active?.takeIf { it.attemptId == attemptId }

    private fun safeEnd(startedAt: Instant): Instant =
        clock.instant().takeUnless { it.isBefore(startedAt) } ?: startedAt

    private fun failureTitle(reason: BleFailureReason): String = when (reason) {
        BleFailureReason.ScanTimedOut -> "Scale not found"
        BleFailureReason.ConnectTimedOut -> "Connection timed out"
        BleFailureReason.RequiredServiceMissing,
        BleFailureReason.RequiredCharacteristicMissing,
        BleFailureReason.RequiredCharacteristicPropertyMissing,
        -> "Scale is not compatible"
        BleFailureReason.ProtocolError -> "Scale protocol stopped"
        BleFailureReason.EvidenceSinkFailed -> "Evidence storage stopped"
        else -> "Could not connect to the scale"
    }

    private fun failureMessage(reason: BleFailureReason, status: Int?): String {
        val base = when (reason) {
            BleFailureReason.ScanTimedOut -> "No supported scale was seen nearby. Keep it awake and try again."
            BleFailureReason.ConnectTimedOut -> "The scale did not accept a connection in time. Partial evidence was kept."
            BleFailureReason.Disconnected -> "The scale disconnected. Partial evidence was kept."
            BleFailureReason.ProtocolError -> "A protocol error ended this attempt. Raw evidence received beforehand was kept."
            BleFailureReason.EvidenceSinkFailed -> "Tracky stopped because a wire frame could not be stored. Previously committed evidence remains in the interrupted session."
            BleFailureReason.RequiredServiceMissing,
            BleFailureReason.RequiredCharacteristicMissing,
            BleFailureReason.RequiredCharacteristicPropertyMissing,
            -> "The nearby device does not expose the required scale service."
            BleFailureReason.WriteRejected,
            BleFailureReason.WriteTimedOut,
            -> "The scale did not accept a required command. Partial evidence was kept."
            else -> "The Bluetooth operation failed. Partial evidence was kept; you can try again."
        }
        return status?.let { "$base Bluetooth status: $it." } ?: base
    }

    private fun formatInstant(instant: Instant): String =
        DateTimeFormatter.ofPattern("MMM d, yyyy • h:mm a", Locale.getDefault())
            .format(instant.atZone(ZoneId.systemDefault()))

    private fun formatDuration(start: Instant, end: Instant): String {
        val seconds = Duration.between(start, end).seconds.coerceAtLeast(0)
        val minutes = seconds / 60
        val remainder = seconds % 60
        return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
    }

    override fun onCleared() {
        client.close()
        // ViewModel scope cannot be used after clear. Best-effort synchronous finalization prevents
        // an explicit OPEN row from being mistaken for a completed session after owner teardown.
        active?.let { current ->
            runBlocking(Dispatchers.IO) {
                runCatching { repository.abortSession(current.sessionId, safeEnd(current.startedAt)) }
            }
            active = null
        }
    }

    sealed interface HostEffect {
        data object RequestBluetoothPermissions : HostEffect
        data object DeviceFoundHaptic : HostEffect
        data object MeasuringHaptic : HostEffect
        data object SavedHaptic : HostEffect
    }

    class Factory(
        private val context: Context,
        private val repository: SessionHistoryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java))
            return MainViewModel(context.applicationContext, repository) as T
        }
    }

    private data class ActiveSession(
        val sessionId: String,
        val startedAt: Instant,
        val attemptId: Long? = null,
        val deviceName: String = UNKNOWN_SCALE_NAME,
        val latestGrams: Long? = null,
        val sampleCount: Int = 0,
        val finishIntent: FinishIntent? = null,
        val deviceFoundHapticSent: Boolean = false,
        val measuringHapticSent: Boolean = false,
    )

    private enum class FinishIntent { Complete, Cancel }

    private data class DecodeMetadata(
        val opcode: Int?,
        val checksum: Int?,
        val kind: String?,
        val error: String?,
        val measurement: MeasurementInterpretation? = null,
    )

    private fun UnitDisplay.toDataUnit(): DisplayUnit = when (this) {
        UnitDisplay.KG -> DisplayUnit.KG
        UnitDisplay.LB -> DisplayUnit.LB
    }

    private companion object {
        const val UNKNOWN_SCALE_NAME = "Nearby scale"
    }
}
