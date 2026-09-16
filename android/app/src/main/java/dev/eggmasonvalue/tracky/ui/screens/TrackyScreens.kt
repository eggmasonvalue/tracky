@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.eggmasonvalue.tracky.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eggmasonvalue.tracky.ui.model.EvidenceDetailUi
import dev.eggmasonvalue.tracky.ui.model.RecentSessionUi
import dev.eggmasonvalue.tracky.ui.model.SessionDetailUi
import dev.eggmasonvalue.tracky.ui.model.TrackyStage
import dev.eggmasonvalue.tracky.ui.model.TrackyUiState
import dev.eggmasonvalue.tracky.ui.model.UiAction
import dev.eggmasonvalue.tracky.ui.model.UnitDisplay
import java.text.NumberFormat

private val InstrumentShape = RoundedCornerShape(
    topStart = 44.dp,
    topEnd = 20.dp,
    bottomEnd = 44.dp,
    bottomStart = 20.dp,
)

@Composable
fun InstrumentScreen(
    uiState: TrackyUiState,
    onAction: (UiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
            AppHeader(
                actionLabel = "History",
                actionDescription = "Open measurement history",
                onAction = { onAction(UiAction.OpenHistory) },
            )
            Spacer(Modifier.height(20.dp))
            UnitSelector(
                selected = uiState.unitDisplay,
                onSelected = { onAction(UiAction.ChangeUnit(it)) },
                modifier = Modifier.align(Alignment.End),
            )
            Spacer(Modifier.height(18.dp))
            InstrumentSurface(stage = uiState.stage, unit = uiState.unitDisplay)
            Spacer(Modifier.height(18.dp))
            EvidenceStatus(stage = uiState.stage)
            Spacer(Modifier.height(20.dp))
            PrimaryStageAction(stage = uiState.stage, onAction = onAction)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AppHeader(
    actionLabel: String,
    actionDescription: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScaleGlyph()
            Spacer(Modifier.width(10.dp))
            Text(
                text = "tracky",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        TextButton(
            onClick = onAction,
            modifier = Modifier.semantics { contentDescription = actionDescription },
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun ScaleGlyph() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = "Tracky scale" },
    ) {
        drawCircle(color = color.copy(alpha = 0.16f))
        drawArc(
            color = color,
            startAngle = 205f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(size.width * 0.24f, size.height * 0.25f),
            size = size * 0.52f,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.5f, size.height * 0.49f),
            end = Offset(size.width * 0.64f, size.height * 0.37f),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun UnitSelector(
    selected: UnitDisplay,
    onSelected: (UnitDisplay) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.semantics { contentDescription = "Weight display unit" },
    ) {
        UnitDisplay.entries.forEachIndexed { index, unit ->
            SegmentedButton(
                selected = selected == unit,
                onClick = { onSelected(unit) },
                shape = SegmentedButtonDefaults.itemShape(index, UnitDisplay.entries.size),
                icon = {},
                label = { Text(unit.name.lowercase()) },
            )
        }
    }
}

@Composable
private fun InstrumentSurface(stage: TrackyStage, unit: UnitDisplay) {
    val presentation = stagePresentation(stage)
    val targetColor = when (stage) {
        is TrackyStage.Failure -> MaterialTheme.colorScheme.errorContainer
        is TrackyStage.Saved -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val targetContentColor = when (stage) {
        is TrackyStage.Failure -> MaterialTheme.colorScheme.onErrorContainer
        is TrackyStage.Saved -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val color = animateColorAsState(targetColor, label = "instrument color")
    val contentColor = animateColorAsState(targetContentColor, label = "instrument content color")
    val expressiveCorner = animateDpAsState(
        targetValue = if (stage is TrackyStage.Measuring || stage is TrackyStage.Saved) 54.dp else 44.dp,
        label = "instrument shape",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = expressiveCorner.value,
            topEnd = 20.dp,
            bottomEnd = expressiveCorner.value,
            bottomStart = 20.dp,
        ),
        color = color.value,
        contentColor = contentColor.value,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 290.dp)
                .padding(horizontal = 28.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ActivityIndicator(stage)
            if (presentation.showIndicator) Spacer(Modifier.height(18.dp))
            Text(
                text = presentation.eyebrow,
                style = MaterialTheme.typography.labelLarge,
                color = when (stage) {
                    is TrackyStage.Failure -> MaterialTheme.colorScheme.onErrorContainer
                    is TrackyStage.Saved -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.primary
                },
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            val grams = stage.readingGrams()
            if (grams != null) {
                WeightAnchor(grams = grams, unit = unit)
            } else {
                Text(
                    text = presentation.title,
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = presentation.supportingText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.takeUnless {
                    stage is TrackyStage.Failure || stage is TrackyStage.Saved
                } ?: androidx.compose.ui.graphics.Color.Unspecified,
            )
            if (stage is TrackyStage.PermissionRequired && stage.permissionLabels.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stage.permissionLabels.joinToString(" • "),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ActivityIndicator(stage: TrackyStage) {
    when (stage) {
        is TrackyStage.Scanning -> LoadingIndicator(
            modifier = Modifier
                .size(62.dp)
                .semantics { contentDescription = "Searching for nearby scales" },
        )
        is TrackyStage.Connecting,
        is TrackyStage.Handshaking,
        is TrackyStage.Measuring,
        is TrackyStage.Stopping,
        -> LinearWavyProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .semantics { contentDescription = stage.progressDescription() },
        )
        else -> Unit
    }
}

@Composable
private fun WeightAnchor(grams: Long, unit: UnitDisplay) {
    val formatted = remember(grams, unit) { formatWeight(grams, unit) }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$formatted ${unit.spokenLabel}" },
    ) {
        Text(
            text = formatted,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = unit.name.lowercase(),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun EvidenceStatus(stage: TrackyStage) {
    val status = when (stage) {
        TrackyStage.Idle -> "No active measurement"
        is TrackyStage.PermissionRequired -> "Waiting for nearby device permission"
        is TrackyStage.Scanning -> stage.nearbyDeviceCount?.let {
            "$it nearby ${if (it == 1) "device" else "devices"} seen"
        } ?: "Listening for scale advertisements"
        is TrackyStage.Connecting -> "Opening a connection to ${stage.deviceName}"
        is TrackyStage.Handshaking -> stage.stepLabel
        is TrackyStage.Measuring -> "${stage.evidenceStatus} • ${stage.elapsedLabel} • ${stage.sampleCount} readings"
        is TrackyStage.Stopping -> stage.evidenceStatus
        is TrackyStage.Saved -> "${stage.durationLabel} • ${stage.sampleCount} readings captured"
        is TrackyStage.Failure -> "Attempt ended • check History for any retained evidence"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(stage)
            Spacer(Modifier.width(12.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusDot(stage: TrackyStage) {
    val color = when (stage) {
        is TrackyStage.Failure -> MaterialTheme.colorScheme.error
        is TrackyStage.Saved -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Canvas(
        modifier = Modifier
            .size(12.dp)
            .semantics { contentDescription = "Status" },
    ) {
        drawCircle(color)
    }
}

@Composable
private fun PrimaryStageAction(stage: TrackyStage, onAction: (UiAction) -> Unit) {
    val action = when (stage) {
        TrackyStage.Idle -> ActionSpec("Find my scale", true, UiAction.StartScan)
        is TrackyStage.PermissionRequired -> ActionSpec("Continue", true, UiAction.RequestPermissions)
        is TrackyStage.Scanning -> ActionSpec("Cancel search", true, UiAction.CancelCurrentOperation)
        is TrackyStage.Connecting -> ActionSpec("Cancel connection", true, UiAction.CancelCurrentOperation)
        is TrackyStage.Handshaking -> ActionSpec("Cancel", true, UiAction.CancelCurrentOperation)
        is TrackyStage.Measuring -> ActionSpec("Stop & save", true, UiAction.StopMeasurement)
        is TrackyStage.Stopping -> ActionSpec("Saving…", false, UiAction.CancelCurrentOperation)
        is TrackyStage.Saved -> ActionSpec("Measure again", true, UiAction.StartAnotherMeasurement)
        is TrackyStage.Failure -> if (stage.canRetry) {
            ActionSpec("Try again", true, UiAction.Retry)
        } else {
            ActionSpec("Back to scale", true, UiAction.OpenInstrument)
        }
    }
    Button(
        onClick = { onAction(action.uiAction) },
        enabled = action.enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(action.label)
    }
}

@Composable
fun HistoryScreen(
    history: List<RecentSessionUi>,
    unitDisplay: UnitDisplay,
    onAction: (UiAction) -> Unit,
    modifier: Modifier = Modifier,
    uiState: TrackyUiState = TrackyUiState(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
            BackHeader(title = "History", onBack = { onAction(UiAction.OpenInstrument) })
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Recent measurements",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Captured sessions and their reading counts.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Google Health Connect Gateway",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (uiState.healthConnectAuthorized) {
                            "Syncing scale metrics to Health Connect for Google Health & Gemini."
                        } else {
                            "Permission needed to write Weight, Body Fat %, and Lean Mass to Health Connect."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!uiState.healthConnectAuthorized) {
                            Button(onClick = { onAction(UiAction.RequestHealthPermissions) }) {
                                Text("Connect to Health Connect")
                            }
                        } else {
                            Button(onClick = { onAction(UiAction.TriggerSync) }) {
                                Text("Sync from debian-mini-fat")
                            }
                        }
                    }
                    if (uiState.syncStatusMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = uiState.syncStatusMessage,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (history.isEmpty()) {
                EmptyHistory()
            } else {
                Surface(
                    shape = RoundedCornerShape(topStart = 32.dp, topEnd = 16.dp, bottomEnd = 32.dp, bottomStart = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column {
                        history.forEachIndexed { index, session ->
                            SessionListItem(
                                session = session,
                                unitDisplay = unitDisplay,
                                onClick = { onAction(UiAction.OpenSession(session.id)) },
                            )
                            if (index != history.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SessionListItem(
    session: RecentSessionUi,
    unitDisplay: UnitDisplay,
    onClick: () -> Unit,
) {
    val reading = remember(session.grams, unitDisplay) {
        session.grams?.let { "${formatWeight(it, unitDisplay)} ${unitDisplay.name.lowercase()}" }
            ?: "No weight observation"
    }
    ListItem(
        onClick = onClick,
        supportingContent = {
            Text("${session.endedAtLabel} • ${session.durationLabel}\n${session.evidenceSummary}")
        },
        trailingContent = {
            Text(
                text = "View",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        leadingContent = { SessionMark() },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = reading,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun SessionMark() {
    val color = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(24.dp)
                .semantics { contentDescription = "Measurement session" },
        ) {
            drawLine(color, Offset(3.dp.toPx(), size.height * 0.72f), Offset(3.dp.toPx(), size.height * 0.48f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(size.width * 0.5f, size.height * 0.72f), Offset(size.width * 0.5f, size.height * 0.28f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(size.width - 3.dp.toPx(), size.height * 0.72f), Offset(size.width - 3.dp.toPx(), size.height * 0.4f), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun EmptyHistory() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = InstrumentShape,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No sessions yet", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            Text(
                "Completed and interrupted sessions will appear here with their captured evidence.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SessionDetailScreen(
    detail: SessionDetailUi?,
    unitDisplay: UnitDisplay,
    onAction: (UiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
            BackHeader(title = "Session", onBack = { onAction(UiAction.OpenHistory) })
            Spacer(Modifier.height(24.dp))
            if (detail == null) {
                MissingSession()
            } else {
                SessionSummary(detail = detail, unitDisplay = unitDisplay)
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Captured evidence",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Ordered facts retained with this session. No values are inferred.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                EvidenceDetails(detail)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = "Go back" },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Text("‹  Back")
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionSummary(detail: SessionDetailUi, unitDisplay: UnitDisplay) {
    val value = remember(detail.session.grams, unitDisplay) {
        detail.session.grams?.let { "${formatWeight(it, unitDisplay)} ${unitDisplay.name.lowercase()}" }
            ?: "No weight observation"
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = InstrumentShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 30.dp)) {
            Text("Most recent valid observation", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = if (detail.session.grams == null) {
                    MaterialTheme.typography.headlineLarge
                } else {
                    MaterialTheme.typography.displayLarge
                },
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(18.dp))
            DetailPair("Started", detail.startedAtLabel)
            DetailPair("Ended", detail.endedAtLabel)
            DetailPair("Duration", detail.session.durationLabel)
            DetailPair("Readings", detail.session.sampleCount.toString())
            detail.deviceLabel?.let { DetailPair("Scale", it) }
        }
    }
}

@Composable
private fun DetailPair(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(20.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun EvidenceDetails(detail: SessionDetailUi) {
    val facts = if (detail.evidence.isEmpty()) {
        listOf(
            EvidenceDetailUi(
                label = "Reading count",
                value = detail.session.sampleCount.toString(),
                supportingText = "Individual readings captured during this session",
            ),
        )
    } else {
        detail.evidence
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            facts.forEachIndexed { index, fact ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Text(fact.label, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            text = fact.value,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = if (fact.label.startsWith("#")) {
                                FontFamily.Monospace
                            } else {
                                FontFamily.Default
                            },
                        )
                    }
                    fact.supportingText?.let { supporting ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = supporting,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (index != facts.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MissingSession() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(28.dp)) {
            Text("Session unavailable", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Choose a session from history to see its captured evidence.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class StagePresentation(
    val eyebrow: String,
    val title: String,
    val supportingText: String,
    val showIndicator: Boolean = false,
)

private fun stagePresentation(stage: TrackyStage): StagePresentation = when (stage) {
    TrackyStage.Idle -> StagePresentation(
        eyebrow = "SCALE",
        title = "Ready when you are",
        supportingText = "Find your nearby scale to begin a measurement.",
    )
    is TrackyStage.PermissionRequired -> StagePresentation(
        eyebrow = "ACCESS NEEDED",
        title = "Nearby permission",
        supportingText = stage.rationale,
    )
    is TrackyStage.Scanning -> StagePresentation(
        eyebrow = "SEARCHING • ${stage.elapsedSeconds}s",
        title = "Finding your scale",
        supportingText = "Keep the scale awake and close to this phone.",
        showIndicator = true,
    )
    is TrackyStage.Connecting -> StagePresentation(
        eyebrow = "CONNECTING",
        title = stage.deviceName,
        supportingText = "Opening a private nearby connection.",
        showIndicator = true,
    )
    is TrackyStage.Handshaking -> StagePresentation(
        eyebrow = "PREPARING",
        title = stage.deviceName,
        supportingText = stage.stepLabel,
        showIndicator = true,
    )
    is TrackyStage.Measuring -> StagePresentation(
        eyebrow = "MEASURING",
        title = "Measurement in progress",
        supportingText = "Live display from the scale",
        showIndicator = true,
    )
    is TrackyStage.Stopping -> StagePresentation(
        eyebrow = "FINISHING",
        title = "Wrapping up",
        supportingText = "Keeping the readings captured so far.",
        showIndicator = true,
    )
    is TrackyStage.Saved -> StagePresentation(
        eyebrow = "SAVED",
        title = "Measurement saved",
        supportingText = "${stage.durationLabel} • ${stage.sampleCount} readings captured",
    )
    is TrackyStage.Failure -> StagePresentation(
        eyebrow = "NEEDS ATTENTION",
        title = stage.title,
        supportingText = stage.message,
    )
}

private fun TrackyStage.readingGrams(): Long? = when (this) {
    is TrackyStage.Measuring -> grams
    is TrackyStage.Stopping -> grams
    is TrackyStage.Saved -> grams
    else -> null
}

private fun TrackyStage.progressDescription(): String = when (this) {
    is TrackyStage.Connecting -> "Connecting to scale"
    is TrackyStage.Handshaking -> "Preparing scale"
    is TrackyStage.Measuring -> "Receiving readings"
    is TrackyStage.Stopping -> "Saving measurement"
    else -> "Working"
}

private val UnitDisplay.spokenLabel: String
    get() = when (this) {
        UnitDisplay.KG -> "kilograms"
        UnitDisplay.LB -> "pounds"
    }

private fun formatWeight(grams: Long, unit: UnitDisplay): String {
    val value = when (unit) {
        UnitDisplay.KG -> grams / 1_000.0
        UnitDisplay.LB -> grams / 453.59237
    }
    return NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 2
    }.format(value)
}

private data class ActionSpec(
    val label: String,
    val enabled: Boolean,
    val uiAction: UiAction,
)
