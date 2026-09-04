package com.jxitc.messagehub.domain.usecase

import android.content.pm.PackageManager
import com.jxitc.messagehub.domain.model.MemoryCreationRequest
import com.jxitc.messagehub.domain.model.NotificationMessage
import com.jxitc.messagehub.domain.model.NotificationProcessingResult
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.model.SourceType
import com.jxitc.messagehub.domain.repository.MemoryRepository
import com.jxitc.messagehub.domain.service.MemorySyncService
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Use case for processing notifications into memories
 * Handles app name resolution and memory creation
 * NO FILTERING: All notifications are captured to preserve complete information history
 */
class ProcessNotificationUseCase(
    private val memoryRepository: MemoryRepository,
    private val packageManager: PackageManager,
    private val syncService: MemorySyncService
) {
    private val syncScope = CoroutineScope(Dispatchers.IO)

    suspend fun processNotification(
        packageName: String,
        title: String,
        content: String,
        timestamp: Long,
        notificationId: Int
    ): NotificationProcessingResult {
        try {
            Logger.d("ProcessNotificationUseCase", "Processing notification from $packageName")
            android.util.Log.d("ProcessNotificationUseCase", "Processing notification - title: $title, package: $packageName")

            // Resolve app name
            val appName = resolveAppName(packageName)
            val displayName = appName ?: packageName

            // Create notification message object
            val notificationMessage = NotificationMessage(
                packageName = packageName,
                appName = appName,
                title = title,
                content = content,
                timestamp = timestamp,
                notificationId = notificationId
            )

            // Format notification content for memory
            val formattedContent = formatNotificationAsMemory(notificationMessage)

            // Create memory request
            val request = MemoryCreationRequest(
                content = formattedContent,
                sourceType = SourceType.NOTIFICATION,
                metadata = mapOf(
                    "source" to "app",
                    "package_name" to packageName,
                    "app_name" to (appName ?: ""),
                    "notification_id" to notificationId.toString(),
                    "title" to title,
                    "timestamp" to timestamp.toString()
                )
            )

            when (val result = memoryRepository.createMemory(request)) {
                is ProcessingResult.Success -> {
                    Logger.d("ProcessNotificationUseCase", "Notification processed successfully, memory ID: ${result.data.id}")

                    // Trigger auto-sync in background after successful save
                    syncScope.launch {
                        try {
                            Logger.d("ProcessNotificationUseCase", "Triggering auto-sync for notification memory ${result.data.id}")
                            syncService.syncPendingMemories()
                        } catch (e: Exception) {
                            Logger.e("ProcessNotificationUseCase", "Auto-sync failed, will retry later", e)
                            // Don't fail the notification processing if sync fails
                            // Memory is already saved locally and will sync later
                        }
                    }

                    return NotificationProcessingResult.Success(
                        result.data.id,
                        "Notification from $displayName saved as memory"
                    )
                }
                is ProcessingResult.Error -> {
                    Logger.e("ProcessNotificationUseCase", "Failed to create memory: ${result.message}")
                    return NotificationProcessingResult.Failed("Failed to save notification: ${result.message}", result.throwable)
                }
                is ProcessingResult.Loading -> {
                    // This shouldn't happen for a synchronous operation
                    return NotificationProcessingResult.Failed("Unexpected loading state", null)
                }
            }

        } catch (e: Exception) {
            Logger.e("ProcessNotificationUseCase", "Error processing notification", e)
            return NotificationProcessingResult.Failed("Unexpected error processing notification", e)
        }
    }

    private fun resolveAppName(packageName: String): String? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Logger.e("ProcessNotificationUseCase", "Error resolving app name for $packageName", e)
            null
        }
    }

    /** 干净正文：优先 title 行 + 正文行；不带 emoji/标签/时间；结构化信息走 metadata。 */
    private fun formatNotificationAsMemory(notification: NotificationMessage): String {
        val title = notification.title.trim()
        val body = notification.content.trim()
        return when {
            title.isNotEmpty() && body.isNotEmpty() -> "$title\n$body"
            title.isNotEmpty() -> title
            else -> body
        }
    }
}
