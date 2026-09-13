package com.jxitc.messagehub.data.database

import com.jxitc.messagehub.domain.model.AttachmentExtraction
import com.jxitc.messagehub.domain.model.ExtractionStatus
import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.ServerAttachment
import com.jxitc.messagehub.domain.model.SkippedAttachment
import com.jxitc.messagehub.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 附件元信息在 Room 里的 JSON 编解码。
 *
 * 只存元信息 + 提取文本（**字节不进库**，图片按需从服务器拉、靠图片库磁盘缓存），
 * 且解码必须容错：一列坏 JSON 不能把列表页搞崩。
 */
class AttachmentMetadataCodecTest {

    private val attachment = ServerAttachment(
        key = "ab/cd/deadbeef.png",
        name = "1000067929.jpg",
        mime = "image/png",
        size = 574_732,
        kind = "image",
        sha256 = "deadbeef",
        extraction = AttachmentExtraction(
            status = ExtractionStatus.DONE,
            engine = "tesseract",
            chars = 928,
            text = "提取出来的文本",
            appliedToContent = false,
            pages = 2,
            charsPerPage = 464.0,
            note = "扫描件"
        )
    )

    @Test
    fun roundTripKeepsEverythingNeededForOfflineDisplay() {
        val json = AttachmentMetadataCodec.encodeAttachments(listOf(attachment))
        val decoded = AttachmentMetadataCodec.decodeAttachments(json)

        assertEquals(1, decoded.size)
        val restored = decoded.first()
        assertEquals(attachment.key, restored.key)
        assertEquals(attachment.name, restored.name)
        assertEquals(attachment.mime, restored.mime)
        assertEquals(attachment.size, restored.size)
        assertEquals(attachment.kind, restored.kind)
        assertEquals(attachment.sha256, restored.sha256)
        assertEquals(ExtractionStatus.DONE, restored.extraction?.status)
        assertEquals("tesseract", restored.extraction?.engine)
        assertEquals(928, restored.extraction?.chars)
        assertEquals("提取出来的文本", restored.extraction?.text)
        assertEquals(false, restored.extraction?.appliedToContent)
        assertEquals(2, restored.extraction?.pages)
        assertEquals(464.0, restored.extraction?.charsPerPage)
        assertEquals("扫描件", restored.extraction?.note)
    }

    @Test
    fun skippedAttachmentsRoundTrip() {
        val json = AttachmentMetadataCodec.encodeSkipped(
            listOf(SkippedAttachment(name = "big.zip", size = 9_999_999, mime = "application/zip", reason = "超过 1 MB"))
        )
        val decoded = AttachmentMetadataCodec.decodeSkipped(json)
        assertEquals(1, decoded.size)
        assertEquals("big.zip", decoded.first().name)
        assertEquals("超过 1 MB", decoded.first().reason)
        assertEquals("9.5 MB", decoded.first().readableSize)
    }

    @Test
    fun corruptJsonDegradesToEmptyInsteadOfThrowing() {
        assertEquals(emptyList<ServerAttachment>(), AttachmentMetadataCodec.decodeAttachments("这不是 JSON"))
        assertEquals(emptyList<ServerAttachment>(), AttachmentMetadataCodec.decodeAttachments(null))
        assertEquals(emptyList<ServerAttachment>(), AttachmentMetadataCodec.decodeAttachments(""))
        assertEquals(emptyList<SkippedAttachment>(), AttachmentMetadataCodec.decodeSkipped("{{{"))
        // 结构对但内容缺字段：缺 key 的条目丢掉，其余照常保留。
        val partial = AttachmentMetadataCodec.decodeAttachments("""[{"name":"no-key.png"},{"key":"a/b/c.png"}]""")
        assertEquals(1, partial.size)
        assertEquals("a/b/c.png", partial.first().key)
        // 缺 name 时用 key 末段兜底。
        assertEquals("c.png", partial.first().name)
        // 缺 extraction 时状态为空（UI 不显示徽标，而不是显示"提取中"）。
        assertNull(partial.first().extraction)
    }

    @Test
    fun entityRoundTripPreservesAttachmentsAndServerId() {
        val memory = Memory(
            id = 7,
            title = "标题",
            content = "正文",
            sourceType = SourceType.MANUAL,
            metadata = mapOf("input_method" to "manual_with_attachments"),
            createdAt = LocalDateTime.of(2026, 9, 13, 10, 0),
            updatedAt = LocalDateTime.of(2026, 9, 13, 10, 5),
            isUploaded = true,
            serverMessageId = "uuid-9",
            attachments = listOf(attachment),
            skippedAttachments = listOf(SkippedAttachment(name = "big.zip", reason = "太大")),
            attachmentsSyncedAt = LocalDateTime.of(2026, 9, 13, 10, 5)
        )

        val restored = memory.toEntity().toDomainModel()

        assertEquals("uuid-9", restored.serverMessageId)
        assertEquals(1, restored.attachments.size)
        assertEquals(attachment.key, restored.attachments.first().key)
        assertEquals(ExtractionStatus.DONE, restored.attachments.first().extraction?.status)
        assertEquals(1, restored.skippedAttachments.size)
        assertEquals(LocalDateTime.of(2026, 9, 13, 10, 5), restored.attachmentsSyncedAt)
        assertFalse(restored.hasPendingExtraction)
        // 字节从来不进这张表：JSON 里不该出现任何 base64 之类的字段名。
        val json = memory.toEntity().attachmentsJson!!
        assertFalse(json.contains("bytes"))
        assertFalse(json.contains("url"))
    }

    @Test
    fun pendingAttachmentKeepsPollingFlagAfterRoundTrip() {
        val pending = attachment.copy(extraction = AttachmentExtraction(status = ExtractionStatus.PENDING))
        val memory = Memory(
            title = "t", content = "c", sourceType = SourceType.MANUAL,
            serverMessageId = "uuid-1", attachments = listOf(pending)
        )
        val restored = memory.toEntity().toDomainModel()
        assertTrue(restored.hasPendingExtraction)
        assertEquals(ExtractionStatus.PENDING, restored.attachments.first().extraction?.status)
    }

    @Test
    fun legacyRowsWithoutAttachmentColumnsReadAsNoAttachments() {
        // v1 迁移过来的行：新列是 NULL，不能当成"有附件"或崩掉。
        val legacy = MemoryEntity(
            id = 1, title = "老数据", content = "老内容", sourceType = SourceType.SMS,
            metadata = emptyMap(), createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now(),
            isUploaded = true, uploadRetryCount = 0
        )
        val domain = legacy.toDomainModel()
        assertEquals(emptyList<ServerAttachment>(), domain.attachments)
        assertEquals(emptyList<SkippedAttachment>(), domain.skippedAttachments)
        assertNull(domain.serverMessageId)
        assertFalse(domain.hasPendingExtraction)
    }
}
