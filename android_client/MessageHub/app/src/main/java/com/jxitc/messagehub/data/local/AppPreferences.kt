package com.jxitc.messagehub.data.local

import android.content.Context
import android.content.SharedPreferences
import com.jxitc.messagehub.utils.Logger

class AppPreferences(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, 
        Context.MODE_PRIVATE
    )
    
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()
    
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
    
    fun isServerConfigured(): Boolean {
        return serverUrl.isNotBlank() && serverUrl != DEFAULT_SERVER_URL
    }
    
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        Logger.i("App preferences reset to defaults")
    }
    
    companion object {
        private const val PREFS_NAME = "messagehub_prefs"
        
        // Keys
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_SYNC_WIFI_ONLY = "sync_wifi_only"
        private const val KEY_BLOCKED_APPS = "blocked_apps"
        
        // Default values
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000" // Android emulator localhost
        private const val DEFAULT_AUTO_SYNC = true
        private const val DEFAULT_SYNC_WIFI_ONLY = false
    }
}