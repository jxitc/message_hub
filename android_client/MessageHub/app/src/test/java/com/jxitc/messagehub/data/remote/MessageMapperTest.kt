package com.jxitc.messagehub.data.remote

import com.jxitc.messagehub.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** 纯函数：MessageMapper 的 type/sender/时间戳映射。 */
class MessageMapperTest {

    @Test
    fun mapToMessageType_sms() {
        assertEquals("SMS", MessageMapper.mapToMessageType(SourceType.SMS, emptyMap()))
    }

    @Test
    fun mapToMessageType_notification() {
        assertEquals("PUSH_NOTIFICATION", MessageMapper.mapToMessageType(SourceType.NOTIFICATION, emptyMap()))
    }

    @Test
    fun mapToMessageType_manualWithNotificationMeta() {
        assertEquals(
            "PUSH_NOTIFICATION",
            MessageMapper.mapToMessageType(SourceType.MANUAL, mapOf("app_name" to "微信"))
        )
    }

    /**
     * 手动记下来的东西不是短信。契约里 NOTE = 手动记录（文本/图片），
     * 所以「手动添加记忆」这条路径统一用 NOTE（而不是老的 SMS）——
     * 否则同一屏里"只写文字"和"文字+图片"会落成两种 type。
     */
    @Test
    fun mapToMessageType_manualPlainIsNote() {
        assertEquals("NOTE", MessageMapper.mapToMessageType(SourceType.MANUAL, emptyMap()))
    }

    // ---------------------------------------------------------------- 附件决定的 type

    @Test
    fun mapManualType_noteWithoutAttachments() {
        assertEquals("NOTE", MessageMapper.mapManualType(emptyList()))
    }

    @Test
    fun mapManualType_noteForImagesOnly() {
        assertEquals("NOTE", MessageMapper.mapManualType(listOf("image/png", "image/jpeg")))
    }

    @Test
    fun mapManualType_documentWhenAFileIsAttached() {
        assertEquals("DOCUMENT", MessageMapper.mapManualType(listOf("application/pdf")))
        assertEquals("DOCUMENT", MessageMapper.mapManualType(listOf("image/png", "text/plain")))
    }

    @Test
    fun resolveSender_prefersContactName() {
        assertEquals(
            "Alice",
            MessageMapper.resolveSender(mapOf("contact_name" to "Alice", "phone_number" to "+1"))
        )
    }

    @Test
    fun resolveSender_fallsBackToPhoneNumber() {
        assertEquals("+1", MessageMapper.resolveSender(mapOf("phone_number" to "+1")))
    }

    @Test
    fun resolveSender_unknown_whenEmpty() {
        assertEquals("unknown", MessageMapper.resolveSender(emptyMap()))
    }

    // ---------------------------------------------------------------- sender（手动添加 = 设备名）

    @Test
    fun resolveSenderFor_manualTypesUseTheDeviceName() {
        // 手动添加的 sender 是产品决定：设备名（Build.MODEL 去空格），不是 unknown
        assertEquals("PHZ110", MessageMapper.resolveSenderFor("NOTE", emptyMap(), "PHZ110"))
        assertEquals("PHZ110", MessageMapper.resolveSenderFor("DOCUMENT", emptyMap(), "PHZ110"))
        assertEquals(
            "PHZ110",
            MessageMapper.resolveSenderFor("NOTE", mapOf("contact_name" to "Alice"), "PHZ110")
        )
    }

    @Test
    fun resolveSenderFor_otherTypesKeepTheOldRule() {
        assertEquals("Alice", MessageMapper.resolveSenderFor("SMS", mapOf("contact_name" to "Alice"), "PHZ110"))
        assertEquals(
            "微信",
            MessageMapper.resolveSenderFor("PUSH_NOTIFICATION", mapOf("app_name" to "微信"), "PHZ110")
        )
    }

    // ---------------------------------------------------------------- 时间戳（契约：ISO8601 + Z）

    @Test
    fun isoTimestampUtc_formatsEpochMillisAsUtc() {
        // 2026-08-31T07:00:00Z
        val epochMillis = Instant.parse("2026-08-31T07:00:00Z").toEpochMilli()
        val timestamp = MessageMapper.isoTimestampUtc(epochMillis)
        assertEquals("2026-08-31T07:00:00Z", timestamp)
        assertTrue(timestamp.endsWith("Z"))
    }

    @Test
    fun isoTimestampUtc_fallsBackToNowWhenMissing() {
        val fallback = Instant.parse("2026-01-02T03:04:05Z")
        val timestamp = MessageMapper.isoTimestampUtc(null, fallback)
        assertEquals("2026-01-02T03:04:05Z", timestamp)
        assertTrue(timestamp.endsWith("Z"))
    }

    @Test
    fun isoTimestampUtc_alwaysEndsWithZ() {
        // 本地时区偏移很大的机器上也必须是 UTC 的 Z 形式，不能出现 +08:00
        val timestamp = MessageMapper.isoTimestampUtc(0L)
        assertTrue(timestamp, timestamp.endsWith("Z"))
        assertTrue(timestamp, timestamp.startsWith("1970-01-01T00:00:00"))
    }
}
