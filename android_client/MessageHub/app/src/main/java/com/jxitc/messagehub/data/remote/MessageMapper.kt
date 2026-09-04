package com.jxitc.messagehub.data.remote

import com.jxitc.messagehub.domain.model.SourceType

/**
 * 纯函数：把 Memory 的 sourceType/metadata 映射为 MH 消息的 `type` / `sender`。
 * 与 Android 框架 / Retrofit 无关，便于 JVM 单元测试。
 */
object MessageMapper {

    /** SourceType -> MH message type。MANUAL 在 metadata 有通知特征时归为 PUSH_NOTIFICATION。 */
    fun mapToMessageType(sourceType: SourceType, metadata: Map<String, String>): String {
        return when (sourceType) {
            SourceType.SMS -> "SMS"
            SourceType.NOTIFICATION -> "PUSH_NOTIFICATION"
            SourceType.MANUAL -> {
                val looksLikeNotification = metadata.containsKey("app_name") ||
                    metadata.containsKey("package_name") ||
                    metadata.containsKey("notification_id")
                if (looksLikeNotification) "PUSH_NOTIFICATION" else "SMS"
            }
            // MH MVP only supports SMS/PUSH_NOTIFICATION/CALL_LOG/EMAIL
            SourceType.SCREENSHOT, SourceType.SHARE_INTENT -> "SMS"
        }
    }

    /** Sender = contact_name > phone_number > app_name > package_name > "unknown"。 */
    fun resolveSender(metadata: Map<String, String>): String {
        val candidate = listOf("contact_name", "phone_number", "app_name", "package_name")
            .mapNotNull { metadata[it]?.takeIf { v -> v.isNotBlank() } }
            .firstOrNull()
        return candidate ?: "unknown"
    }
}
