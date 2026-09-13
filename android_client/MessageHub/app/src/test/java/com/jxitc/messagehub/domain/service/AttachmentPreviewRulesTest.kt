package com.jxitc.messagehub.domain.service

import com.jxitc.messagehub.domain.model.AttachmentExtraction
import com.jxitc.messagehub.domain.model.ExtractedTextSource
import com.jxitc.messagehub.domain.model.ExtractionStatus
import com.jxitc.messagehub.domain.model.ServerAttachment
import com.jxitc.messagehub.domain.model.SkippedAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提取状态的纯规则：
 *  - 状态是否终态（`pending` 是唯一的非终态）；
 *  - **提取文本落位**（content vs extraction.text —— 契约里最容易写错的一条）；
 *  - 状态徽标文案；
 *  - 大小格式化。
 */
class AttachmentPreviewRulesTest {

    private fun attachment(
        key: String = "ab/cd/deadbeef.png",
        name: String = "1000067929.jpg",
        mime: String = "image/png",
        kind: String = "image",
        size: Long = 574_732L,
        extraction: AttachmentExtraction? = null
    ) = ServerAttachment(key = key, name = name, mime = mime, size = size, kind = kind, extraction = extraction)

    // ------------------------------------------------------------ 状态终态

    @Test
    fun pendingIsTheOnlyNonTerminalStatus() {
        assertFalse(ExtractionStatus.PENDING.isTerminal)
        assertTrue(ExtractionStatus.DONE.isTerminal)
        assertTrue(ExtractionStatus.EMPTY.isTerminal)
        assertTrue(ExtractionStatus.FAILED.isTerminal)
        assertTrue(ExtractionStatus.UNAVAILABLE.isTerminal)
        assertTrue(ExtractionStatus.SKIPPED.isTerminal)
    }

    @Test
    fun unknownStatusIsTreatedAsTerminalSoPollingStops() {
        // 契约里没见过的新状态：不能当成 pending 永远轮询下去。
        assertTrue(ExtractionStatus.UNKNOWN.isTerminal)
        assertEquals(ExtractionStatus.UNKNOWN, ExtractionStatus.fromWire("brand_new_state"))
        assertEquals(ExtractionStatus.UNKNOWN, ExtractionStatus.fromWire(null))
        assertEquals(ExtractionStatus.UNKNOWN, ExtractionStatus.fromWire(""))
    }

    @Test
    fun statusParsingIsCaseInsensitiveAndTrimmed() {
        assertEquals(ExtractionStatus.PENDING, ExtractionStatus.fromWire("pending"))
        assertEquals(ExtractionStatus.PENDING, ExtractionStatus.fromWire(" Pending "))
        assertEquals(ExtractionStatus.DONE, ExtractionStatus.fromWire("DONE"))
        assertEquals(ExtractionStatus.UNAVAILABLE, ExtractionStatus.fromWire("unavailable"))
    }

    @Test
    fun messageWithAnyPendingAttachmentKeepsPolling() {
        val detail = com.jxitc.messagehub.domain.model.MessageAttachmentDetail(
            serverMessageId = "uuid-1",
            attachments = listOf(
                attachment(key = "a/1.png", extraction = AttachmentExtraction(ExtractionStatus.DONE, chars = 12)),
                attachment(key = "b/2.png", extraction = AttachmentExtraction(ExtractionStatus.PENDING))
            )
        )
        assertTrue(detail.hasPendingExtraction)

        val settled = detail.copy(
            attachments = listOf(
                attachment(key = "a/1.png", extraction = AttachmentExtraction(ExtractionStatus.DONE, chars = 12)),
                attachment(key = "b/2.png", extraction = AttachmentExtraction(ExtractionStatus.FAILED))
            )
        )
        assertFalse(settled.hasPendingExtraction)
    }

    // ------------------------------------------------------------ 文本落位（核心规则）

    @Test
    fun appliedToContentMeansTheTextIsInTheMessageBody() {
        val att = attachment(
            extraction = AttachmentExtraction(
                status = ExtractionStatus.DONE,
                engine = "tesseract",
                chars = 928,
                text = null,                 // 写进正文后这里就没有 text
                appliedToContent = true
            )
        )
        val block = AttachmentPreviewRules.extractedTextOf(att, "发票金额 1280 元")
        assertEquals(ExtractedTextSource.MESSAGE_CONTENT, block?.source)
        assertEquals("发票金额 1280 元", block?.text)
        assertEquals("已写入消息正文", AttachmentPreviewRules.sourceLabel(block!!.source))
    }

    @Test
    fun notAppliedToContentMeansTheTextIsInExtractionText() {
        val att = attachment(
            extraction = AttachmentExtraction(
                status = ExtractionStatus.DONE,
                engine = "tesseract",
                chars = 11,
                text = "OCR 出来的正文",
                appliedToContent = false
            )
        )
        val block = AttachmentPreviewRules.extractedTextOf(att, "邮件正文：请查收附件")
        assertEquals(ExtractedTextSource.EXTRACTION_TEXT, block?.source)
        assertEquals("OCR 出来的正文", block?.text)
        assertEquals("OCR 结果", AttachmentPreviewRules.sourceLabel(block!!.source))
    }

