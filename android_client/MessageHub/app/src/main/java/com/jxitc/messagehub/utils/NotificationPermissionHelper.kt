package com.jxitc.messagehub.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.jxitc.messagehub.service.NotificationListener

/**
 * Utility for checking and requesting notification listener permissions
 * Required for NotificationListenerService to function
 */
object NotificationPermissionHelper {

    // Track service connection state
    @Volatile
    private var isServiceConnected = false

    @Volatile
    private var lastDisconnectTime: Long = 0

    /**
     * Called by NotificationListener when service connects
     */
    fun onServiceConnected() {
        isServiceConnected = true
        android.util.Log.i("NotificationPermissionHelper", "✅ Service marked as connected - isServiceConnected=$isServiceConnected")
        Logger.i("NotificationPermissionHelper", "Service marked as connected")
    }

    /**
     * Called by NotificationListener when service disconnects
     */
    fun onServiceDisconnected() {
        isServiceConnected = false
        lastDisconnectTime = System.currentTimeMillis()
        android.util.Log.w("NotificationPermissionHelper", "⚠️ Service marked as disconnected - isServiceConnected=$isServiceConnected")
        Logger.w("NotificationPermissionHelper", "Service marked as disconnected")
    }

    /**
     * Check if the app has notification listener permission enabled
     */
    fun isNotificationAccessEnabled(context: Context): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )

        if (flat != null && flat.isNotEmpty()) {
            val names = flat.split(":").toTypedArray()
            for (name in names) {
                val componentName = ComponentName.unflattenFromString(name)
                if (componentName != null) {
                    if (packageName == componentName.packageName) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Alternative way to check using NotificationManagerCompat
     */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val packageName = context.packageName
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledListeners.contains(packageName)
    }

    /**
     * Open notification listener settings page for user to enable the service
     */
    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Get a user-friendly explanation text for why notification access is needed
     */
    fun getPermissionExplanation(): String {
        return """
            MessageHub needs notification access to:
            • Capture all notifications as memories
            • Preserve complete information history
            • Sync notifications to your MessageHub server

            NO FILTERING: All notifications will be captured.

            To enable:
            1. Tap "Open Settings" below
            2. Find "MessageHub" in the list
            3. Toggle the switch to ON
        """.trimIndent()
    }

    /**
     * Check if permission was recently granted
     * Useful for showing confirmation after user returns from settings
     */
    fun hasPermissionChanged(context: Context, wasEnabled: Boolean): Boolean {
        val isNowEnabled = isNotificationAccessEnabled(context)
        return isNowEnabled != wasEnabled
    }

    /**
     * Check if the notification service is actually active and bound
     * This checks both permission AND actual service connection state
     *
     * IMPORTANT: Permission can show "granted" in Settings but service can still be disconnected!
     * This function detects the actual service state.
     *
     * WORKAROUND: If we haven't seen a disconnect recently and permission is granted,
     * assume service is active. This handles app restart where service is already bound
     * but onListenerConnected() hasn't been called yet.
     */
    fun isNotificationServiceActive(context: Context): Boolean {
        val hasPermission = isNotificationAccessEnabled(context)

        // If permission is granted and service is explicitly connected, it's active
        if (hasPermission && isServiceConnected) {
            android.util.Log.d(
                "NotificationPermissionHelper",
                "🔍 isNotificationServiceActive: TRUE (connected explicitly)"
            )
            return true
        }

        // If we've recently seen a disconnect, service is definitely not active
        if (wasRecentlyDisconnected()) {
            android.util.Log.d(
                "NotificationPermissionHelper",
                "🔍 isNotificationServiceActive: FALSE (recently disconnected)"
            )
            return false
        }

        // If permission is granted and we haven't seen any disconnect, assume it's active
        // This handles app restart where service is already bound
        val assumeActive = hasPermission
        android.util.Log.d(
            "NotificationPermissionHelper",
            "🔍 isNotificationServiceActive: $assumeActive (assuming based on permission, no disconnect seen)"
        )
        return assumeActive
    }

    /**
     * Check if service was recently disconnected (within last 5 minutes)
     * Useful for showing alerts to re-enable the service
     */
    fun wasRecentlyDisconnected(): Boolean {
        if (lastDisconnectTime == 0L) return false
        val timeSinceDisconnect = System.currentTimeMillis() - lastDisconnectTime
        return timeSinceDisconnect < 5 * 60 * 1000 // 5 minutes
    }

    /**
     * Get status message for debugging
     */
    fun getServiceStatus(context: Context): String {
        val hasPermission = isNotificationAccessEnabled(context)
        return """
            Permission Granted: $hasPermission
            Service Connected: $isServiceConnected
            Last Disconnect: ${if (lastDisconnectTime > 0) java.text.SimpleDateFormat("HH:mm:ss").format(lastDisconnectTime) else "Never"}
        """.trimIndent()
    }

    /**
     * Request service rebind programmatically (requires Android 7.0+)
     * Note: This may not work on OPPO/ColorOS devices due to aggressive power management
     */
    fun requestServiceRebind(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                val componentName = ComponentName(context, NotificationListener::class.java)
                NotificationListenerService.requestRebind(componentName)
                Logger.i("NotificationPermissionHelper", "Requested service rebind")
            } catch (e: Exception) {
                Logger.e("NotificationPermissionHelper", "Failed to request rebind", e)
            }
        }
    }
}
