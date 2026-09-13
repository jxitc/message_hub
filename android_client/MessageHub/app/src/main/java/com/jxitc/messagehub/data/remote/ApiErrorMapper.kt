package com.jxitc.messagehub.data.remote

import com.google.gson.JsonParser
import com.jxitc.messagehub.domain.service.AttachmentPolicy

/**
 * HTTP 错误 → 给用户看的话。
 *
 * 服务器出错时返回 `{"error": "<中文说明>"}`（400/413/415/507 都是这个形状），
 * 那句说明是写给用户看的，所以**优先原样展示**；拿不到 body 时按状态码兜底。
 *
 * 纯函数 + 无 Android 依赖，见 ApiErrorMapperTest。
 */
object ApiErrorMapper {

    /** 单条提示的长度上限（日志/界面都不该被一个巨大 body 撑爆）。 */
    private const val MAX_MESSAGE_CHARS = 300

    /**
     * 解析错误 body 里的 `error`（也认 `message`，兼容老的 `{"message": ...}` 风格）。
     * 不是 JSON、或没有这两个字段时返回 null。
     */
    fun extractServerMessage(rawBody: String?): String? {
        val body = rawBody?.trim().orEmpty()
        if (body.isEmpty()) return null
        return try {
            val element = JsonParser.parseString(body)
            if (!element.isJsonObject) return null
            val obj = element.asJsonObject
            val message = listOf("error", "message")
                .asSequence()
                .mapNotNull { key -> obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
                .firstOrNull { it.isNotBlank() }
            message?.let { clamp(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 完整的错误文案：先看服务器说了什么，没有就按状态码兜底翻译。
     *
     * @param maxBytes 当前附件上限（用于 413 文案里报出真实数字）
     */
    fun describe(
        code: Int,
        rawErrorBody: String? = null,
        statusMessage: String? = null,
        maxBytes: Long = AttachmentPolicy.DEFAULT_MAX_BYTES
    ): String = extractServerMessage(rawErrorBody) ?: fallback(code, statusMessage, maxBytes)

    /** 状态码兜底文案。 */
    fun fallback(
        code: Int,
        statusMessage: String? = null,
        maxBytes: Long = AttachmentPolicy.DEFAULT_MAX_BYTES
    ): String {
        val limit = AttachmentPolicy.formatSize(maxBytes)
        return when (code) {
            400 -> "服务器不接受这次请求（HTTP 400）：" +
                statusMessage.orEmpty().ifBlank { "参数有问题" }
            401 -> "API Key 无效或已过期（HTTP 401），请到「设置」里检查"
            403 -> "没有权限（HTTP 403），请检查 API Key 的权限"
            404 -> "接口不存在（HTTP 404），请检查「设置」里的 Server URL"
            413 -> "附件太大被服务器拒绝（HTTP 413）：单个文件上限 $limit，" +
                "超过的图片会自动压缩，PDF 等请换小文件"
            415 -> "服务器不接受这个文件类型（HTTP 415）：允许 PNG/JPEG/GIF/WebP 图片、PDF、纯文本"
            500 -> "服务器内部错误（HTTP 500），请稍后再试"
            507 -> "服务器存储配额已满（HTTP 507），请稍后再试或清理服务器上的旧文件"
            in 300..399 -> "HTTP $code: 服务器要求跳转（通常是 Server URL 少了 https://）——" +
                "重定向会把 POST 降级成 GET，消息会静默丢失"
            else -> "HTTP $code: ${statusMessage.orEmpty().ifBlank { "请求失败" }}"
        }
    }

    private fun clamp(message: String): String =
        if (message.length <= MAX_MESSAGE_CHARS) message
        else message.take(MAX_MESSAGE_CHARS) + "…"
}
