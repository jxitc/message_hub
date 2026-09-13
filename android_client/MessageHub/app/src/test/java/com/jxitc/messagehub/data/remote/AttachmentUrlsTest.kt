package com.jxitc.messagehub.data.remote

import com.jxitc.messagehub.domain.model.ExtractionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件地址拼接：**必须用 `key` + 配置的 serverUrl**，绝不能用接口返回的 `url`。
 *
 * 那个 `url` 指向独立源（`https://mhblob.jxitc.com/...`），是给浏览器用的
 * （浏览器的 `<img>` 带不了 header），而客户端的鉴权拦截器只对 API 域名加 `X-API-Key`
 * —— 直连它必然 401。另外用 key 还能让客户端不受服务端存储配置变化影响。
 */
class AttachmentUrlsTest {

    @Test
    fun buildsTheStableBlobEndpointFromKeyAndServerUrl() {
        assertEquals(
            "https://mh.jxitc.com/api/v1/blobs/ab/cd/deadbeef.png",
            AttachmentUrls.blobUrl("https://mh.jxitc.com", "ab/cd/deadbeef.png")
        )
        // 尾斜杠/前导斜杠的多种写法都归一。
        assertEquals(
            "https://mh.jxitc.com/api/v1/blobs/ab/cd/deadbeef.png",
            AttachmentUrls.blobUrl("https://mh.jxitc.com/", "/ab/cd/deadbeef.png")
        )
        // 局域网调试地址（保持 http，不做 https 升级）。
        assertEquals(
            "http://192.168.1.5:8000/api/v1/blobs/ab/cd/x.pdf",
            AttachmentUrls.blobUrl("http://192.168.1.5:8000/", "ab/cd/x.pdf")
        )
    }

    @Test
    fun ignoresTheIndependentBlobDomainEvenWhenGivenOne() {
        // 接口返回的 url 字段（独立源）与 key 派生出的地址是两回事 —— 客户端只用后者。
        val fromApi = "https://mhblob.jxitc.com/ab/cd/deadbeef.png"
        val ours = AttachmentUrls.blobUrl("https://mh.jxitc.com", "ab/cd/deadbeef.png")
        assertFalse(ours.contains("mhblob.jxitc.com"))
        assertTrue(ours.startsWith("https://mh.jxitc.com/api/v1/blobs/"))
        assertTrue(fromApi != ours)
    }

    @Test
    fun apiKeyIsOnlyAttachedToTheConfiguredServerHost() {
        val server = "https://mh.jxitc.com"
        assertTrue(AttachmentUrls.shouldAttachApiKey("https://mh.jxitc.com/api/v1/blobs/ab/cd/x.png", server))
        assertTrue(AttachmentUrls.shouldAttachApiKey("https://mh.jxitc.com/api/v1/messages/uuid", server))
        // 独立 blob 源：不带凭据（就算有代码去请求它，也不会把 key 漏出去）。
        assertFalse(AttachmentUrls.shouldAttachApiKey("https://mhblob.jxitc.com/ab/cd/x.png", server))
        // 别的域名同样不加。
        assertFalse(AttachmentUrls.shouldAttachApiKey("https://example.com/x.png", server))
        // 端口不同＝不同源，也不加。
        assertFalse(AttachmentUrls.shouldAttachApiKey("https://mh.jxitc.com:8443/x.png", server))
        assertTrue(AttachmentUrls.shouldAttachApiKey("http://192.168.1.5:8000/api/v1/blobs/x", "http://192.168.1.5:8000"))
        assertFalse(AttachmentUrls.shouldAttachApiKey("http://192.168.1.5:9000/api/v1/blobs/x", "http://192.168.1.5:8000"))
    }

    @Test
    fun defaultPortsAreNormalisedSoTheKeyIsNotLost() {
        // 用户可能把 serverUrl 填成 https://host:443，而 OkHttp 的 URL 会省掉默认端口 ——
        // 不归一化就会判成"不同源"、请求不带 API key，全部 401。
        assertTrue(
            AttachmentUrls.shouldAttachApiKey(
                "https://mh.jxitc.com/api/v1/blobs/x.png",
                "https://mh.jxitc.com:443"
            )
        )
        assertTrue(
            AttachmentUrls.shouldAttachApiKey(
                "http://192.168.1.5/api/v1/blobs/x.png",
                "http://192.168.1.5:80"
            )
        )
        // 非默认端口仍然算不同源。
        assertFalse(
            AttachmentUrls.shouldAttachApiKey(
                "https://mh.jxitc.com:8443/api/v1/blobs/x.png",
                "https://mh.jxitc.com"
            )
        )
    }

