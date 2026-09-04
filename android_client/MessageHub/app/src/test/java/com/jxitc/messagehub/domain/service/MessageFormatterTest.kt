package com.jxitc.messagehub.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 纯函数：MessageFormatter 的内容格式化。 */
class MessageFormatterTest {

    @Test
    fun sms_returnsTrimmedContent() {
        assertEquals("hello", MessageFormatter.sms("  hello  "))
    }

    @Test
    fun sms_noEmojiOrEnglishPrefix() {
        val c = MessageFormatter.sms("CLEAN_SMS_1")
        assertFalse(c.contains("SMS Message"))
        assertFalse(c.contains("From:"))
        assertFalse(c.contains("\uD83D\uDCF1")) // 📱
    }

    @Test
    fun notification_titleAndBody_joinsWithNewline() {
        assertEquals("Title\nBody", MessageFormatter.notification(" Title ", " Body "))
    }

    @Test
    fun notification_titleOnly() {
        assertEquals("Title", MessageFormatter.notification("Title", "   "))
    }

    @Test
    fun notification_bodyOnly() {
        assertEquals("Body", MessageFormatter.notification("", "Body"))
    }

    @Test
    fun notification_empty() {
        assertEquals("", MessageFormatter.notification("", "   "))
    }
}
