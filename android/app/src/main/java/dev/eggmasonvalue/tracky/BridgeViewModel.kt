package dev.eggmasonvalue.tracky

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.eggmasonvalue.tracky.core.data.DiagnosticsRepository
import dev.eggmasonvalue.tracky.core.healthconnect.HealthConnectManager
import dev.eggmasonvalue.tracky.core.sync.LocalNetworkPermission
import dev.eggmasonvalue.tracky.core.sync.SecureSyncConfigStore
import dev.eggmasonvalue.tracky.core.sync.SyncEndpointValidator
import dev.eggmasonvalue.tracky.core.sync.SyncScheduler
import dev.eggmasonvalue.tracky.ui.model.BridgeAction
import dev.eggmasonvalue.tracky.ui.model.BridgeUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BridgeViewModel(
    context: Context,
    private val configStore: SecureSyncConfigStore,
    private val healthConnectManager: HealthConnectManager,
    private val diagnostics: DiagnosticsRepository,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val _uiState = MutableStateFlow(BridgeUiState())
    val uiState: StateFlow<BridgeUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<HostEffect>(extraBufferCapacity = 2)
    val effects: SharedFlow<HostEffect> = _effects.asSharedFlow()

    init {
        refresh()
    }

    fun setRationaleEntry(isRationale: Boolean) {
        _uiState.value = _uiState.value.copy(isRationaleEntry = isRationale)
    }

    fun refresh() {
        viewModelScope.launch {
            val available = healthConnectManager.isAvailable()
            val permissions = if (available) healthConnectManager.grantedPermissions() else emptySet()
            val authorized = permissions.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)
            val compositionPermissions = HealthConnectManager.REQUESTED_PERMISSIONS - HealthConnectManager.REQUIRED_PERMISSIONS
            val config = runCatching { configStore.load() }.getOrNull()
            val storedDiagnostics = diagnostics.read()
            _uiState.value = _uiState.value.copy(
                healthConnectAvailable = available,
                healthConnectAuthorized = authorized,
                compositionPermissionsGranted = permissions.intersect(compositionPermissions).size,
                localNetworkAuthorized = LocalNetworkPermission.isGranted(appContext),
                configured = config != null,
                endpoint = config?.endpoint,
                diagnosticsMessage = storedDiagnostics?.message,
                diagnosticsStatus = storedDiagnostics?.status,
            )
        }
    }

    fun onAction(action: BridgeAction) {
        when (action) {
            BridgeAction.RequestHealthPermissions ->
                _effects.tryEmit(HostEffect.RequestHealthPermissions)
            BridgeAction.RequestLocalNetworkPermission ->
                _effects.tryEmit(HostEffect.RequestLocalNetworkPermission)
            BridgeAction.OpenAppSettings ->
                _effects.tryEmit(HostEffect.OpenAppSettings)
            BridgeAction.Refresh -> refresh()
            BridgeAction.SyncNow -> {
                SyncScheduler.triggerImmediateSync(appContext)
                _uiState.value = _uiState.value.copy(
                    diagnosticsStatus = "requested",
                    diagnosticsMessage = "Background sync requested; this screen will not acquire from a scale.",
                )
            }
            is BridgeAction.SaveConfiguration -> saveConfiguration(action.endpoint, action.token)
        }
    }

    private fun saveConfiguration(endpoint: String, token: String) {
        viewModelScope.launch {
            try {
                val config = SyncEndpointValidator.validate(endpoint, token)
                configStore.save(config.endpoint, config.bearerToken)
                _uiState.value = _uiState.value.copy(
                    configured = true,
                    endpoint = config.endpoint,
                    configurationError = null,
                    diagnosticsStatus = "configured",
                    diagnosticsMessage = "Secure sync configuration saved. Token is not displayed or logged.",
                )
                SyncScheduler.triggerImmediateSync(appContext)
            } catch (error: IllegalArgumentException) {
                _uiState.value = _uiState.value.copy(
                    configurationError = error.message ?: "Endpoint or token is invalid",
                )
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(
                    configurationError = "Secure token storage is unavailable on this device",
                )
            }
        }
    }

    sealed interface HostEffect {
        data object RequestHealthPermissions : HostEffect
        data object RequestLocalNetworkPermission : HostEffect
        data object OpenAppSettings : HostEffect
    }

    class Factory(
        private val context: Context,
        private val configStore: SecureSyncConfigStore,
        private val healthConnectManager: HealthConnectManager,
        private val diagnostics: DiagnosticsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BridgeViewModel::class.java))
            return BridgeViewModel(context, configStore, healthConnectManager, diagnostics) as T
        }
    }
}
