package com.jxitc.messagehub.data.remote

import com.jxitc.messagehub.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

/** 纯函数：MessageMapper 的 type/sender 映射。 */
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

    @Test
    fun mapToMessageType_manualPlainIsSms() {
        assertEquals("SMS", MessageMapper.mapToMessageType(SourceType.MANUAL, emptyMap()))
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
}
