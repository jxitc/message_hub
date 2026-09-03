package com.jxitc.infoagent.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.jxitc.infoagent.data.local.AppPreferences
import com.jxitc.infoagent.data.remote.InfoAgentApiClient
import com.jxitc.infoagent.domain.model.ProcessingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferences: AppPreferences,
    private val apiClient: InfoAgentApiClient
) : BaseViewModel() {
    
    private val _serverUrl = MutableStateFlow(preferences.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()
    
    private val _autoSync = MutableStateFlow(preferences.autoSync)
    val autoSync: StateFlow<Boolean> = _autoSync.asStateFlow()
    
    private val _syncOnlyOnWifi = MutableStateFlow(preferences.syncOnlyOnWifi)
    val syncOnlyOnWifi: StateFlow<Boolean> = _syncOnlyOnWifi.asStateFlow()
    
    private val _healthCheckResult = MutableStateFlow<String?>(null)
    val healthCheckResult: StateFlow<String?> = _healthCheckResult.asStateFlow()
    
    fun updateServerUrl(newUrl: String) {
        _serverUrl.value = newUrl
        preferences.serverUrl = newUrl
        clearHealthCheckResult()
    }
    
    fun updateAutoSync(enabled: Boolean) {
        _autoSync.value = enabled
        preferences.autoSync = enabled
    }
    
    fun updateSyncOnlyOnWifi(enabled: Boolean) {
        _syncOnlyOnWifi.value = enabled
        preferences.syncOnlyOnWifi = enabled
    }
    
    fun testConnection() {
        clearError()
        clearHealthCheckResult()
        
        if (_serverUrl.value.isBlank()) {
            _healthCheckResult.value = "❌ Please enter a server URL first"
            return
        }
        
        if (!isValidUrl(_serverUrl.value)) {
            _healthCheckResult.value = "❌ Invalid URL format. Use http://... or https://..."
            return
        }
        
        launchWithLoading(
            block = { apiClient.healthCheck() },
            onSuccess = { isHealthy ->
                if (isHealthy) {
                    _healthCheckResult.value = "✅ Server connection successful!"
                } else {
                    _healthCheckResult.value = "❌ Server responded but health check failed"
                }
            },
            onError = { error ->
                _healthCheckResult.value = "❌ Connection failed: $error"
            }
        )
    }
    
    private fun clearHealthCheckResult() {
        _healthCheckResult.value = null
    }
    
    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }
    
    fun resetToDefaults() {
        preferences.resetToDefaults()
        _serverUrl.value = preferences.serverUrl
        _autoSync.value = preferences.autoSync
        _syncOnlyOnWifi.value = preferences.syncOnlyOnWifi
        clearHealthCheckResult()
        clearError()
    }
}