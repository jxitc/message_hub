package com.jxitc.messagehub.domain.model

import java.time.LocalDateTime

data class Memory(
    val id: Long = 0,
    val title: String,
    val content: String,
    val sourceType: SourceType,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val isUploaded: Boolean = false,
    val uploadRetryCount: Int = 0,
    /**
     * 服务器给的消息 id（UUID）。本地主键是自增 Long，两者不是一回事。
     * 附件状态要按它去 `GET /api/v1/messages/<id>`，所以必须留在本地。
     */
    val serverMessageId: String? = null,
    /** 服务器上已存下来的附件 + 提取状态（元信息，**不含字节**）。 */
    val attachments: List<ServerAttachment> = emptyList(),
    /** "有附件但没存下来"的文件（超限/类型被拒），要显示名称与原因。 */
    val skippedAttachments: List<SkippedAttachment> = emptyList(),
    /** 附件状态的最后同步时刻（含离线来源）。 */
    val attachmentsSyncedAt: LocalDateTime? = null
) {
    /** 还有附件在非终态（`pending`）→ 该继续轮询。 */
    val hasPendingExtraction: Boolean
        get() = attachments.any { !(it.extraction?.status ?: ExtractionStatus.UNKNOWN).isTerminal }
}

enum class SourceType {
    SMS,
    SCREENSHOT,
    SHARE_INTENT,
    NOTIFICATION,
    MANUAL
}

data class MemoryCreationRequest(
    val content: String,
    val sourceType: SourceType,
    val metadata: Map<String, String> = emptyMap()
)
