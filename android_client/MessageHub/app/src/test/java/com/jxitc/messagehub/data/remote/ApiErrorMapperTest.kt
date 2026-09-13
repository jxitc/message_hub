package com.jxitc.messagehub.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯函数：HTTP 错误 → 给用户看的话。
 *
 * 服务器的 `{"error": "..."}` 是**写给用户看的**，必须优先原样透出；
 * 拿不到 body 时才按状态码兜底（413/415/507/401 这些）。
 */
class ApiErrorMapperTest {

    // ---------------------------------------------------------------- 解析服务器文案

    @Test
    fun extractServerMessage_readsTheErrorField() {
        assertEquals(
            "文件太大（2048 KB > 1024 KB）。图片请在手机端压缩后再传。",
            ApiErrorMapper.extractServerMessage("""{"error":"文件太大（2048 KB > 1024 KB）。图片请在手机端压缩后再传。"}""")
        )
    }

    @Test
    fun extractServerMessage_alsoAcceptsMessageField() {
        assertEquals("Message created", ApiErrorMapper.extractServerMessage("""{"message":"Message created"}"""))
    }

    @Test
    fun extractServerMessage_prefersErrorOverMessage() {
        assertEquals(
            "出错了",
            ApiErrorMapper.extractServerMessage("""{"error":"出错了","message":"ignored"}""")
        )
    }

    @Test
    fun extractServerMessage_returnsNullForNonJsonBodies() {
        assertNull(ApiErrorMapper.extractServerMessage(null))
        assertNull(ApiErrorMapper.extractServerMessage(""))
        assertNull(ApiErrorMapper.extractServerMessage("   "))
        assertNull(ApiErrorMapper.extractServerMessage("<html>502 Bad Gateway</html>"))
        assertNull(ApiErrorMapper.extractServerMessage("""{"detail":"no"}"""))
        assertNull(ApiErrorMapper.extractServerMessage("""{"error":"   "}"""))
    }

    @Test
    fun extractServerMessage_clampsVeryLongMessages() {
        val huge = "x".repeat(5_000)
        val extracted = ApiErrorMapper.extractServerMessage("""{"error":"$huge"}""")
        assertTrue(extracted!!.length < 400)
        assertTrue(extracted.endsWith("…"))
    }

    // ---------------------------------------------------------------- 状态码兜底

    @Test
    fun describe_prefersTheServerMessage() {
        val text = ApiErrorMapper.describe(413, """{"error":"这张图太大啦"}""")
        assertEquals("这张图太大啦", text)
    }

    @Test
    fun describe_413MentionsTheRealLimit() {
        val text = ApiErrorMapper.describe(413, rawErrorBody = null)
        assertTrue(text, text.contains("413"))
        assertTrue(text, text.contains("1 MB"))
        assertTrue(text, text.contains("附件太大"))
    }

    @Test
    fun describe_413UsesTheServerAdvertisedLimitWhenGiven() {
        val text = ApiErrorMapper.describe(413, maxBytes = 2L * 1024 * 1024)
        assertTrue(text, text.contains("2 MB"))
    }

    @Test
    fun describe_415ExplainsAllowedTypes() {
        val text = ApiErrorMapper.describe(415)
        assertTrue(text, text.contains("415"))
        assertTrue(text, text.contains("PDF"))
        assertTrue(text, text.contains("纯文本"))
    }

    @Test
    fun describe_507ExplainsQuotaIsFull() {
        val text = ApiErrorMapper.describe(507)
        assertTrue(text, text.contains("507"))
        assertTrue(text, text.contains("配额"))
    }

    @Test
    fun describe_401PointsAtTheApiKey() {
        val text = ApiErrorMapper.describe(401)
        assertTrue(text, text.contains("401"))
        assertTrue(text, text.contains("API Key"))
    }

    @Test
    fun describe_3xxWarnsAboutTheRedirectDowngrade() {
        val text = ApiErrorMapper.describe(301, rawErrorBody = null, statusMessage = "Moved Permanently")
        assertTrue(text, text.contains("301"))
        assertTrue(text, text.contains("https"))
    }

    @Test
    fun describe_fallsBackToStatusMessageForUnknownCodes() {
        val text = ApiErrorMapper.describe(418, rawErrorBody = "not json", statusMessage = "I'm a teapot")
        assertTrue(text, text.contains("418"))
        assertTrue(text, text.contains("I'm a teapot"))
    }

    @Test
    fun describe_handlesAnEmptyBodyAndNoStatusMessage() {
        assertEquals("服务器内部错误（HTTP 500），请稍后再试", ApiErrorMapper.describe(500))
        val text = ApiErrorMapper.describe(599)
        assertTrue(text, text.contains("599"))
    }
}
