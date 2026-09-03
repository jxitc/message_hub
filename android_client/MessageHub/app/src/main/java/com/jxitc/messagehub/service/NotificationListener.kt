package com.jxitc.messagehub.service

import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jxitc.messagehub.MessageHubApplication
import com.jxitc.messagehub.utils.Logger
import com.jxitc.messagehub.utils.NotificationPermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * NotificationListenerService for intercepting all system notifications
 * Processes notification content and stores it as memories in MessageHub
 *
 * NO FILTERING: All notifications are captured to preserve complete information history
 */
class NotificationListener : NotificationListenerService() {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Log notification received
        android.util.Log.d("NotificationListener", "========== NOTIFICATION POSTED ==========")
        android.util.Log.d("NotificationListener", "Package: ${sbn?.packageName}")
        android.util.Log.d("NotificationListener", "ID: ${sbn?.id}")
        android.util.Log.d("NotificationListener", "PostTime: ${sbn?.postTime}")

        Logger.d("NotificationListener", "onNotificationPosted: ${sbn?.packageName}")

        if (sbn != null) {
            // Filter out our own app's notifications to avoid infinite loop
            if (sbn.packageName == packageName) {
                Logger.d("NotificationListener", "Ignoring own app notification")
                return
            }

            try {
                val appContainer = (applicationContext as MessageHubApplication).appContainer

                // 入口查黑名单: 被屏蔽 app 的新通知直接丢弃, 不处理
                if (appContainer.appPreferences.isAppBlocked(sbn.packageName)) {
                    android.util.Log.d("NotificationListener", "Ignoring notification from blocked app: ${sbn.packageName}")
                    Logger.d("NotificationListener", "Ignoring notification from blocked app: ${sbn.packageName}")
                    return
                }

                coroutineScope.launch {
                    try {
                        // Get notification processor from app container
                        appContainer.notificationProcessor.processNotification(sbn)
                        android.util.Log.d("NotificationListener", "Notification processing launched for: ${sbn.packageName}")
                    } catch (e: Exception) {
                        android.util.Log.e("NotificationListener", "Error processing notification from ${sbn.packageName}", e)
                        Logger.e("NotificationListener", "Error processing notification", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NotificationListener", "Error in notification listener", e)
                Logger.e("NotificationListener", "Error in notification listener", e)
            }
        } else {
            android.util.Log.w("NotificationListener", "Received null StatusBarNotification")
            Logger.w("NotificationListener", "Received null StatusBarNotification")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: Log when notifications are dismissed
        Logger.d("NotificationListener", "Notification removed: ${sbn?.packageName}")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        android.util.Log.i("NotificationListener", "NotificationListener connected")
        Logger.i("NotificationListener", "NotificationListener service connected")

        // Track service connection state
        NotificationPermissionHelper.onServiceConnected()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        android.util.Log.w("NotificationListener", "NotificationListener disconnected - attempting rebind")
        Logger.w("NotificationListener", "NotificationListener service disconnected")

        // Track service disconnection state
        NotificationPermissionHelper.onServiceDisconnected()

        // Attempt automatic rebind (Android 7.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val componentName = ComponentName(this, NotificationListener::class.java)
                requestRebind(componentName)
                android.util.Log.i("NotificationListener", "Requested service rebind")
            } catch (e: Exception) {
                android.util.Log.e("NotificationListener", "Failed to request rebind", e)
                Logger.e("NotificationListener", "Failed to request rebind", e)
            }
        }

        // Note: On OPPO/ColorOS devices, auto-rebind may not work due to aggressive power management
        // Users will need to manually toggle the notification access setting
    }
}
