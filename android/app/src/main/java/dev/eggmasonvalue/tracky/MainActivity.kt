package dev.eggmasonvalue.tracky

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import dev.eggmasonvalue.tracky.core.healthconnect.HealthConnectManager
import dev.eggmasonvalue.tracky.core.sync.LocalNetworkPermission
import dev.eggmasonvalue.tracky.core.sync.SyncScheduler
import dev.eggmasonvalue.tracky.ui.BridgeApp
import dev.eggmasonvalue.tracky.ui.theme.TrackyTheme
import kotlinx.coroutines.flow.collect

/** Health Connect rationale/onboarding entry point; it is intentionally not a launcher activity. */
class MainActivity : ComponentActivity() {
    private val viewModel: BridgeViewModel by viewModels {
        val application = application as TrackyApplication
        BridgeViewModel.Factory(
            context = application,
            configStore = application.configStore,
            healthConnectManager = application.healthConnectManager,
            diagnostics = application.diagnostics,
        )
    }

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        viewModel.refresh()
        SyncScheduler.triggerImmediateSync(this)
    }

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refresh()
        if (granted) SyncScheduler.triggerImmediateSync(this)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setRationaleEntry(
            intent?.action in setOf("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", Intent.ACTION_VIEW_PERMISSION_USAGE),
        )
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(viewModel) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        BridgeViewModel.HostEffect.RequestHealthPermissions ->
                            healthPermissionLauncher.launch(HealthConnectManager.REQUESTED_PERMISSIONS)
                        BridgeViewModel.HostEffect.RequestLocalNetworkPermission ->
                            localNetworkPermissionLauncher.launch(LocalNetworkPermission.PERMISSION)
                        BridgeViewModel.HostEffect.OpenAppSettings -> startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
                        )
                    }
                }
            }
            TrackyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BridgeApp(
                        state = state,
                        onAction = viewModel::onAction,
                    )
                }
            }
        }
    }
}
