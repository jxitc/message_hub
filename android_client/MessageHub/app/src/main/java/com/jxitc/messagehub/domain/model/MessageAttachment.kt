package com.jxitc.messagehub.domain.model

import com.jxitc.messagehub.domain.service.AttachmentPolicy

/**
 * 服务端附件与提取状态（**冻结契约**的另一半，客户端只读）。
 *
 * 服务端把"这个附件是什么"写进消息 metadata，把字节放在 blob 里，靠内容派生的 `key` 关联。
 * 客户端因此只保存元信息 + 提取出的文本，**永不保存字节**（图片按需拉，靠图片库磁盘缓存）。
 */
enum class ExtractionStatus(val wire: String) {
    /** 后台提取还没轮到它 —— 唯一的非终态。 */
    PENDING("pending"),
    DONE("done"),
    EMPTY("empty"),
    FAILED("failed"),

    /** 服务器上没装对应引擎（例如缺 tesseract）：不是失败，但也不会有文本。 */
    UNAVAILABLE("unavailable"),

    /** 该类型本就不做提取（图片/PDF/文本以外的类型）。 */
    SKIPPED("skipped"),

    /** 契约里没见过的状态。 */
    UNKNOWN("");

    /**
     * 是否终态（**非 `pending` 即终态**，见契约）。
     *
     * [UNKNOWN] 也算终态：没见过的新状态不该让客户端永远轮询下去 —— 宁可停下、
     * 让用户手动刷新，也不要无休止地打服务器。
     */
    val isTerminal: Boolean
        get() = this != PENDING

    companion object {
        fun fromWire(raw: String?): ExtractionStatus {
            val value = raw?.trim()?.lowercase().orEmpty()
            if (value.isEmpty()) return UNKNOWN
            return entries.firstOrNull { it.wire == value } ?: UNKNOWN
        }
    }
}

/**
 * `attachment.extraction` 的内容。
 *
 * ⚠️ **提取出的文本在两处之一**（契约）：
 *  - [appliedToContent] == true → 文本写进了**消息的 content**（此时 [text] 不存在）；
 *  - 否则 [text] 就是提取结果（未写进正文，例如邮件正文已有时）。
 * 两者都没有 → 没有可显示的文本（empty/failed）。
 * 具体判定见 [com.jxitc.messagehub.domain.service.AttachmentPreviewRules]（纯函数、有单测）。
 */
data class AttachmentExtraction(
    val status: ExtractionStatus = ExtractionStatus.UNKNOWN,
    val engine: String? = null,
    val chars: Int? = null,
    val text: String? = null,
    val appliedToContent: Boolean = false,
    val pages: Int? = null,
    val charsPerPage: Double? = null,
    val error: String? = null,
    val note: String? = null
)

/** 一个已存到服务器上的附件（消息详情里 `metadata.attachments[i]`）。 */
data class ServerAttachment(
    /**
     * 内容派生的存储 key，形如 `ab/cd/<sha256>.png`。
     *
     * 下载地址**只用它 + 配置的 serverUrl 拼**（[com.jxitc.messagehub.data.remote.AttachmentUrls]），
     * 绝不用接口返回的 `url` 字段：那个指向独立源（给浏览器用），
     * 客户端的鉴权拦截器只对 API 域名加 `X-API-Key`，去那个源会 401。
     * 本模型**故意不保留 `url` 字段** —— 不留就没人能误用。
     */
    val key: String,
    val name: String,
    val mime: String,
    val size: Long,
    /** 服务端嗅探出的粗分类：image / pdf / text / file。 */
    val kind: String = "",
    val sha256: String? = null,
    val extraction: AttachmentExtraction? = null
) {
    val isImage: Boolean
        get() = kind.equals("image", ignoreCase = true) || mime.startsWith("image/", ignoreCase = true)

    val isPdf: Boolean
        get() = kind.equals("pdf", ignoreCase = true) || AttachmentPolicy.normalizeMime(mime) == "application/pdf"

    /** 人类可读大小，与"手动添加"页用的是同一套规则（纯函数 `AttachmentPolicy.formatSize`）。 */
    val readableSize: String get() = AttachmentPolicy.formatSize(size)
}

/**
 * 邮件里**存在但没存下来**的文件（`metadata.attachments_skipped`）。
 * 名称 + 原因要展示出来：**"有附件没存"和"没附件"是两回事**，后者事后无法分辨。
 * 这些不是附件，不能去下载。
 */
data class SkippedAttachment(
    val name: String,
    val size: Long = -1L,
    val mime: String? = null,
    val reason: String? = null
) {
    val readableSize: String? get() = if (size >= 0) AttachmentPolicy.formatSize(size) else null
}

/**
 * 一条消息的附件现状（`GET /api/v1/messages/<id>` 的客户端形态）。
 *
 * [attachmentsJson] 之外的东西（id/content）也带着：轮询拿到新正文时，
 * 若提取文本被写进了 content（`applied_to_content`），本地那份 content 也要跟着更新。
 */
data class MessageAttachmentDetail(
    val serverMessageId: String,
    val content: String = "",
    val attachments: List<ServerAttachment> = emptyList(),
    val skipped: List<SkippedAttachment> = emptyList()
) {
    /** 还有附件在 `pending`（非终态）→ 调用方应继续轮询。 */
    val hasPendingExtraction: Boolean
        get() = attachments.any { !(it.extraction?.status ?: ExtractionStatus.UNKNOWN).isTerminal }
}

/** 提取文本的来处，UI 必须标明（"已写入消息正文" vs "OCR 结果"）。 */
enum class ExtractedTextSource { MESSAGE_CONTENT, EXTRACTION_TEXT }

/** 一段可显示、可复制的提取文本，带来源与所属附件。 */
data class ExtractedTextBlock(
    val attachmentKey: String,
    val attachmentName: String,
    val source: ExtractedTextSource,
    val text: String,
    val engine: String? = null,
    val chars: Int? = null,
    val pages: Int? = null
)
