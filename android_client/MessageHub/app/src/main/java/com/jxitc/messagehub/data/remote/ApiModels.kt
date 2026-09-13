package com.jxitc.messagehub.data.remote

import com.google.gson.annotations.SerializedName
import com.jxitc.messagehub.domain.model.AttachmentExtraction
import com.jxitc.messagehub.domain.model.AttachmentLimits
import com.jxitc.messagehub.domain.model.ExtractionStatus
import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.MessageAttachmentDetail
import com.jxitc.messagehub.domain.model.ServerAttachment
import com.jxitc.messagehub.domain.model.SkippedAttachment
import com.jxitc.messagehub.domain.model.SourceType
import com.jxitc.messagehub.domain.service.AttachmentPolicy
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

// ============================================================================
// Message Hub (MH) request models
// POST /api/v1/messages
// ============================================================================
data class MessageCreateRequest(
    @SerializedName("source_device_id")
    val sourceDeviceId: String,
    @SerializedName("type")
    val type: String,               // SMS | PUSH_NOTIFICATION | CALL_LOG | EMAIL
    @SerializedName("sender")
    val sender: String,
    @SerializedName("content")
    val content: String,
    @SerializedName("timestamp")
    val timestamp: String,          // ISO8601, e.g. 2026-08-31T07:00:00Z
    @SerializedName("metadata")
    val metadata: Map<String, Any> = emptyMap()
)

// ============================================================================
// Message Hub (MH) response models
// ============================================================================

/** A single message as returned by the MH server (matches Message.to_dict()). */
data class MessageApiData(
    @SerializedName("id")
    val id: String,
    @SerializedName("source_device")
    val sourceDevice: String,
    @SerializedName("type")
    val type: String,
    @SerializedName("sender")
    val sender: String,
    @SerializedName("content")
    val content: String,
    @SerializedName("timestamp")
    val timestamp: String? = null,
    @SerializedName("received_at")
    val receivedAt: String? = null,
    @SerializedName("metadata")
    val metadata: Map<String, Any> = emptyMap()
)

/** Response of POST /api/v1/messages: {"message": "...", "id": "<uuid>", "data": {...}} */
data class MessageApiResponse(
    @SerializedName("message")
    val message: String? = null,
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("data")
    val data: MessageApiData? = null,
    /**
     * 带附件上传时，**顶层**还有一个 `attachments`（注意：不在 metadata 里 ——
     * 与 `GET /api/v1/messages/<id>` 把附件放在 `metadata.attachments` 不同）。
     * 里面的 `extraction.status` 初始是 `pending`，可以直接拿来填本地状态。
     */
    @SerializedName("attachments")
    val attachments: List<ApiAttachment>? = null,
    /** 服务器按类型拒掉的文件（多文件时其余照常入库）：`[{name, error}]`。 */
    @SerializedName("rejected")
    val rejected: List<ApiRejectedAttachment>? = null
)

/** Response of GET /api/v1/messages: {"messages": [...], "total": N, "page": ..., "per_page": ..., "has_more": ...} */
data class MessageListResponse(
    @SerializedName("messages")
    val messages: List<MessageApiData>,
    @SerializedName("total")
    val total: Int,
    @SerializedName("page")
    val page: Int? = null,
    @SerializedName("per_page")
    val perPage: Int? = null,
    @SerializedName("has_more")
    val hasMore: Boolean? = null
)

/**
 * Response of GET /api/v1/attachments/limits:
 * `{"max_bytes": 1048576, "allowed": [...], "note": "..."}`
 *
 * 客户端从这里读上限与允许类型，不写死；字段缺失/非法时用契约里的兜底值。
 */
data class AttachmentLimitsResponse(
    @SerializedName("max_bytes")
    val maxBytes: Long = 0L,
    @SerializedName("allowed")
    val allowed: List<String> = emptyList(),
    @SerializedName("note")
    val note: String? = null
)

fun AttachmentLimitsResponse.toDomain(): AttachmentLimits = AttachmentLimits(
    maxBytes = if (maxBytes > 0L) maxBytes else AttachmentPolicy.DEFAULT_MAX_BYTES,
    allowedMimeTypes = allowed.mapNotNull { it.trim().takeIf { mime -> mime.isNotEmpty() } }
        .ifEmpty { AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES }
)

// ============================================================================
// Attachments (frozen contract — see docs/attachments.md on the server side)
// ============================================================================

/**
 * `metadata.attachments[i]` 与上传响应顶层 `attachments[i]` 的同一个形态。
 *
 * ⚠️ **故意不声明 `url` 字段**：那个地址指向 blob 独立源（`https://mhblob.jxitc.com/...`），
 * 是给浏览器 `<img>` 用的 —— 浏览器的图片请求带不了自定义 header，而我们客户端的
 * `X-API-Key` 拦截器只对 API 域名生效，直连那个源会 401。
 * 下载地址一律由 `key` + 配置的 serverUrl 拼（[AttachmentUrls]）；不解析 `url`，
 * 就不会有人误用它。
 */
data class ApiAttachment(
    @SerializedName("key")
    val key: String? = null,
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("mime")
    val mime: String? = null,
    @SerializedName("size")
    val size: Long? = null,
    @SerializedName("kind")
    val kind: String? = null,
    @SerializedName("sha256")
    val sha256: String? = null,
    @SerializedName("extraction")
    val extraction: ApiAttachmentExtraction? = null
)

