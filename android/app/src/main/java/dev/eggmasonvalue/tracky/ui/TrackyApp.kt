package dev.eggmasonvalue.tracky.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.eggmasonvalue.tracky.ui.model.RecentSessionUi
import dev.eggmasonvalue.tracky.ui.model.SessionDetailUi
import dev.eggmasonvalue.tracky.ui.model.TrackyUiState
import dev.eggmasonvalue.tracky.ui.model.UiAction
import dev.eggmasonvalue.tracky.ui.model.UiDestination
import dev.eggmasonvalue.tracky.ui.screens.HistoryScreen
import dev.eggmasonvalue.tracky.ui.screens.InstrumentScreen
import dev.eggmasonvalue.tracky.ui.screens.SessionDetailScreen

/**
 * Stateless presentation root. The host responds to [UiAction] and supplies the resulting state;
 * no permission, navigation, storage, protocol, or Bluetooth work is performed here.
 */
@Composable
fun TrackyApp(
    uiState: TrackyUiState,
    history: List<RecentSessionUi>,
    selectedSession: SessionDetailUi?,
    onAction: (UiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState.destination) {
        UiDestination.Instrument -> InstrumentScreen(
            uiState = uiState,
            onAction = onAction,
            modifier = modifier,
        )
        UiDestination.History -> HistoryScreen(
            history = history,
            unitDisplay = uiState.unitDisplay,
            onAction = onAction,
            modifier = modifier,
            uiState = uiState,
        )
        UiDestination.SessionDetail -> SessionDetailScreen(
            detail = selectedSession,
            unitDisplay = uiState.unitDisplay,
            onAction = onAction,
            modifier = modifier,
        )
    }
}
