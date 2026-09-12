package com.jxitc.messagehub.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.jxitc.messagehub.data.local.AppPreferences
import com.jxitc.messagehub.data.remote.MessageHubApiClient
import com.jxitc.messagehub.data.remote.UpdateChecker
import com.jxitc.messagehub.domain.model.ProcessingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferences: AppPreferences,
    private val apiClient: MessageHubApiClient,
    private val updateChecker: UpdateChecker
) : BaseViewModel() {

    // ---- 应用内自动更新 ----
    private val _currentVersion = MutableStateFlow("")
    val currentVersion: StateFlow<String> = _currentVersion.asStateFlow()

    /** 服务器上发现的新版本；null = 无更新或尚未检查 */
    private val _availableUpdate = MutableStateFlow<UpdateChecker.ReleaseInfo?>(null)
    val availableUpdate: StateFlow<UpdateChecker.ReleaseInfo?> = _availableUpdate.asStateFlow()

    /** 界面提示文案（检查结果 / 下载进度 / 错误） */
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    private val _updateBusy = MutableStateFlow(false)
    val updateBusy: StateFlow<Boolean> = _updateBusy.asStateFlow()

    /** 下载完成、待安装的 APK */
    private var pendingApk: java.io.File? = null

    init {
        val (name, code) = updateChecker.currentVersion()
        _currentVersion.value = "$name ($code)"
    }

    /** 拉 /api/v1/releases/latest-info 并比较版本号 */
    fun checkForUpdate() {
        if (_updateBusy.value) return
        _updateBusy.value = true
        _updateStatus.value = "Checking..."
        viewModelScope.launch(exceptionHandler) {
            when (val result = updateChecker.checkForUpdate()) {
                is ProcessingResult.Success -> {
                    val info = result.data
                    if (info == null) {
                        _availableUpdate.value = null
                        _updateStatus.value = "✅ You're on the latest version"
                    } else {
                        _availableUpdate.value = info
                        _updateStatus.value = "New version ${info.versionName} available (${info.sizeMb} MB)"
                    }
                }
                is ProcessingResult.Error -> _updateStatus.value = "❌ ${result.message}"
                ProcessingResult.Loading -> Unit
            }
            _updateBusy.value = false
        }
    }

    /** 下载 APK，然后拉起系统安装器（用户需在系统界面点"安装"） */
    fun downloadAndInstallUpdate() {
        val info = _availableUpdate.value ?: return
        if (_updateBusy.value) return

        // 未授权"安装未知应用"时先引导授权（Android 8+ 每个来源应用单独授权）
        if (!updateChecker.canInstallPackages()) {
            _updateStatus.value = "请先允许 MessageHub 安装未知应用，然后再次点击"
            updateChecker.openInstallPermissionSettings()
            return
        }

        _updateBusy.value = true
        _updateStatus.value = "Downloading…"
        viewModelScope.launch(exceptionHandler) {
            when (val result = updateChecker.downloadApk(info) { pct ->
                _updateStatus.value = "Downloading… $pct%"
            }) {
                is ProcessingResult.Success -> {
                    val file = result.data
                    pendingApk = file
                    _updateStatus.value = "Downloaded — opening installer…"
                    when (val installed = updateChecker.installApk(file)) {
                        is ProcessingResult.Success -> Unit
                        is ProcessingResult.Error -> {
                            pendingApk = null
                            _updateStatus.value = "❌ ${installed.message}"
                        }
                        ProcessingResult.Loading -> Unit
                    }
                }
                is ProcessingResult.Error -> _updateStatus.value = "❌ ${result.message}"
                ProcessingResult.Loading -> Unit
            }
            _updateBusy.value = false
        }
    }

    /** 下载完成但安装被中断时，重新拉起安装器 */
    fun installDownloadedUpdate() {
        pendingApk?.let { updateChecker.installApk(it) }
    }
    
    private val _serverUrl = MutableStateFlow(preferences.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()
    
    private val _autoSync = MutableStateFlow(preferences.autoSync)
    val autoSync: StateFlow<Boolean> = _autoSync.asStateFlow()
    
    private val _syncOnlyOnWifi = MutableStateFlow(preferences.syncOnlyOnWifi)
    val syncOnlyOnWifi: StateFlow<Boolean> = _syncOnlyOnWifi.asStateFlow()

    private val _blockedApps = MutableStateFlow(preferences.blockedApps)
    val blockedApps: StateFlow<Set<String>> = _blockedApps.asStateFlow()
    
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

    /** 取消屏蔽某个 app（设置页移除） */
    fun removeBlockedApp(packageName: String) {
        preferences.removeBlockedApp(packageName)
        _blockedApps.value = preferences.blockedApps
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