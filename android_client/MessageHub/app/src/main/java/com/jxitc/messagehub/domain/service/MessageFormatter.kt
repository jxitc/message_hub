package com.jxitc.messagehub.domain.service

/**
 * 纯函数：把采集到的原始字段格式化成干净的 `content`（无 emoji/前缀/时间戳）。
 * 与 Android 框架无关，便于 JVM 单元测试。
 */
object MessageFormatter {

    /** SMS：直接返回正文（trim）。 */
    fun sms(content: String): String = content.trim()

    /** 通知：有 title 和 body 合成 `title\nbody`；只有其一则取其本身；都空返回空串。 */
    fun notification(title: String, content: String): String {
        val t = title.trim()
        val b = content.trim()
        return when {
            t.isNotEmpty() && b.isNotEmpty() -> "$t\n$b"
            t.isNotEmpty() -> t
            else -> b
        }
    }
}
