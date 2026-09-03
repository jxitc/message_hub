package com.jxitc.messagehub.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Helper for managing app permissions
 */
object PermissionHelper {
    
    const val REQUEST_SMS_PERMISSIONS = 1001
    const val REQUEST_CONTACTS_PERMISSION = 1002
    
    /**
     * SMS permissions required for SMS monitoring
     */
    val SMS_PERMISSIONS = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS
    )
    
    /**
     * Contacts permission for contact name resolution
     */
    val CONTACTS_PERMISSIONS = arrayOf(
        Manifest.permission.READ_CONTACTS
    )

    /**
     * Notification permission (Android 13+ / API 33+). Without it the system
     * blocks ALL notifications from the app — including the KeepAliveService
     * foreground notification — so it must be requested at first run.
     * Below API 33 the permission does not exist and is treated as granted.
     */
    val NOTIFICATION_PERMISSIONS: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
    
    /**
     * Check if all SMS permissions are granted
     */
    fun hasSmsPermissions(context: Context): Boolean {
        return SMS_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if contacts permission is granted
     */
    fun hasContactsPermission(context: Context): Boolean {
        return CONTACTS_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if all data collection permissions are granted
     */
    fun hasAllDataCollectionPermissions(context: Context): Boolean {
        return hasSmsPermissions(context) && hasContactsPermission(context)
    }
    
    /**
     * Check if notification permission is granted (no-op below API 33)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return NOTIFICATION_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Get list of missing permissions
     */
    fun getMissingPermissions(context: Context): List<String> {
        val allPermissions = SMS_PERMISSIONS + CONTACTS_PERMISSIONS + NOTIFICATION_PERMISSIONS
        return allPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
}