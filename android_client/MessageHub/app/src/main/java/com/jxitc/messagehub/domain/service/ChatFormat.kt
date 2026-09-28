package com.jxitc.messagehub.domain.service

import com.jxitc.messagehub.domain.model.ChatMessage
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 问答页面用到的格式化：**纯函数，无 Android 依赖**，所以能在 JVM 单测里钉住输出格式
 * （见 ChatFormatTest）。
 *
 * 数字一律用 [Locale.US]：`String.format` 跟随系统 locale，在德语/法语机器上
 * 小数点是逗号，`¥0,0229` 这种输出会溜到界面上。
 */
object ChatFormat {

    private val TIME_WITH_DATE = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * 回答下方那行：`3.0s · 10432+259 tokens · ¥0.0229`。
     *
     * 缺项**整段省掉**而不是补 0：老记录（或某次调用没回 usage）确实没有这些数字，
     * 显示 `0 tokens` 是在编数据。
     */
    fun meta(message: ChatMessage): String {
        val parts = mutableListOf<String>()
        message.elapsedMs?.let { parts += duration(it) }
        tokenSummary(message)?.let { parts += it }
        message.cost?.let { parts += money(it) }
        return parts.joinToString(" · ")
    }

    /** `3.0s` / `850ms`。 */
    fun duration(elapsedMs: Int): String =
        if (elapsedMs < 1000) "${elapsedMs}ms"
        else String.format(Locale.US, "%.1fs", elapsedMs / 1000.0)

    /** `10432+259 tokens`：prompt+completion，两个都没有时返回 null。 */
    private fun tokenSummary(message: ChatMessage): String? {
        val prompt = message.tokensPrompt
        val completion = message.tokensCompletion
        if (prompt == null && completion == null) return null
        return "${prompt ?: 0}+${completion ?: 0} tokens"
    }

    /**
     * `¥0.0229`。
     *
     * 4 位小数是刻意的：一问一答就是几分钱，2 位小数全都会显示成 `¥0.02`，
     * 看不出 0.0209 和 0.0229 的区别，而那正是"换模型/加检索值不值"要看的东西。
     */
    fun money(cost: Double): String = "¥" + String.format(Locale.US, "%.4f", cost)

    /**
     * 服务器给的 `created_at` 是 **naive UTC**（服务端 `datetime.utcnow()`，不带时区后缀），
     * 直接当本地时间显示会差几个小时 —— 所以按 UTC 解析再转本地时区。
     * 解析不了（格式变了）就原样返回，宁可显示一个 ISO 串也不显示错误的时间。
     */
    fun timestamp(raw: String): String {
        if (raw.isBlank()) return ""
        val local = parseUtc(raw) ?: return raw
        val zone = ZoneId.systemDefault()
        val time = local.atZone(ZoneOffset.UTC).withZoneSameInstant(zone)
        val now = LocalDateTime.now(zone)
        // 今天的只显示时刻，之前的带上日期：聊天记录里绝大多数是最近的。
        return if (time.toLocalDate() == now.toLocalDate()) time.format(TIME_ONLY)
        else time.format(TIME_WITH_DATE)
    }

    private fun parseUtc(raw: String): LocalDateTime? = try {
        // 服务器给的是微秒精度（2026-09-28T09:12:33.123456），ISO_LOCAL_DATE_TIME 能解析。
        LocalDateTime.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    } catch (e: Exception) {
        try {
            // 万一带上时区（Z / +08:00），按带偏移的解析再归一化到 UTC。
            java.time.OffsetDateTime.parse(raw.trim())
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime()
        } catch (e2: Exception) {
            null
        }
    }

    /** 来源标注：`短信 95588` / `通知 微信`；两边都没有时给个中性词。 */
    fun sourceLabel(type: String?, sender: String?): String {
        val kind = when (type?.trim()?.uppercase()) {
            "SMS" -> "短信"
            "PUSH_NOTIFICATION" -> "通知"
            "EMAIL" -> "邮件"
            "CALL_LOG" -> "通话"
            "NOTE", "DOCUMENT" -> "手动"
            else -> type?.trim().orEmpty()
        }
        val from = sender?.trim().orEmpty()
        return listOf(kind, from).filter { it.isNotEmpty() }.joinToString(" ").ifBlank { "库里的记录" }
    }
}
