package com.jxitc.messagehub.data.remote

import com.jxitc.messagehub.domain.model.AttachmentPayload
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：`POST /api/v1/messages` 的 multipart 请求体形态。
 *
 * 这些字段名是与服务器冻结的约定，改一个就会在生产上静默丢数据，
 * 所以用单测把它们钉住。整个请求体是纯 OkHttp 对象，可以在 JVM 上直接 dump 出来断言。
 */
class MessageMultipartBuilderTest {

    private val metadata = mapOf<String, Any>(
        "input_method" to "manual_with_attachments",
        "attachment_count" to 2
    )

    private fun textOf(body: MultipartBody): String {
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun build(
        content: String = "hello",
        attachments: List<AttachmentPayload> = emptyList()
    ): MultipartBody = MessageMultipartBuilder.build(
        sourceDeviceId = "PHZ110-abc123",
        type = "NOTE",
        sender = "PHZ110",
        content = content,
        timestamp = "2026-08-31T07:00:00Z",
        metadata = metadata,
        attachments = attachments
    )

    private fun payload(name: String, mime: String, size: Int) =
        AttachmentPayload(name, mime, ByteArray(size) { 1 })

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun allContractFieldsArePresentExactlyOnce() {
        val text = textOf(build())
        for (field in listOf(
            "source_device_id", "type", "sender", "content", "timestamp", "metadata"
        )) {
            assertEquals("field $field", 1, occurrences(text, "name=\"$field\""))
        }
    }

    @Test
    fun fieldValuesAreCarriedThrough() {
        val text = textOf(build())
        assertTrue(text, text.contains("PHZ110-abc123"))
        assertTrue(text, text.contains("PHZ110"))
        assertTrue(text, text.contains("2026-08-31T07:00:00Z"))
        assertTrue(text, text.contains("NOTE"))
    }

    @Test
    fun bodyIsMultipartFormData() {
        val body = build()
        assertEquals("multipart", body.type?.type)
        assertEquals("form-data", body.type?.subtype)
        assertTrue(textOf(body).startsWith("--"))
    }

    @Test
    fun metadataIsSentAsAJsonStringNotAnObject() {
        val text = textOf(build())
        // 值直接以 JSON 文本出现在 part 里，而不是某个嵌套结构
        assertTrue(
            text,
            text.contains("""{"input_method":"manual_with_attachments","attachment_count":2}""")
        )
    }

    @Test
    fun metadataJsonOfEmptyMapIsStillAnObject() {
        assertEquals("{}", MessageMultipartBuilder.metadataJson(emptyMap()))
        // 非 ASCII 不该被转义成 \uXXXX（服务器存的是 UTF-8 中文）
        assertTrue(
            MessageMultipartBuilder.metadataJson(mapOf("input_method" to "手动")).contains("手动")
        )
    }

    @Test
    fun attachmentsUseTheFrozenFieldNameAndRepeatOncePerFile() {
        val text = textOf(
            build(
                attachments = listOf(
                    payload("shot.png", "image/png", 10),
                    payload("doc.pdf", "application/pdf", 20)
                )
            )
        )
        // 字段名固定为 attachments，一个文件一个 part —— 出现两次
        assertEquals(2, occurrences(text, "name=\"attachments\""))
        assertTrue(text, text.contains("filename=\"shot.png\""))
        assertTrue(text, text.contains("filename=\"doc.pdf\""))
        // 每个 part 带上自己的 Content-Type，服务器/浏览器都靠它
        assertTrue(text, text.contains("image/png"))
        assertTrue(text, text.contains("application/pdf"))
    }

    @Test
    fun attachmentContentTypeFallsBackWhenMimeIsMissing() {
        val text = textOf(build(attachments = listOf(payload("blob", "", 4))))
        assertTrue(text, text.contains("application/octet-stream"))
    }

    @Test
    fun noAttachmentPartsAreSentWhenThereAreNoAttachments() {
        val text = textOf(build())
        assertFalse(text, text.contains("name=\"attachments\""))
    }

    @Test
    fun blankContentIsStillSentAsAnEmptyField() {
        // 契约：有附件、无正文时 content 传空字符串（field 必须在，只是值为空）
        val text = textOf(build(content = "", attachments = listOf(payload("shot.png", "image/png", 5))))
        assertEquals(1, occurrences(text, "name=\"content\""))
        val contentPart = text.substringAfter("name=\"content\"")
        val value = contentPart.substringAfter("\r\n\r\n").substringBefore("\r\n--")
        assertEquals("", value)
    }

    @Test
    fun textPartsDeclareUtf8PlainText() {
        val text = textOf(build(content = "中文内容"))
        assertTrue(text, text.contains("text/plain; charset=utf-8"))
        assertTrue(text, text.contains("中文内容"))
    }

    @Test
    fun everyTextPartValueIsPresent() {
        val text = textOf(build(content = "remember this"))
        assertTrue(text, text.contains("remember this"))
    }

    @Test
    fun builderOverloadAcceptsAMessageCreateRequest() {
        val request = MessageCreateRequest(
            sourceDeviceId = "dev-1",
            type = "DOCUMENT",
            sender = "Pixel7",
            content = "",
            timestamp = "2026-08-31T07:00:00Z",
            metadata = mapOf("input_method" to "manual_with_attachments")
        )
        val text = textOf(
            MessageMultipartBuilder.build(request, listOf(payload("a.pdf", "application/pdf", 8)))
        )
        assertTrue(text, text.contains("dev-1"))
        assertTrue(text, text.contains("DOCUMENT"))
        assertTrue(text, text.contains("name=\"attachments\""))
    }

    @Test
    fun filenamesWithQuotesAreEscapedNotInjected() {
        // OkHttp 会把 " 转义成 %22；这里断言的是"没有产生一个假的 part 头"
        val text = textOf(build(attachments = listOf(payload("we\"ird.png", "image/png", 3))))
        assertEquals(1, occurrences(text, "name=\"attachments\""))
        assertFalse(text, text.contains("filename=\"we\"ird.png\""))
    }
}
