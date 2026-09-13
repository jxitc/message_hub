package com.jxitc.messagehub.domain.service

import com.jxitc.messagehub.domain.model.ExtractedTextBlock
import com.jxitc.messagehub.domain.model.ExtractedTextSource
import com.jxitc.messagehub.domain.model.ExtractionStatus
import com.jxitc.messagehub.domain.model.ServerAttachment

/**
 * 附件在**界面上怎么显示**的纯规则（无 Android 依赖 → 可在 JVM 单测里直接断言）。
 *
 * 这里集中两类容易写错的东西：
 *  1. **状态徽标文案**（`提取中` / `N 字` / `无文字` / `提取失败` / `不支持`）；
 *  2. **提取文本落位规则** —— 文本只可能在"消息 content"或"extraction.text"**两处之一**，
 *     客户端必须两处都看，且要标明来源。
 */
object AttachmentPreviewRules {

    const val LABEL_PENDING = "提取中"
    const val LABEL_EMPTY = "无文字"
    const val LABEL_FAILED = "提取失败"
    const val LABEL_UNSUPPORTED = "不支持"
    const val LABEL_SOURCE_CONTENT = "已写入消息正文"
    const val LABEL_SOURCE_OCR = "OCR 结果"

    /** 状态徽标文案；[chars] 为 `done` 时的字符数（null 时回退用提取文本长度）。 */
    fun badgeLabel(
        status: ExtractionStatus,
        chars: Int? = null,
        textLengthFallback: Int? = null
    ): String? = when (status) {
        ExtractionStatus.PENDING -> LABEL_PENDING
        ExtractionStatus.DONE -> {
            val count = chars ?: textLengthFallback ?: 0
            if (count > 0) "$count 字" else LABEL_EMPTY
        }
        ExtractionStatus.EMPTY -> LABEL_EMPTY
        ExtractionStatus.FAILED -> LABEL_FAILED
        ExtractionStatus.UNAVAILABLE, ExtractionStatus.SKIPPED -> LABEL_UNSUPPORTED
        // 契约里没见过的状态：不编造，让别的字段（错误/提示）去说明。
        ExtractionStatus.UNKNOWN -> null
    }

    /** 徽标的语义分类，UI 据此上色（不给未知状态加色彩）。 */
    enum class BadgeTone { NEUTRAL, POSITIVE, WARNING, ERROR }

    fun badgeTone(status: ExtractionStatus): BadgeTone = when (status) {
        ExtractionStatus.PENDING -> BadgeTone.NEUTRAL
        ExtractionStatus.DONE -> BadgeTone.POSITIVE
        ExtractionStatus.EMPTY, ExtractionStatus.SKIPPED, ExtractionStatus.UNAVAILABLE -> BadgeTone.WARNING
        ExtractionStatus.FAILED -> BadgeTone.ERROR
        ExtractionStatus.UNKNOWN -> BadgeTone.NEUTRAL
    }

    /** 附件自己的徽标文案（没有 extraction 字段时返回 null，不强加"未知"字样）。 */
    fun badgeLabelFor(attachment: ServerAttachment): String? {
        val extraction = attachment.extraction ?: return null
        return badgeLabel(
            status = extraction.status,
            chars = extraction.chars,
            textLengthFallback = extraction.text?.trim()?.length
        )
    }

    /**
     * 单个附件的提取文本。
     *
     * 规则（契约，**两处都要看**）：
     *  1. `status` 非终态（pending）→ 还没有结果，返回 null（UI 显示"提取中"）；
     *  2. `applied_to_content == true` 且消息 content 非空 → 文本就是 content，来源=消息正文；
     *  3. 否则 `extraction.text` 非空 → 来源=OCR/提取结果；
     *  4. 都没有 → null（empty/failed：没有可显示文本）。
     */
    fun extractedTextOf(attachment: ServerAttachment, messageContent: String?): ExtractedTextBlock? {
        val extraction = attachment.extraction ?: return null
        if (!extraction.status.isTerminal) return null

        val content = messageContent?.trim().orEmpty()
        if (extraction.appliedToContent && content.isNotEmpty()) {
            return ExtractedTextBlock(
                attachmentKey = attachment.key,
                attachmentName = attachment.name,
                source = ExtractedTextSource.MESSAGE_CONTENT,
                text = content,
                engine = extraction.engine,
                chars = content.length,
                pages = extraction.pages
            )
        }

        val text = extraction.text?.trim().orEmpty()
        if (text.isEmpty()) return null
        return ExtractedTextBlock(
            attachmentKey = attachment.key,
            attachmentName = attachment.name,
            source = ExtractedTextSource.EXTRACTION_TEXT,
            text = text,
            engine = extraction.engine,
            chars = extraction.chars ?: text.length,
            pages = extraction.pages
        )
    }

    /**
     * 整条消息的提取文本块。
     *
     * **去重**：多个附件都 `applied_to_content` 时，它们指向的是同一份 content
     * （服务端只在 content 为空时写入，后来的覆盖前面的），所以同样的文本只显示一次 ——
     * 否则一张图配四段一模一样的长文本。
     */
    fun extractedTexts(messageContent: String?, attachments: List<ServerAttachment>): List<ExtractedTextBlock> {
        val seen = mutableSetOf<Pair<ExtractedTextSource, String>>()
        val blocks = mutableListOf<ExtractedTextBlock>()
        for (attachment in attachments) {
            val block = extractedTextOf(attachment, messageContent) ?: continue
            if (seen.add(block.source to block.text)) blocks += block
        }
        return blocks
    }

    /** 来源标签：UI 必须把"哪来的"写明，不能让用户以为 OCR 结果是发件人写的。 */
    fun sourceLabel(source: ExtractedTextSource): String = when (source) {
        ExtractedTextSource.MESSAGE_CONTENT -> LABEL_SOURCE_CONTENT
        ExtractedTextSource.EXTRACTION_TEXT -> LABEL_SOURCE_OCR
    }

    /**
     * `done` 但带页数信息时补一句（PDF 用）：`OCR 结果 · 第 3 页 · 928 字`。
     * 没有页数就不显示这截。
     */
    fun detailLine(block: ExtractedTextBlock): String {
        val parts = mutableListOf(sourceLabel(block.source))
        block.engine?.takeIf { it.isNotBlank() }?.let { parts += it }
        block.pages?.takeIf { it > 0 }?.let { parts += "$it 页" }
        block.chars?.takeIf { it > 0 }?.let { parts += "$it 字" }
        return parts.joinToString(" · ")
    }

    /** 附件类型图标（UI 映射到 Material Icon；纯字符串便于单测）。 */
    enum class AttachmentIcon { IMAGE, PDF, TEXT, FILE }

    fun iconFor(attachment: ServerAttachment): AttachmentIcon = when {
        attachment.isImage -> AttachmentIcon.IMAGE
        attachment.isPdf -> AttachmentIcon.PDF
        attachment.kind.equals("text", ignoreCase = true) ||
            attachment.mime.startsWith("text/", ignoreCase = true) -> AttachmentIcon.TEXT
        else -> AttachmentIcon.FILE
    }
}
