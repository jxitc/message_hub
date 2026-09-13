package com.jxitc.messagehub.data.database

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jxitc.messagehub.domain.model.AttachmentExtraction
import com.jxitc.messagehub.domain.model.ExtractionStatus
import com.jxitc.messagehub.domain.model.ServerAttachment
import com.jxitc.messagehub.domain.model.SkippedAttachment

/**
 * 附件元信息在 Room 里的 JSON 编解码（纯 JVM，有单测）。
 *
 * 只存**元信息与提取文本**，不存字节 —— 见 [MemoryEntity] 的注释。
 *
 * 两条硬规则：
 *  1. **解码永不抛异常**：一列坏 JSON 不该让整个列表页崩掉，宁可当"没有附件"；
 *  2. 用**可空字段的 DTO** 做中转，不直接反序列化领域模型 —— Gson 会绕过 Kotlin
 *     构造函数，缺字段时能在非空属性里塞进 null，等到别处用（例如 `mime.startsWith`）
 *     才炸。中转一层把这种情况就地收敛成默认值。
 */
object AttachmentMetadataCodec {

    private val gson = Gson()

    private class AttachmentDto(
        val key: String? = null,
        val name: String? = null,
        val mime: String? = null,
        val size: Long? = null,
        val kind: String? = null,
        val sha256: String? = null,
        val extraction: ExtractionDto? = null
    )

    private class ExtractionDto(
        val status: String? = null,
        val engine: String? = null,
        val chars: Int? = null,
        val text: String? = null,
        val appliedToContent: Boolean? = null,
        val pages: Int? = null,
        val charsPerPage: Double? = null,
        val error: String? = null,
        val note: String? = null
    )

    private class SkippedDto(
        val name: String? = null,
        val size: Long? = null,
        val mime: String? = null,
        val reason: String? = null
    )

    private val attachmentListType = object : TypeToken<List<AttachmentDto>>() {}.type
    private val skippedListType = object : TypeToken<List<SkippedDto>>() {}.type

    fun encodeAttachments(attachments: List<ServerAttachment>): String =
        gson.toJson(attachments.map { it.toDto() })

    fun encodeSkipped(skipped: List<SkippedAttachment>): String =
        gson.toJson(skipped.map { it.toSkippedDto() })

    fun decodeAttachments(json: String?): List<ServerAttachment> {
        val raw = json?.takeIf { it.isNotBlank() } ?: return emptyList()
        val dtos: List<AttachmentDto> = try {
            gson.fromJson<List<AttachmentDto>>(raw, attachmentListType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return dtos.mapNotNull { it.toDomain() }
    }

    fun decodeSkipped(json: String?): List<SkippedAttachment> {
        val raw = json?.takeIf { it.isNotBlank() } ?: return emptyList()
        val dtos: List<SkippedDto> = try {
            gson.fromJson<List<SkippedDto>>(raw, skippedListType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return dtos.map { SkippedAttachment(
            name = it.name?.takeIf { n -> n.isNotBlank() } ?: "未命名文件",
            size = it.size ?: -1L,
            mime = it.mime?.takeIf { m -> m.isNotBlank() },
            reason = it.reason?.takeIf { r -> r.isNotBlank() }
        ) }
    }

    fun decode(attachmentsJson: String?, skippedJson: String?): AttachmentMetadata =
        AttachmentMetadata(decodeAttachments(attachmentsJson), decodeSkipped(skippedJson))

    private fun ServerAttachment.toDto() = AttachmentDto(
        key = key,
        name = name,
        mime = mime,
        size = size,
        kind = kind,
        sha256 = sha256,
        extraction = extraction?.let {
            ExtractionDto(
                status = it.status.wire,
                engine = it.engine,
                chars = it.chars,
                text = it.text,
                appliedToContent = it.appliedToContent,
                pages = it.pages,
                charsPerPage = it.charsPerPage,
                error = it.error,
                note = it.note
            )
        }
    )

    private fun SkippedAttachment.toSkippedDto() = SkippedDto(name, size, mime, reason)

    private fun AttachmentDto.toDomain(): ServerAttachment? {
        val storageKey = key?.trim().orEmpty()
        if (storageKey.isEmpty()) return null
        return ServerAttachment(
            key = storageKey,
            name = name?.takeIf { it.isNotBlank() } ?: storageKey.substringAfterLast('/'),
            mime = mime?.trim().orEmpty(),
            size = size ?: -1L,
            kind = kind?.trim().orEmpty(),
            sha256 = sha256?.takeIf { it.isNotBlank() },
            extraction = extraction?.let {
                AttachmentExtraction(
                    status = ExtractionStatus.fromWire(it.status),
                    engine = it.engine,
                    chars = it.chars,
                    text = it.text,
                    appliedToContent = it.appliedToContent ?: false,
                    pages = it.pages,
                    charsPerPage = it.charsPerPage,
                    error = it.error,
                    note = it.note
                )
            }
        )
    }
}

/** 一条记忆的附件元信息（Room 里是两列 JSON）。 */
data class AttachmentMetadata(
    val attachments: List<ServerAttachment> = emptyList(),
    val skipped: List<SkippedAttachment> = emptyList()
)