    @Test
    fun neitherPlaceHasTextMeansNothingToShow() {
        // empty / failed：两处都没有文本。
        val empty = attachment(extraction = AttachmentExtraction(ExtractionStatus.EMPTY, chars = 0, appliedToContent = false))
        assertNull(AttachmentPreviewRules.extractedTextOf(empty, ""))

        val failed = attachment(
            extraction = AttachmentExtraction(ExtractionStatus.FAILED, error = "tesseract 未安装", appliedToContent = false)
        )
        assertNull(AttachmentPreviewRules.extractedTextOf(failed, "一段与附件无关的正文"))
    }

    @Test
    fun pendingAttachmentHasNoTextYet() {
        val att = attachment(extraction = AttachmentExtraction(ExtractionStatus.PENDING))
        assertNull(AttachmentPreviewRules.extractedTextOf(att, "正文"))
    }

    @Test
    fun appliedToContentWithEmptyBodyFallsBackToExtractionText() {
        // 契约保证 applied_to_content=true 时文本在 content 里；万一 content 是空的
        // （例如本地拿到的是旧快照），仍然要用 extraction.text，别把文本弄丢。
        val att = attachment(
            extraction = AttachmentExtraction(
                status = ExtractionStatus.DONE,
                chars = 5,
                text = "兜底文本",
                appliedToContent = true
            )
        )
        val block = AttachmentPreviewRules.extractedTextOf(att, "")
        assertEquals(ExtractedTextSource.EXTRACTION_TEXT, block?.source)
        assertEquals("兜底文本", block?.text)
    }

    @Test
    fun attachmentWithoutExtractionFieldHasNoTextAndNoBadge() {
        val att = attachment(extraction = null)
        assertNull(AttachmentPreviewRules.extractedTextOf(att, "正文"))
        assertNull(AttachmentPreviewRules.badgeLabelFor(att))
    }

    @Test
    fun textBlocksAreDeduplicatedWhenSeveralAttachmentsShareTheMessageBody() {
        // 服务端只在 content 为空时把提取文本写进正文；多个附件都标记 applied_to_content 时
        // 指向的是同一份正文 —— 不能把同一段长文本显示四遍。
        val attachments = listOf(
            attachment(key = "a.png", extraction = AttachmentExtraction(ExtractionStatus.DONE, appliedToContent = true)),
            attachment(key = "b.png", extraction = AttachmentExtraction(ExtractionStatus.DONE, appliedToContent = true)),
            attachment(
                key = "c.pdf",
                extraction = AttachmentExtraction(ExtractionStatus.DONE, text = "PDF 文本", appliedToContent = false)
            )
        )
        val blocks = AttachmentPreviewRules.extractedTexts("同一段正文", attachments)
        assertEquals(2, blocks.size)
        assertEquals(ExtractedTextSource.MESSAGE_CONTENT, blocks[0].source)
        assertEquals(ExtractedTextSource.EXTRACTION_TEXT, blocks[1].source)
    }

    // ------------------------------------------------------------ 徽标

    @Test
    fun badgeLabelsMatchTheContract() {
        assertEquals("提取中", AttachmentPreviewRules.badgeLabel(ExtractionStatus.PENDING))
        assertEquals("928 字", AttachmentPreviewRules.badgeLabel(ExtractionStatus.DONE, chars = 928))
        assertEquals("无文字", AttachmentPreviewRules.badgeLabel(ExtractionStatus.EMPTY))
        assertEquals("提取失败", AttachmentPreviewRules.badgeLabel(ExtractionStatus.FAILED))
        assertEquals("不支持", AttachmentPreviewRules.badgeLabel(ExtractionStatus.UNAVAILABLE))
        assertEquals("不支持", AttachmentPreviewRules.badgeLabel(ExtractionStatus.SKIPPED))
    }

    @Test
    fun doneWithZeroCharsReadsAsNoText() {
        assertEquals("无文字", AttachmentPreviewRules.badgeLabel(ExtractionStatus.DONE, chars = 0))
    }

    @Test
    fun doneWithoutCharsCountFallsBackToTextLength() {
        assertEquals("4 字", AttachmentPreviewRules.badgeLabel(
            ExtractionStatus.DONE, chars = null, textLengthFallback = 4
        ))
    }

    @Test
    fun badgeToneSeparatesErrorFromPlainUnsupported() {
        assertEquals(AttachmentPreviewRules.BadgeTone.ERROR, AttachmentPreviewRules.badgeTone(ExtractionStatus.FAILED))
        assertEquals(AttachmentPreviewRules.BadgeTone.POSITIVE, AttachmentPreviewRules.badgeTone(ExtractionStatus.DONE))
        assertEquals(AttachmentPreviewRules.BadgeTone.NEUTRAL, AttachmentPreviewRules.badgeTone(ExtractionStatus.PENDING))
        assertEquals(AttachmentPreviewRules.BadgeTone.WARNING, AttachmentPreviewRules.badgeTone(ExtractionStatus.UNAVAILABLE))
    }

    // ------------------------------------------------------------ 大小格式化

