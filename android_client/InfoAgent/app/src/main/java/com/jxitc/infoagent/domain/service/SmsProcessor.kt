package com.jxitc.infoagent.domain.service

import android.telephony.SmsMessage
import com.jxitc.infoagent.domain.usecase.ProcessSmsUseCase
import com.jxitc.infoagent.utils.Logger

/**
 * Service for processing SMS messages from the SMS receiver
 * Coordinates SMS parsing and memory creation
 */
class SmsProcessor(
    private val processSmsUseCase: ProcessSmsUseCase
) {
    
    suspend fun processSmsMessage(smsMessage: SmsMessage) {
        try {
            Logger.d("SmsProcessor", "Processing SMS: ${smsMessage.originatingAddress} - ${smsMessage.messageBody?.take(50)}...")
            
            val phoneNumber = smsMessage.originatingAddress ?: "Unknown"
            val content = smsMessage.messageBody ?: ""
            val timestamp = smsMessage.timestampMillis
            
            // Validate SMS content
            if (content.isBlank()) {
                Logger.w("SmsProcessor", "Empty SMS content, skipping")
                return
            }
            
            // Process the SMS using the use case
            val result = processSmsUseCase.processSmsMessage(phoneNumber, content, timestamp)
            
            when (result) {
                is com.jxitc.infoagent.domain.model.SmsProcessingResult.Success -> {
                    Logger.i("SmsProcessor", "SMS processed successfully: ${result.message}")
                }
                is com.jxitc.infoagent.domain.model.SmsProcessingResult.Filtered -> {
                    Logger.d("SmsProcessor", "SMS filtered: ${result.reason}")
                }
                is com.jxitc.infoagent.domain.model.SmsProcessingResult.Failed -> {
                    Logger.e("SmsProcessor", "SMS processing failed: ${result.error}", result.exception)
                }
            }
            
        } catch (e: Exception) {
            Logger.e("SmsProcessor", "Unexpected error in SMS processor", e)
        }
    }
}