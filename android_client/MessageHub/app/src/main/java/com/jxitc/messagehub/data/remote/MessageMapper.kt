package com.jxitc.messagehub.data.remote

import com.jxitc.messagehub.domain.model.SourceType
import com.jxitc.messagehub.domain.service.AttachmentPolicy
import java.time.Instant

/**
 * 纯函数：把 Memory 的 sourceType/metadata 映射为 MH 消息的 `type` / `sender`，
 * 以及契约要求的 ISO8601（Z 结尾）时间戳。
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
                if (looksLikeNotification) "PUSH_NOTIFICATION" else AttachmentPolicy.TYPE_NOTE
            }
            // MH MVP only supports SMS/PUSH_NOTIFICATION/CALL_LOG/EMAIL
            SourceType.SCREENSHOT, SourceType.SHARE_INTENT -> "SMS"
        }
    }

    /**
     * 「手动添加记忆」的 type：**带了非图片附件（PDF/文本等）→ DOCUMENT，否则 NOTE**。
     * 与契约里的语义一致（NOTE = 手动记的文本/图片，DOCUMENT = 上传的文件）。
     */
    fun mapManualType(attachmentMimeTypes: List<String>): String =
        AttachmentPolicy.typeForMimeTypes(attachmentMimeTypes)

    /** Sender = contact_name > phone_number > app_name > package_name > "unknown"。 */
    fun resolveSender(metadata: Map<String, String>): String {
        val candidate = listOf("contact_name", "phone_number", "app_name", "package_name")
            .mapNotNull { metadata[it]?.takeIf { v -> v.isNotBlank() } }
            .firstOrNull()
        return candidate ?: "unknown"
    }

    /**
     * sender 的取值规则：**手动添加（NOTE/DOCUMENT）用设备名**，其余按 [resolveSender]。
     *
     * 手动记录没有"对端"，填 `unknown` 或用户名都不对 —— 产品决定是机身型号
     * （`Build.MODEL` 去空格，例 `PHZ110`），这样服务器上一眼能看出是谁记的。
     * [deviceSender] 由调用方从 AppPreferences.manualSender 传进来，本函数保持纯函数。
     */
    fun resolveSenderFor(
        type: String,
        metadata: Map<String, String>,
        deviceSender: String
    ): String = when (type) {
        AttachmentPolicy.TYPE_NOTE, AttachmentPolicy.TYPE_DOCUMENT -> deviceSender
        else -> resolveSender(metadata)
    }

    /**
     * 契约要求的时间戳：ISO8601、UTC、以 `Z` 结尾（例 `2026-08-31T07:00:00Z`）。
     *
     * [epochMillis] 是事件发生时刻（metadata["timestamp"]，SMS/通知处理器会填）；
     * 为空（例如手动添加）就用 [fallback]（默认此刻）。
     */
    fun isoTimestampUtc(epochMillis: Long?, fallback: Instant = Instant.now()): String {
        val instant = epochMillis?.let { Instant.ofEpochMilli(it) } ?: fallback
        return instant.toString()
    }
}