    @Test
    fun relativeOrBlankUrlsAreNotTrusted() {
        assertFalse(AttachmentUrls.shouldAttachApiKey("/api/v1/blobs/ab/cd/x.png", "https://mh.jxitc.com"))
        assertFalse(AttachmentUrls.shouldAttachApiKey("", "https://mh.jxitc.com"))
        assertFalse(AttachmentUrls.shouldAttachApiKey("https://mh.jxitc.com/x", ""))
    }

    // ------------------------------------------------------------ DTO 映射

    private fun json(s: String) = s

    @Test
    fun apiAttachmentUrlFieldIsDroppedOnPurpose() {
        val dto = ApiAttachment(
            key = "ab/cd/deadbeef.png",
            name = "1000067929.jpg",
            mime = "image/png",
            size = 574_732,
            kind = "image",
            sha256 = "deadbeef",
            extraction = ApiAttachmentExtraction(status = "pending")
        )
        val domain = dto.toDomain()!!
        assertEquals("ab/cd/deadbeef.png", domain.key)
        assertEquals(ExtractionStatus.PENDING, domain.extraction?.status)
        // 领域模型里根本没有 url 字段：不留就没人能误用（编译期保证）。
        assertEquals(574_732L, domain.size)
        assertEquals("561.3 KB", domain.readableSize)
    }

    @Test
    fun attachmentWithoutKeyIsDropped() {
        assertNull(ApiAttachment(key = null, name = "x.png").toDomain())
        assertNull(ApiAttachment(key = "   ", name = "x.png").toDomain())
    }

    @Test
    fun nameFallsBackToTheKeyTailWhenTheServerOmitsIt() {
        val domain = ApiAttachment(key = "ab/cd/deadbeef.png").toDomain()!!
        assertEquals("deadbeef.png", domain.name)
    }

    @Test
    fun detailParsesAttachmentsFromMetadata() {
        val body = com.google.gson.Gson().fromJson(
            json(
                """
                {"id":"uuid-1","content":"正文","metadata":{
                  "source":"email",
                  "attachments":[
                    {"key":"ab/cd/x.png","kind":"image","mime":"image/png","size":574732,"name":"1000067929.jpg",
                     "sha256":"abc","url":"https://mhblob.jxitc.com/ab/cd/x.png",
                     "extraction":{"status":"done","engine":"tesseract","chars":928,
                                   "applied_to_content":true,"pages":null,"chars_per_page":null}}
                  ],
                  "attachments_skipped":[{"name":"big.zip","size":9999999,"mime":"application/zip","reason":"超过 1 MB"}]
                }}
                """.trimIndent()
            ),
            MessageDetailApiData::class.java
        ).toDomain()

        assertEquals("uuid-1", body.serverMessageId)
        assertEquals("正文", body.content)
        assertEquals(1, body.attachments.size)
        val attachment = body.attachments.first()
        assertEquals(ExtractionStatus.DONE, attachment.extraction?.status)
        assertTrue(attachment.extraction!!.appliedToContent)
        assertEquals(928, attachment.extraction!!.chars)
        assertFalse(body.hasPendingExtraction)
        assertEquals(1, body.skipped.size)
        assertEquals("big.zip", body.skipped.first().name)
        assertEquals("超过 1 MB", body.skipped.first().reason)
    }

    @Test
    fun uploadResponseParsesTopLevelAttachmentsAndRejections() {
        val response = com.google.gson.Gson().fromJson(
            json(
                """
                {"message":"Message created successfully","id":"uuid-2",
                 "attachments":[{"key":"ab/cd/y.png","kind":"image","mime":"image/png","size":10,
                                 "name":"y.png","sha256":"abc","extraction":{"status":"pending"}}],
                 "rejected":[{"name":"x.zip","error":"不支持的类型"}]}
                """.trimIndent()
            ),
            MessageApiResponse::class.java
        )
        assertEquals("uuid-2", response.id)
        assertEquals(1, response.attachments?.size)
        assertEquals(ExtractionStatus.PENDING, response.attachments!!.first().toDomain()!!.extraction?.status)
        assertEquals(1, response.rejected?.size)
        val rejected = response.rejected!!.first().toDomain()
        assertEquals("x.zip", rejected.name)
        assertEquals("不支持的类型", rejected.reason)
    }
}
