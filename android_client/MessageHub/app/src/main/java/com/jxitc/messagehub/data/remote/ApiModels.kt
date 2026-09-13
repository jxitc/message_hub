package com.jxitc.messagehub.data.remote

import com.google.gson.annotations.SerializedName
import com.jxitc.messagehub.domain.model.AttachmentLimits
import com.jxitc.messagehub.domain.model.Memory
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
    val data: MessageApiData? = null
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
        isUploaded = true // Already on server
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
