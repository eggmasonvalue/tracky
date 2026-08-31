package dev.eggmasonvalue.tracky.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.eggmasonvalue.tracky.ui.model.EvidenceDetailUi
import dev.eggmasonvalue.tracky.ui.model.RecentSessionUi
import dev.eggmasonvalue.tracky.ui.model.SessionDetailUi
import dev.eggmasonvalue.tracky.ui.model.TrackyStage
import dev.eggmasonvalue.tracky.ui.model.TrackyUiState
import dev.eggmasonvalue.tracky.ui.model.UiDestination
import dev.eggmasonvalue.tracky.ui.theme.TrackyTheme

private val previewHistory = listOf(
    RecentSessionUi(
        id = "session-3",
        endedAtLabel = "Today, 7:42 PM",
        grams = 72460,
        durationLabel = "42 sec",
        sampleCount = 84,
    ),
    RecentSessionUi(
        id = "session-2",
        endedAtLabel = "Yesterday, 8:10 AM",
        grams = 72180,
        durationLabel = "37 sec",
        sampleCount = 73,
    ),
    RecentSessionUi(
        id = "session-1",
        endedAtLabel = "28 Aug, 6:56 PM",
        grams = 72710,
        durationLabel = "45 sec",
        sampleCount = 91,
    ),
)

private val previewDetail = SessionDetailUi(
    session = previewHistory.first(),
    startedAtLabel = "Today, 7:41:18 PM",
    endedAtLabel = "Today, 7:42:00 PM",
    deviceLabel = "Tracky Scale",
    evidence = listOf(
        EvidenceDetailUi("Reading count", "84", "Readings captured during this session"),
        EvidenceDetailUi("First reading", "7:41:18 PM"),
        EvidenceDetailUi("Last reading", "7:42:00 PM"),
        EvidenceDetailUi("Source", "Nearby scale"),
    ),
)

@Composable
private fun PreviewApp(state: TrackyUiState) {
    TrackyTheme(dynamicColor = false) {
        TrackyApp(
            uiState = state,
            history = previewHistory,
            selectedSession = previewDetail,
            onAction = {},
        )
    }
}

@Preview(name = "Instrument • Idle", showBackground = true, showSystemUi = true)
@Composable
private fun IdlePreview() = PreviewApp(TrackyUiState())

@Preview(name = "Instrument • Scanning", showBackground = true, showSystemUi = true)
@Composable
private fun ScanningPreview() = PreviewApp(
    TrackyUiState(stage = TrackyStage.Scanning(elapsedSeconds = 8, nearbyDeviceCount = 1)),
)

@Preview(name = "Instrument • Connecting", showBackground = true, showSystemUi = true)
@Composable
private fun ConnectingPreview() = PreviewApp(
    TrackyUiState(stage = TrackyStage.Connecting(deviceName = "Tracky Scale")),
)

@Preview(name = "Instrument • Handshaking", showBackground = true, showSystemUi = true)
@Composable
private fun HandshakingPreview() = PreviewApp(
    TrackyUiState(
        stage = TrackyStage.Handshaking(
            deviceName = "QN-Scale",
            stepLabel = "Opening the reading channel",
        ),
    ),
)

@Preview(name = "Instrument • Measuring", showBackground = true, showSystemUi = true)
@Composable
private fun MeasuringPreview() = PreviewApp(
    TrackyUiState(
        stage = TrackyStage.Measuring(
            grams = 72460,
            elapsedLabel = "00:42",
            sampleCount = 84,
        ),
    ),
)

@Preview(name = "Instrument • Saved", showBackground = true, showSystemUi = true)
@Composable
private fun SavedPreview() = PreviewApp(
    TrackyUiState(
        stage = TrackyStage.Saved(
            sessionId = "session-3",
            grams = 72460,
            durationLabel = "42 sec",
            sampleCount = 84,
        ),
    ),
)

@Preview(name = "Instrument • Failure", showBackground = true, showSystemUi = true)
@Composable
private fun FailurePreview() = PreviewApp(
    TrackyUiState(
        stage = TrackyStage.Failure(
            title = "Scale disconnected",
            message = "Move closer to the scale, wake it, and try again.",
        ),
    ),
)

@Preview(
    name = "Instrument • Dark",
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DarkPreview() = PreviewApp(
    TrackyUiState(
        stage = TrackyStage.Measuring(
            grams = 72460,
            elapsedLabel = "00:42",
            sampleCount = 84,
        ),
    ),
)

@Preview(name = "Instrument • Large type", showBackground = true, fontScale = 1.5f)
@Composable
private fun LargeTypePreview() = PreviewApp(
    TrackyUiState(
        stage = TrackyStage.PermissionRequired(
            permissionLabels = listOf("Nearby devices"),
        ),
    ),
)

@Preview(name = "Instrument • Measuring large type", showBackground = true, fontScale = 1.5f)
@Composable
private fun MeasuringLargeTypePreview() = PreviewApp(
    TrackyUiState(
        stage = TrackyStage.Measuring(
            grams = 179990,
            elapsedLabel = "1m 12s",
            sampleCount = 144,
        ),
    ),
)

@Preview(name = "History", showBackground = true, showSystemUi = true)
@Composable
private fun HistoryPreview() = PreviewApp(
    TrackyUiState(destination = UiDestination.History),
)

@Preview(name = "Session detail", showBackground = true, showSystemUi = true)
@Preview(name = "Session detail • Large type", showBackground = true, fontScale = 1.5f)
@Composable
private fun SessionDetailPreview() = PreviewApp(
    TrackyUiState(destination = UiDestination.SessionDetail),
)
