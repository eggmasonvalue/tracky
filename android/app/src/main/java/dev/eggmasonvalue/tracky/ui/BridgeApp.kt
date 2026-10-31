package dev.eggmasonvalue.tracky.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.eggmasonvalue.tracky.ui.model.BridgeAction
import dev.eggmasonvalue.tracky.ui.model.BridgeUiState
import dev.eggmasonvalue.tracky.core.sync.LocalNetworkPermission

@Composable
fun BridgeApp(
    state: BridgeUiState,
    onAction: (BridgeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var endpoint by rememberSaveable(state.endpoint) { mutableStateOf(state.endpoint.orEmpty()) }
    // Bearer tokens must not enter the saved-instance-state bundle.
    var token by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Tracky bridge", style = MaterialTheme.typography.headlineLarge)
        Text(
            "A background projection from the Linux acquisition appliance to Health Connect. " +
                "This app does not scan Bluetooth or start weigh-ins.",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.isRationaleEntry) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    "Tracky reads nothing from Health Connect. It writes Linux-provided weight and, when available and permitted, " +
                        "body fat, lean body mass, body water mass, bone mass and basal metabolic rate. " +
                        "Linux owns the profile and calculations. Tracky never writes height, protein or skeletal muscle.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Health Connect", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        !state.healthConnectAvailable -> "Health Connect is unavailable. Install or update it, then return through its permission screen."
                        state.healthConnectAuthorized -> "Weight write access is granted. Optional composition permissions: ${state.compositionPermissionsGranted}/5."
                        else -> "Only weight write permission is required for sync. Composition write permissions are optional."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Tracky reads nothing from Health Connect. It writes weight and, with each optional permission, " +
                        "Linux-provided body fat, lean body mass, body water mass, bone mass and basal metabolic rate. " +
                        "If Linux cannot derive composition, Tracky removes derived records for types it can write.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { onAction(BridgeAction.RequestHealthPermissions) },
                    enabled = state.healthConnectAvailable,
                ) {
                    Text("Grant Health Connect writes")
                }
            }
        }

        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Local network", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (state.localNetworkAuthorized) "Local network access is granted. Tracky can reach the Linux appliance over HTTPS."
                    else LocalNetworkPermission.DENIED_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!state.localNetworkAuthorized) {
                    Button(onClick = { onAction(BridgeAction.RequestLocalNetworkPermission) }) {
                        Text("Allow local network")
                    }
                    TextButton(onClick = { onAction(BridgeAction.OpenAppSettings) }) {
                        Text("Open app permissions")
                    }
                }
            }
        }

        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Secure Linux endpoint", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Use the HTTPS base URL for the authenticated observation endpoint. Install your private CA certificate " +
                        "in Android settings first; the server certificate must cover the endpoint's IP or hostname. " +
                        "The bearer token is encrypted with Android Keystore and is never shown or logged.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS endpoint") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Bearer token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                state.configurationError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onAction(BridgeAction.SaveConfiguration(endpoint.trim(), token))
                            token = ""
                        },
                        enabled = endpoint.isNotBlank() && token.isNotBlank(),
                    ) {
                        Text("Save and sync")
                    }
                    TextButton(onClick = { onAction(BridgeAction.SyncNow) }, enabled = state.configured) {
                        Text("Sync now")
                    }
                }
            }
        }

        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Background diagnostics", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.diagnosticsMessage ?: "No sync has run yet. WorkManager retries network and Health Connect failures with backoff.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.diagnosticsStatus?.let { Text("Status: $it", style = MaterialTheme.typography.labelLarge) }
                TextButton(onClick = { onAction(BridgeAction.Refresh) }) { Text("Refresh status") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
