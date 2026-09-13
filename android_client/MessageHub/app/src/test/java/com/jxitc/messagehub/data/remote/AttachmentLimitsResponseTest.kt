package com.jxitc.messagehub.data.remote

import com.google.gson.Gson
import com.jxitc.messagehub.domain.service.AttachmentPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `GET /api/v1/attachments/limits` 的解析与兜底。
 *
 * 客户端**不写死**上限与允许类型：读服务器的；读不到（或字段非法）时才回退到
 * 契约里的 1 MB + 类型清单。
 */
class AttachmentLimitsResponseTest {

    private val gson = Gson()

    @Test
    fun parsesTheServerPayload() {
        val json = """
            {"max_bytes": 1048576,
             "allowed": ["image/png","image/jpeg","image/gif","image/webp",
                         "application/pdf","text/plain"],
             "note": "图片超过 max_bytes 请在客户端压缩"}
        """.trimIndent()

        val limits = gson.fromJson(json, AttachmentLimitsResponse::class.java).toDomain()

        assertEquals(1_048_576L, limits.maxBytes)
        assertEquals(6, limits.allowedMimeTypes.size)
        assertEquals(AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES, limits.allowedMimeTypes)
    }

    @Test
    fun honoursADifferentServerLimit() {
        val limits = gson.fromJson(
            """{"max_bytes": 5242880, "allowed": ["image/png"]}""",
            AttachmentLimitsResponse::class.java
        ).toDomain()

        assertEquals(5_242_880L, limits.maxBytes)
        assertEquals(listOf("image/png"), limits.allowedMimeTypes)
    }

    @Test
    fun fallsBackWhenThePayloadIsEmptyOrBroken() {
        val empty = gson.fromJson("{}", AttachmentLimitsResponse::class.java).toDomain()
        assertEquals(AttachmentPolicy.DEFAULT_MAX_BYTES, empty.maxBytes)
        assertEquals(AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES, empty.allowedMimeTypes)

        // max_bytes=0 或负数都不是有效上限
        val zero = gson.fromJson("""{"max_bytes":0}""", AttachmentLimitsResponse::class.java).toDomain()
        assertEquals(AttachmentPolicy.DEFAULT_MAX_BYTES, zero.maxBytes)

        // allowed 里全是空白字符串 → 按"没有清单"处理
        val blanks = gson.fromJson(
            """{"allowed":["","  "]}""", AttachmentLimitsResponse::class.java
        ).toDomain()
        assertEquals(AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES, blanks.allowedMimeTypes)
    }
}
