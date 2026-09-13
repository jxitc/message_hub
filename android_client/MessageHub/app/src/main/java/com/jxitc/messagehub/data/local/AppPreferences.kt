package com.jxitc.messagehub.data.local

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.jxitc.messagehub.domain.service.AttachmentPolicy
import com.jxitc.messagehub.utils.Logger

class AppPreferences(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, 
        Context.MODE_PRIVATE
    )
    
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    /**
     * 实际用于请求的地址。
     *
     * 把**公网**的 `http://` 自动升级成 `https://`：服务器（nginx）对 http 请求回 301 到 https，
     * 而 OkHttp 按 HTTP 标准会把 301 后的 POST **降级成 GET** —— 消息提交因此变成"查列表"，
     * 静默失败，表面上"测试连接"还是通的（/health 是 GET，重定向后照样 200）。
     *
     * 本地/私有地址保持 http 不动，方便连开发机（10.0.2.2 模拟器、192.168.x 局域网）。
     */
    val effectiveServerUrl: String
        get() = normalizeServerUrl(serverUrl)

    /** Shared API key sent as the X-API-Key header for Message Hub requests. */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    /**
     * 本机设备标识（消息与崩溃上报的 source_device_id）。
     *
     * 首次运行自动生成一个可读值（系统设备名/机型 + 短码，如 `Alice-S8-a1b2c3`），
     * 之后**以存储值为准**——用户在 Settings 里看到的就是这个值，直接改它即可。
     * 不再有"自定义名/留空=自动"两层概念：写入的就是生效的。
     *
     * 历史教训：这个值曾在 MessageHubApiClient 里硬编码为 "android-phone-1"，
     * 只有一台设备时看不出来，第二台设备一接入就全混到同一个名字下，无法区分来源。
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: generateAndStoreDeviceId()

    /** 直接设置设备标识（清理非法字符）。空值忽略，避免把标识清成空。 */
    fun setDeviceId(value: String) {
        val clean = value.trim().replace(Regex("[^A-Za-z0-9_.-]"), "-")
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_DEVICE_ID, clean).apply()
        Logger.i("Device ID set to: $clean")
    }

    /**
     * 「手动添加记忆」上传时的 `sender`：**设备名**（`Build.MODEL` 去空格，例如 `PHZ110`）。
     *
     * 这是产品决定：手动记录没有"对端"，用用户名或空值都不对；填机身型号让
     * 服务器/列表一眼看出这条是谁记的。Build.MODEL 取不到时退回用户给手机起的名字。
     */
    val manualSender: String
        get() {
            val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: systemDeviceName()
            return AttachmentPolicy.senderFromDeviceModel(model)
        }

    /** 恢复为自动生成的标识（系统设备名/机型 + 短码）。 */
    fun resetDeviceId(): String {
        prefs.edit().remove(KEY_DEVICE_ID).apply()
        return deviceId
    }

    private fun generateAndStoreDeviceId(): String {
        val base = systemDeviceName() ?: (Build.MODEL ?: "device")
        val safe = base.replace(Regex("[^A-Za-z0-9_-]"), "-")
        val id = "$safe-${shortSuffix()}"
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
    
    /** 本地/私有网段（这些保持 http，其余公网地址自动升级 https） */
    private val PRIVATE_HOST = Regex(
        "^(localhost|127\\.0\\.0\\.1|10\\..*|192\\.168\\..*|172\\.(1[6-9]|2[0-9]|3[01])\\..*)$"
    )

    private fun normalizeServerUrl(raw: String): String {
        val url = raw.trim()
        if (!url.startsWith("http://", ignoreCase = true)) return url
        val host = url.substring("http://".length).substringBefore('/').substringBefore(':')
        return if (PRIVATE_HOST.matches(host)) url else "https://" + url.substring("http://".length)
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
        
        // Default values
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000" // Android emulator localhost
        private const val DEFAULT_AUTO_SYNC = true
        private const val DEFAULT_SYNC_WIFI_ONLY = false
    }
}