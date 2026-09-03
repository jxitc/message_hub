package com.jxitc.infoagent.domain.service

import android.service.notification.StatusBarNotification
import com.jxitc.infoagent.domain.usecase.ProcessNotificationUseCase
import com.jxitc.infoagent.utils.Logger

/**
 * Service for processing notifications from the NotificationListenerService
 * Coordinates notification parsing and memory creation
 *
 * NO FILTERING: All notifications are processed to preserve complete information history
 */
class NotificationProcessor(
    private val processNotificationUseCase: ProcessNotificationUseCase
) {

    /**
     * 连续去重缓存: 全局"上一条" (packageName, title, content)
     * 规则: 与**紧邻的上一条(任何 app)** 原始内容完全一致的通知只记第一条(严格相邻)。
     * 例: A A A → 1 条; A B A → 3 条 (B 打断后 A 重新开始, 不能被跨来源去重掉)。
     * 进程重启缓存清空 → 最多多记一条, 可接受。
     */
    private var lastNotification: Triple<String, String, String>? = null

    suspend fun processNotification(sbn: StatusBarNotification) {
        try {
            Logger.d("NotificationProcessor", "Processing notification: ${sbn.packageName} - ${sbn.notification.extras.getString("android.title")}")

            // Extract notification data
            val packageName = sbn.packageName
            val notification = sbn.notification
            val postTime = sbn.postTime
            val notificationId = sbn.id

            // Extract notification content
            val title = notification.extras.getString("android.title") ?: ""
            val text = notification.extras.getCharSequence("android.text")?.toString() ?: ""
            val bigText = notification.extras.getCharSequence("android.bigText")?.toString()

            // Use bigText if available (longer content), otherwise use text
            val content = bigText ?: text

            // Validate notification content
            if (title.isBlank() && content.isBlank()) {
                Logger.w("NotificationProcessor", "Empty notification content, skipping")
                return
            }

            // 连续去重: 与全局"上一条"原始 title/content 完全一致 → 丢弃, 不更新缓存
            // 用原始字段比较(不含格式化拼进去的 "Time:" 时间戳, 否则永远不相等, 去重失效)
            val last = lastNotification
            if (last != null && last.first == packageName && last.second == title && last.third == content) {
                Logger.d("NotificationProcessor", "Duplicate consecutive notification from $packageName, skipping")
                return
            }
            lastNotification = Triple(packageName, title, content)

            // Process the notification using the use case
            val result = processNotificationUseCase.processNotification(
                packageName = packageName,
                title = title,
                content = content,
                timestamp = postTime,
                notificationId = notificationId
            )

            when (result) {
                is com.jxitc.infoagent.domain.model.NotificationProcessingResult.Success -> {
                    Logger.i("NotificationProcessor", "Notification processed successfully: ${result.message}")
                }
                is com.jxitc.infoagent.domain.model.NotificationProcessingResult.Failed -> {
                    Logger.e("NotificationProcessor", "Notification processing failed: ${result.error}", result.exception)
                }
            }

        } catch (e: Exception) {
            Logger.e("NotificationProcessor", "Unexpected error in notification processor", e)
        }
    }
}
