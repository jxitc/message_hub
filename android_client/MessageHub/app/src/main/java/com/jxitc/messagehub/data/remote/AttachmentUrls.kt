package com.jxitc.messagehub.data.remote

/**
 * 附件原件的**稳定下载入口**地址（纯函数，有单测）。
 *
 * 🔴 铁律：地址只能用配置的 `serverUrl` + 附件 `key` 拼，**绝不用接口返回的 `url` 字段**。
 *
 * 原因有三条，任一条单独成立就够：
 *  1. 那个 `url` 指向**独立源**（如 `https://mhblob.jxitc.com/...`），是给浏览器 `<img>` 用的
 *     —— 浏览器发图片请求时带不了自定义 header。而我们的 OkHttp 鉴权拦截器
 *     **只对 API 域名**加 `X-API-Key`，直连那个源必得 401。
 *  2. `key` 与物理存储分离（`key` 由内容派生，指向哪儿由服务端配置决定）。
 *     客户端拼稳定入口，服务端以后把 blob 换到对象存储、改 `BLOB_PUBLIC_BASE`，
 *     都不影响已发布的 APK —— 这正是"下载入口被设计成稳定入口"的意义。
 *  3. `url` 可能是签名链接（带过期时间），缓存它没有意义。
 */
object AttachmentUrls {

    /** `{serverUrl}/api/v1/blobs/{key}`，两端多余的斜杠会被规整。 */
    fun blobUrl(serverUrl: String, key: String): String {
        val base = serverUrl.trim().trimEnd('/')
        val path = key.trim().trimStart('/')
        return "$base/api/v1/blobs/$path"
    }

    /**
     * 这个请求该不该带 `X-API-Key`。
     *
     * 只对**配置的服务器主机**加：API key 是凭据，不该出现在任何第三方请求里
     * （例如服务端 metadata 里那个独立 blob 源的 URL —— 就算真有代码去请求它，
     * 这里也不会把 key 漏出去）。
     */
    fun shouldAttachApiKey(requestUrl: String, serverUrl: String): Boolean {
        val requestHost = hostOf(requestUrl) ?: return false
        val serverHost = hostOf(serverUrl) ?: return false
        return requestHost.equals(serverHost, ignoreCase = true)
    }

    /**
     * URL 的 `host[:port]`，**默认端口会被省掉**。
     *
     * 这一步很关键：OkHttp 的 `HttpUrl.toString()` 会把 `https://h:443/x` 写成
     * `https://h/x`，而用户可能把 serverUrl 填成带 `:443` 的样子 —— 不归一化就会
     * 判成"不同源"，请求**不带 API key**，结果是全部 401。
     */
    private fun hostOf(url: String): String? {
        val trimmed = url.trim()
        val scheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> "http"
            trimmed.startsWith("https://", ignoreCase = true) -> "https"
            else -> return null
        }
        val withoutScheme = trimmed.substringAfter("://")
        val authority = withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .ifBlank { return null }
        val defaultPort = if (scheme == "https") ":443" else ":80"
        return if (authority.endsWith(defaultPort)) {
            authority.dropLast(defaultPort.length)
        } else authority
    }
}
