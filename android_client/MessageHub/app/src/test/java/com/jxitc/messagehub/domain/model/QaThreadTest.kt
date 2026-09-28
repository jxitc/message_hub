package com.jxitc.messagehub.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 问答会话按设备分。
 *
 * 原本固定成 "android"，两台手机会共用同一条聊天记录。改成 deviceId 之后要保证三件事：
 * 每台设备各自一条、拿不到 deviceId 时有兜底、长度不超服务端上限。
 */
class QaThreadTest {

    @Test
    fun `a device gets its own conversation`() {
        assertEquals("OPPO-Find-X7-db1792",
            QaThread.conversationId("OPPO-Find-X7-db1792"))
    }

    @Test
    fun `two devices do not share a conversation`() {
        // 就是这条决定了 OPPO 和三星各看各的历史
        assertNotEquals(
            QaThread.conversationId("OPPO-Find-X7-db1792"),
            QaThread.conversationId("SM-S928B-1a2b3c"))
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals("OPPO-Find-X7-db1792", QaThread.conversationId("  OPPO-Find-X7-db1792  "))
    }

    @Test
    fun `a missing device id falls back instead of asking for an empty conversation`() {
        // conversation_id 为空时服务端返回**全部**会话的历史，那是最糟的结果
        assertEquals(QaThread.FALLBACK_CONVERSATION_ID, QaThread.conversationId(null))
        assertEquals(QaThread.FALLBACK_CONVERSATION_ID, QaThread.conversationId(""))
        assertEquals(QaThread.FALLBACK_CONVERSATION_ID, QaThread.conversationId("   "))
    }

    @Test
    fun `the id is capped at the server's column width`() {
        assertEquals(64, QaThread.conversationId("x".repeat(200)).length)
    }

    @Test
    fun `source stays android so the server can still tell phones apart from the web`() {
        assertEquals("android", QaThread.SOURCE)
    }
}
