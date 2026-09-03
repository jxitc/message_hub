package com.jxitc.messagehub.domain.model

/**
 * Domain model for SMS message data
 */
data class SmsMessage(
    val phoneNumber: String,
    val contactName: String? = null,
    val content: String,
    val timestamp: Long,
    val messageId: String? = null
)

/**
 * Result of SMS processing operation
 */
sealed class SmsProcessingResult {
    data class Success(val memoryId: Long, val message: String) : SmsProcessingResult()
    data class Filtered(val reason: String) : SmsProcessingResult()
    data class Failed(val error: String, val exception: Throwable? = null) : SmsProcessingResult()
}