/** `attachment.extraction`：status ∈ pending|done|empty|failed|unavailable|skipped。 */
data class ApiAttachmentExtraction(
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("engine")
    val engine: String? = null,
    @SerializedName("chars")
    val chars: Int? = null,
    /** 仅当文本**没有**写进消息正文时才出现（见契约）。 */
    @SerializedName("text")
    val text: String? = null,
    @SerializedName("applied_to_content")
    val appliedToContent: Boolean = false,
    @SerializedName("pages")
    val pages: Int? = null,
    @SerializedName("chars_per_page")
    val charsPerPage: Double? = null,
    @SerializedName("error")
    val error: String? = null,
    @SerializedName("note")
    val note: String? = null
)

/** `metadata.attachments_skipped[i]`：邮件里有、但没存下来的文件（不要当附件下载）。 */
data class ApiSkippedAttachment(
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("size")
    val size: Long? = null,
    @SerializedName("mime")
    val mime: String? = null,
    @SerializedName("reason")
    val reason: String? = null
)

/** 上传响应里的 `rejected[i]`（服务器按类型拒掉的文件）：`{name, error}`。 */
data class ApiRejectedAttachment(
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("error")
    val error: String? = null
)

/** `GET /api/v1/messages/<id>`：附件在 `metadata.attachments` 里（不在顶层）。 */
data class MessageDetailApiData(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("content")
    val content: String = "",
    @SerializedName("metadata")
    val metadata: MessageDetailMetadata? = null
)

/**
 * 消息 metadata 里我们关心的两样。Gson 会忽略其余键 ——
 * 服务端 metadata 是"渠道相关事实的默认去处"，客户端不该把它全抄一遍。
 */
data class MessageDetailMetadata(
    @SerializedName("attachments")
    val attachments: List<ApiAttachment>? = null,
    @SerializedName("attachments_skipped")
    val skipped: List<ApiSkippedAttachment>? = null
)

fun ApiAttachmentExtraction.toDomain(): AttachmentExtraction = AttachmentExtraction(
    status = ExtractionStatus.fromWire(status),
    engine = engine?.takeIf { it.isNotBlank() },
    chars = chars,
    text = text?.takeIf { it.isNotBlank() },
    appliedToContent = appliedToContent,
    pages = pages,
    charsPerPage = charsPerPage,
    error = error?.takeIf { it.isNotBlank() },
    note = note?.takeIf { it.isNotBlank() }
)

/**
 * 一个附件 → 领域模型；**没有 key 的直接丢掉**（没有 key 就没法下载，留着只会是个死条目）。
 * name 缺失时用 key 的最后一段兜底。
 */
fun ApiAttachment.toDomain(): ServerAttachment? {
    val storageKey = key?.trim().orEmpty()
    if (storageKey.isEmpty()) return null
    return ServerAttachment(
        key = storageKey,
        name = name?.takeIf { it.isNotBlank() }
            ?: storageKey.substringAfterLast('/').ifBlank { "附件" },
        mime = mime?.trim().orEmpty(),
        size = size ?: -1L,
        kind = kind?.trim().orEmpty(),
        sha256 = sha256?.takeIf { it.isNotBlank() },
        extraction = extraction?.toDomain()
    )
}

fun ApiSkippedAttachment.toDomain(): SkippedAttachment = SkippedAttachment(
    name = name?.takeIf { it.isNotBlank() } ?: "未命名文件",
    size = size ?: -1L,
    mime = mime?.takeIf { it.isNotBlank() },
    reason = reason?.takeIf { it.isNotBlank() }
)

fun ApiRejectedAttachment.toDomain(): SkippedAttachment = SkippedAttachment(
    name = name?.takeIf { it.isNotBlank() } ?: "未命名文件",
    reason = error?.takeIf { it.isNotBlank() }
)

fun MessageDetailApiData.toDomain(): MessageAttachmentDetail = MessageAttachmentDetail(
    serverMessageId = id,
    content = content,
    attachments = (metadata?.attachments ?: emptyList()).mapNotNull { it.toDomain() },
    skipped = (metadata?.skipped ?: emptyList()).map { it.toDomain() }
)

// ============================================================================
// Conversion helpers (server message -> local domain Memory)
// ============================================================================
fun MessageApiData.toDomainModel(): Memory {
    return Memory(
        id = id.toLongOrNull() ?: 0L, // MH uses UUID strings; local ids are Long
        title = sender.ifBlank { type },
        content = content,
        sourceType = type.toSourceType(),
        metadata = metadata.mapValues { it.value.toString() },
        createdAt = parseIsoTimestamp(timestamp),
        updatedAt = parseIsoTimestamp(receivedAt),
        isUploaded = true, // Already on server
        // 服务器的 UUID 留着：附件状态要按它去 GET /api/v1/messages/<id>。
        serverMessageId = id.takeIf { it.isNotBlank() }
    )
}

private fun String.toSourceType(): SourceType {
    return when (this) {
        "SMS" -> SourceType.SMS
        "PUSH_NOTIFICATION" -> SourceType.NOTIFICATION
        // NOTE（手动记的文本/图片）、DOCUMENT（上传的文件）与 CALL_LOG/EMAIL 一样，
        // 本地没有更贴切的类别，都归到 MANUAL。
        "NOTE", "DOCUMENT", "CALL_LOG", "EMAIL" -> SourceType.MANUAL
        else -> SourceType.MANUAL
    }
}

private fun parseIsoTimestamp(raw: String?): LocalDateTime {
    if (raw.isNullOrBlank()) return LocalDateTime.now()
    return try {
        when {
            raw.endsWith("Z") -> LocalDateTime.ofInstant(Instant.parse(raw), ZoneId.systemDefault())
            else -> OffsetDateTime.parse(raw).toLocalDateTime()
        }
    } catch (e: Exception) {
        LocalDateTime.now()
    }
}
