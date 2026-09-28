package com.jxitc.messagehub.domain.service

import com.jxitc.messagehub.domain.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 回答下方那行与时间的显示格式。
 *
 * 时间那部分依赖运行环境的时区，所以断言写成"显示出来的时刻换回 UTC 一定等于服务器给的值"，
 * 而不是写死一个字符串 —— 写死的话，CI 换了时区就会红，而那种红是没有意义的。
 */
class ChatFormatTest {

    private fun message(
        elapsedMs: Int? = 2989,
        prompt: Int? = 10432,
        completion: Int? = 259,
        cost: Double? = 0.0229
    ) = ChatMessage(
        turnId = "t-1",
        question = "q",
        answer = "a",
        elapsedMs = elapsedMs,
        tokensPrompt = prompt,
        tokensCompletion = completion,
        cost = cost
    )

    @Test
    fun metaLineIsDurationTokensCost() {
        assertEquals("3.0s · 10432+259 tokens · ¥0.0229", ChatFormat.meta(message()))
    }

    @Test
    fun metaLineOmitsWhatTheServerDidNotReport() {
        // 老记录/某次调用没回 usage：整段省掉，而不是补 0 假装有数据
        assertEquals("3.0s", ChatFormat.meta(message(prompt = null, completion = null, cost = null)))
        assertEquals("¥0.0229", ChatFormat.meta(message(elapsedMs = null, prompt = null, completion = null)))
        assertEquals("", ChatFormat.meta(ChatMessage(turnId = "t", question = "q", answer = "a")))
    }

    @Test
    fun durationSwitchesToMillisecondsBelowOneSecond() {
        assertEquals("850ms", ChatFormat.duration(850))
        assertEquals("1.0s", ChatFormat.duration(1000))
        assertEquals("12.3s", ChatFormat.duration(12345))
    }

    @Test
    fun moneyKeepsFourDecimalsWithADotRegardlessOfLocale() {
        // 跟随系统 locale 的话，德语机器上会是 ¥0,0229
        assertEquals("¥0.0229", ChatFormat.money(0.0229))
        assertEquals("¥0.0209", ChatFormat.money(0.0209))
        assertEquals("¥0.0000", ChatFormat.money(0.0))
        assertEquals("¥1.2345", ChatFormat.money(1.2345))
    }

    @Test
    fun serverTimestampIsUtcAndShownInLocalTime() {
        // 服务端存的是 datetime.utcnow()，isoformat() 不带时区后缀 —— 直接当本地时间会差几小时
        val raw = "2026-01-15T03:04:05"
        val expected = LocalDateTime.of(2026, 1, 15, 3, 4, 5)
            .atZone(ZoneOffset.UTC)
            .withZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

        assertEquals(expected, ChatFormat.timestamp(raw))
    }

    @Test
    fun todaysTimestampShowsOnlyTheTime() {
        val now = LocalDateTime.now(ZoneId.systemDefault()).atZone(ZoneId.systemDefault())
        val utc = now.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
        val shown = ChatFormat.timestamp(utc.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        assertTrue("今天的时间应该只显示 HH:mm，实际是 $shown", Regex("""\d{2}:\d{2}""").matches(shown))
    }

    @Test
    fun unparsableTimestampIsShownAsIsInsteadOfAWrongTime() {
        assertEquals("", ChatFormat.timestamp(""))
        assertEquals("刚刚", ChatFormat.timestamp("刚刚"))
    }

    @Test
    fun sourceLabelReadsAsKindPlusSender() {
        assertEquals("短信 95588", ChatFormat.sourceLabel("SMS", "95588"))
        assertEquals("通知 微信", ChatFormat.sourceLabel("PUSH_NOTIFICATION", "微信"))
        assertEquals("短信", ChatFormat.sourceLabel("SMS", "  "))
        assertEquals("库里的记录", ChatFormat.sourceLabel(null, null))
        assertEquals("CUSTOM", ChatFormat.sourceLabel("CUSTOM", null))
    }
}
