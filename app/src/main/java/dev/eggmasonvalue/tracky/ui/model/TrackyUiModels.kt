package dev.eggmasonvalue.tracky.ui.model

import androidx.compose.runtime.Immutable

/** The unit used to present a reading. Stored readings remain grams. */
@Immutable
enum class UnitDisplay {
    KG,
    LB,
}

@Immutable
enum class UiDestination {
    Instrument,
    History,
    SessionDetail,
}

/** Complete, render-only state consumed by the Compose UI. */
@Immutable
data class TrackyUiState(
    val stage: TrackyStage = TrackyStage.Idle,
    val unitDisplay: UnitDisplay = UnitDisplay.KG,
    val destination: UiDestination = UiDestination.Instrument,
)

/**
 * Mutually exclusive states of the scale workflow. All weight values are canonical grams so a
 * [UnitDisplay] change can never alter persisted or protocol data.
 */
@Immutable
sealed interface TrackyStage {
    @Immutable
    data object Idle : TrackyStage

    @Immutable
    data class PermissionRequired(
        val permissionLabels: List<String>,
        val rationale: String = "Nearby device access is needed to find and connect to the scale.",
    ) : TrackyStage

    @Immutable
    data class Scanning(
        val elapsedSeconds: Int = 0,
        val nearbyDeviceCount: Int? = null,
    ) : TrackyStage

    @Immutable
    data class Connecting(
        val deviceName: String,
    ) : TrackyStage

    @Immutable
    data class Handshaking(
        val deviceName: String,
        val stepLabel: String = "Preparing measurement",
    ) : TrackyStage

    @Immutable
    data class Measuring(
        val grams: Long,
        val elapsedLabel: String,
        val sampleCount: Int,
        val evidenceStatus: String = "Receiving scale readings",
    ) : TrackyStage

    @Immutable
    data class Stopping(
        val grams: Long?,
        val evidenceStatus: String = "Closing the measurement and saving its evidence",
    ) : TrackyStage

    @Immutable
    data class Saved(
        val sessionId: String,
        val grams: Long,
        val durationLabel: String,
        val sampleCount: Int,
    ) : TrackyStage

    @Immutable
    data class Failure(
        val title: String,
        val message: String,
        val canRetry: Boolean = true,
    ) : TrackyStage
}

/** Compact history projection. Labels are pre-localized by the parent layer. */
@Immutable
data class RecentSessionUi(
    val id: String,
    val endedAtLabel: String,
    val grams: Long?,
    val durationLabel: String,
    val sampleCount: Int,
    val evidenceSummary: String = "$sampleCount readings captured",
)

/** One factual item shown in a saved session's evidence section. */
@Immutable
data class EvidenceDetailUi(
    val label: String,
    val value: String,
    val supportingText: String? = null,
)

/** Detail projection for a session. This model intentionally contains no inferred graph data. */
@Immutable
data class SessionDetailUi(
    val session: RecentSessionUi,
    val startedAtLabel: String,
    val endedAtLabel: String,
    val deviceLabel: String? = null,
    val evidence: List<EvidenceDetailUi> = emptyList(),
)

/** Events emitted by UI. The host owns navigation, permissions, persistence, and BLE work. */
@Immutable
sealed interface UiAction {
    data object RequestPermissions : UiAction
    data object StartScan : UiAction
    data object CancelCurrentOperation : UiAction
    data object StopMeasurement : UiAction
    data object Retry : UiAction
    data object StartAnotherMeasurement : UiAction
    data object OpenHistory : UiAction
    data object OpenInstrument : UiAction
    data class OpenSession(val sessionId: String) : UiAction
    data class ChangeUnit(val unit: UnitDisplay) : UiAction
}
