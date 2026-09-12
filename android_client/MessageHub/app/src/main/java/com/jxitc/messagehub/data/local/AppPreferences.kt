package com.jxitc.messagehub.data.local

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.jxitc.messagehub.utils.Logger

class AppPreferences(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, 
        Context.MODE_PRIVATE
    )
    
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    /** Shared API key sent as the X-API-Key header for Message Hub requests. */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    /**
     * 用户自定义的设备名（可空）。在 App 的 Settings 里填，用于让多台设备在服务器上一眼可分。
     * 填了就直接当 deviceId 用（业界主流：像 Apple 的 "John's iPhone"、Home Assistant 的实体名）。
     */
    var customDeviceName: String
        get() = prefs.getString(KEY_CUSTOM_DEVICE_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_DEVICE_NAME, value.trim()).apply()
            prefs.edit().remove(KEY_DEVICE_ID).apply()   // 让 deviceId 重新计算
            Logger.i("Custom device name set: '${value.trim()}'")
        }

    /**
     * 本机唯一设备 ID（消息与崩溃上报都用它标 source_device_id）。
     *
     * 三层取名，总是人类可读（业界通行做法）：
     *   1. 用户在 App 里填的自定义名            → `oppo-main`、`s8`
     *   2. 否则用系统设备名(Settings.Global.DEVICE_NAME，用户在系统设置里起的)
     *      + 短码                              → `Alice-S8-a1b2c3`
     *   3. 否则用机型 + 短码                    → `PHZ110-3f2a1c`
     *
     * 短码取 ANDROID_ID 尾 6 位，只为解决"两台同型号/同名"的冲突；
     * ANDROID_ID 在 Android 8+ 按 (应用签名, 用户, 设备) 派生，**卸载重装不变**。
     *
     * 历史教训：这个值曾在 MessageHubApiClient 里硬编码为 "android-phone-1"，
     * 只有一台设备时看不出来，第二台设备一接入就全混到同一个名字下，无法区分来源。
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: generateDeviceId()

    private fun generateDeviceId(): String {
        val custom = customDeviceName.trim()
        val id = if (custom.isNotEmpty()) {
            custom.replace(Regex("[^A-Za-z0-9_.-]"), "-")
        } else {
            val base = systemDeviceName() ?: (Build.MODEL ?: "device")
            val safe = base.replace(Regex("[^A-Za-z0-9_-]"), "-")
            "$safe-${shortSuffix()}"
        }
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        Logger.i("Device ID assigned: $id")
        return id
    }

    /** 用户在系统设置里给这台手机起的名字（API 25+），读不到就返回 null。 */
    private fun systemDeviceName(): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?.trim()?.takeIf { it.isNotEmpty() }
        } else null
    } catch (_: Throwable) {
        null
    }

    /** 短唯一码：ANDROID_ID 尾 6 位，取不到就用随机值。 */
    private fun shortSuffix(): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (_: Throwable) {
            ""
        }
        return if (androidId.length >= 6) androidId.takeLast(6)
        else java.util.UUID.randomUUID().toString().replace("-", "").take(6)
    }
    
    var autoSync: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, DEFAULT_AUTO_SYNC)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()
    
    var syncOnlyOnWifi: Boolean
        get() = prefs.getBoolean(KEY_SYNC_WIFI_ONLY, DEFAULT_SYNC_WIFI_ONLY)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_WIFI_ONLY, value).apply()

    /**
     * 被屏蔽的通知 app 黑名单(packageName, 如 com.tencent.qqmusic)。
     * 只拦新通知, 已存记录保留。
     */
    var blockedApps: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BLOCKED_APPS, value).apply()

    fun isAppBlocked(packageName: String): Boolean = blockedApps.contains(packageName)

    /** 屏蔽某个 app: 加入黑名单(不可变拷贝写入, 避免 SharedPreferences StringSet 共享实例被改) */
    fun addBlockedApp(packageName: String) {
        val updated = blockedApps + packageName
        prefs.edit().putStringSet(KEY_BLOCKED_APPS, updated).apply()
        Logger.i("App blocked from notifications: $packageName")
    }

    /** 取消屏蔽某个 app: 移出黑名单 */
    fun removeBlockedApp(packageName: String) {
        val updated = blockedApps - packageName
        prefs.edit().putStringSet(KEY_BLOCKED_APPS, updated).apply()
        Logger.i("App unblocked from notifications: $packageName")
    }
    
    fun isServerConfigured(): Boolean {
        return serverUrl.isNotBlank() && serverUrl != DEFAULT_SERVER_URL && apiKey.isNotBlank()
    }
    
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        Logger.i("App preferences reset to defaults")
    }
    
    companion object {
        private const val PREFS_NAME = "messagehub_prefs"
        
        // Keys
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_SYNC_WIFI_ONLY = "sync_wifi_only"
        private const val KEY_BLOCKED_APPS = "blocked_apps"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CUSTOM_DEVICE_NAME = "custom_device_name"
        
        // Default values
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000" // Android emulator localhost
        private const val DEFAULT_AUTO_SYNC = true
        private const val DEFAULT_SYNC_WIFI_ONLY = false
    }
}