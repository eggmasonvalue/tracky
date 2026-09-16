package dev.eggmasonvalue.tracky

import android.Manifest
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.health.connect.client.PermissionController
import dev.eggmasonvalue.tracky.core.healthconnect.HealthConnectManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eggmasonvalue.tracky.ui.TrackyApp
import dev.eggmasonvalue.tracky.ui.model.UiDestination
import dev.eggmasonvalue.tracky.ui.theme.TrackyTheme
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        val trackyApplication = application as TrackyApplication
        MainViewModel.Factory(applicationContext, trackyApplication.sessionHistoryRepository)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Read the permission gate again rather than trusting a possibly incomplete result map.
        viewModel.onPermissionResult()
    }

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        viewModel.refreshHealthConnectStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val history by viewModel.history.collectAsStateWithLifecycle()
            val selectedSession by viewModel.selectedSession.collectAsStateWithLifecycle()
            val view = LocalView.current

            LaunchedEffect(viewModel, permissionLauncher, view) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        MainViewModel.HostEffect.RequestBluetoothPermissions -> permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                            ),
                        )
                        MainViewModel.HostEffect.RequestHealthPermissions ->
                            healthPermissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                        MainViewModel.HostEffect.DeviceFoundHaptic ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        MainViewModel.HostEffect.MeasuringHaptic ->
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        MainViewModel.HostEffect.SavedHaptic ->
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                }
            }

            PredictiveBackHandler(enabled = uiState.destination != UiDestination.Instrument) { progress ->
                progress.collect { /* Keep the system's predictive gesture as the visual affordance. */ }
                viewModel.navigateBack()
            }

            TrackyTheme {
                TrackyApp(
                    uiState = uiState,
                    history = history,
                    selectedSession = selectedSession,
                    onAction = viewModel::onAction,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
    }

    override fun onStop() {
        viewModel.setForeground(false)
        super.onStop()
    }
}
