package com.jxitc.infoagent.domain.model

/**
 * Domain model for notification data
 */
data class NotificationMessage(
    val packageName: String,
    val appName: String? = null,
    val title: String,
    val content: String,
    val timestamp: Long,
    val notificationId: Int
)

/**
 * Result of notification processing operation
 * NO FILTERING: All notifications are captured, so no Filtered case needed
 */
sealed class NotificationProcessingResult {
    data class Success(val memoryId: Long, val message: String) : NotificationProcessingResult()
    data class Failed(val error: String, val exception: Throwable? = null) : NotificationProcessingResult()
}