    @Test
    fun sizeFormattingIsHumanReadable() {
        assertEquals("0 B", AttachmentPolicy.formatSize(0))
        assertEquals("512 B", AttachmentPolicy.formatSize(512))
        assertEquals("1 KB", AttachmentPolicy.formatSize(1024))
        assertEquals("1.5 KB", AttachmentPolicy.formatSize(1536))
        assertEquals("561.3 KB", AttachmentPolicy.formatSize(574_732))
        assertEquals("1 MB", AttachmentPolicy.formatSize(1_048_576))
        assertEquals("未知大小", AttachmentPolicy.formatSize(-1))
    }

    @Test
    fun attachmentExposesHumanReadableSize() {
        assertEquals("561.3 KB", attachment(size = 574_732).readableSize)
        assertEquals("1 MB", attachment(size = 1_048_576).readableSize)
        // 服务端没给 size 时不当成 0 B 展示（UI 会隐藏大小那一截）。
        assertEquals(-1L, attachment(size = -1).size)
    }

    // ------------------------------------------------------------ 类型

    @Test
    fun iconKindComesFromSniffedKindThenMime() {
        assertEquals(
            AttachmentPreviewRules.AttachmentIcon.IMAGE,
            AttachmentPreviewRules.iconFor(attachment(kind = "image", mime = "image/jpeg"))
        )
        assertEquals(
            AttachmentPreviewRules.AttachmentIcon.PDF,
            AttachmentPreviewRules.iconFor(attachment(kind = "pdf", mime = "application/pdf"))
        )
        assertEquals(
            AttachmentPreviewRules.AttachmentIcon.TEXT,
            AttachmentPreviewRules.iconFor(attachment(kind = "text", mime = "text/plain"))
        )
        assertEquals(
            AttachmentPreviewRules.AttachmentIcon.FILE,
            AttachmentPreviewRules.iconFor(attachment(kind = "file", mime = "application/octet-stream"))
        )
        // kind 缺失时按 mime 判。
        assertTrue(attachment(kind = "", mime = "image/webp").isImage)
    }

    @Test
    fun detailLineMentionsSourceEnginePagesAndChars() {
        val block = com.jxitc.messagehub.domain.model.ExtractedTextBlock(
            attachmentKey = "k",
            attachmentName = "scan.pdf",
            source = ExtractedTextSource.EXTRACTION_TEXT,
            text = "文字",
            engine = "pdftoppm+tesseract",
            chars = 928,
            pages = 3
        )
        assertEquals("OCR 结果 · pdftoppm+tesseract · 3 页 · 928 字", AttachmentPreviewRules.detailLine(block))
    }

    // ---- 列表里"那一条消息"的附件摘要（不是列表页顶部横幅）----

    @Test
    fun `no attachments means no summary line`() {
        assertEquals(null, AttachmentPreviewRules.attachmentsSummary(emptyList(), emptyList()))
    }

    @Test
    fun `pending attachment summarises as extracting`() {
        val summary = AttachmentPreviewRules.attachmentsSummary(
            listOf(attachment(extraction = AttachmentExtraction(status = ExtractionStatus.PENDING))), emptyList())
        assertEquals("1 个附件 · 提取中", summary)
    }

    @Test
    fun `done attachments summarise total characters`() {
        val summary = AttachmentPreviewRules.attachmentsSummary(
            listOf(
                attachment(extraction = AttachmentExtraction(status = ExtractionStatus.DONE, chars = 900)),
                attachment(extraction = AttachmentExtraction(status = ExtractionStatus.DONE, chars = 28))
            ), emptyList())
        assertEquals("2 个附件 · 928 字", summary)
    }

    @Test
    fun `failed attachments say so rather than showing zero characters`() {
        val summary = AttachmentPreviewRules.attachmentsSummary(
            listOf(attachment(extraction = AttachmentExtraction(status = ExtractionStatus.FAILED))), emptyList())
        assertEquals("1 个附件 · 提取失败", summary)
    }

    @Test
    fun `unsupported and empty have their own wording`() {
        assertEquals("1 个附件 · 不支持", AttachmentPreviewRules.attachmentsSummary(
            listOf(attachment(extraction = AttachmentExtraction(status = ExtractionStatus.UNAVAILABLE))), emptyList()))
        assertEquals("1 个附件 · 无文字", AttachmentPreviewRules.attachmentsSummary(
            listOf(attachment(extraction = AttachmentExtraction(status = ExtractionStatus.EMPTY))), emptyList()))
    }

    @Test
    fun `skipped attachments are counted separately`() {
        val summary = AttachmentPreviewRules.attachmentsSummary(
            listOf(attachment(extraction = AttachmentExtraction(status = ExtractionStatus.DONE, chars = 12))),
            listOf(SkippedAttachment(name = "big.zip", size = 2_000_000, reason = "类型不受支持")))
        assertEquals("1 个附件 · 12 字 · 1 个未保存", summary)
    }

    @Test
    fun `skipped only still produces a line`() {
        assertEquals("1 个未保存", AttachmentPreviewRules.attachmentsSummary(
            emptyList(), listOf(SkippedAttachment(name = "x.zip"))))
    }
}
