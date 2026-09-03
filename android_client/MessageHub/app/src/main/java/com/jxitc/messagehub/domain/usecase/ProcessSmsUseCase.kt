package com.jxitc.messagehub.domain.usecase

import android.content.ContentResolver
import android.provider.ContactsContract
import com.jxitc.messagehub.domain.model.MemoryCreationRequest
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.model.SmsMessage
import com.jxitc.messagehub.domain.model.SmsProcessingResult
import com.jxitc.messagehub.domain.model.SourceType
import com.jxitc.messagehub.domain.repository.MemoryRepository
import com.jxitc.messagehub.domain.service.MemorySyncService
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.*

/**
 * Use case for processing SMS messages into memories
 * Handles contact resolution and memory creation
 * NO FILTERING: All SMS messages are captured to preserve complete communication history
 */
class ProcessSmsUseCase(
    private val memoryRepository: MemoryRepository,
    private val contentResolver: ContentResolver,
    private val syncService: MemorySyncService
) {
    private val syncScope = CoroutineScope(Dispatchers.IO)
    
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    
    suspend fun processSmsMessage(phoneNumber: String, content: String, timestamp: Long): SmsProcessingResult {
        try {
            Logger.d("ProcessSmsUseCase", "Processing SMS from $phoneNumber")
            android.util.Log.d("ProcessSmsUseCase", "Processing SMS - length: ${content.length}, from: $phoneNumber")

            // Resolve contact name
            val contactName = resolveContactName(phoneNumber)
            val displayName = contactName ?: phoneNumber
            
            // Create SMS message object
            val smsMessage = SmsMessage(
                phoneNumber = phoneNumber,
                contactName = contactName,
                content = content,
                timestamp = timestamp
            )
            
            // Format SMS content for memory
            val formattedContent = formatSmsAsMemory(smsMessage)
            
            // Create memory request
            val request = MemoryCreationRequest(
                content = formattedContent,
                sourceType = SourceType.SMS,
                metadata = mapOf(
                    "phone_number" to phoneNumber,
                    "contact_name" to (contactName ?: ""),
                    "timestamp" to timestamp.toString()
                )
            )

            when (val result = memoryRepository.createMemory(request)) {
                is ProcessingResult.Success -> {
                    Logger.d("ProcessSmsUseCase", "SMS processed successfully, memory ID: ${result.data.id}")

                    // Trigger auto-sync in background after successful save
                    syncScope.launch {
                        try {
                            Logger.d("ProcessSmsUseCase", "Triggering auto-sync for SMS memory ${result.data.id}")
                            syncService.syncPendingMemories()
                        } catch (e: Exception) {
                            Logger.e("ProcessSmsUseCase", "Auto-sync failed, will retry later", e)
                            // Don't fail the SMS processing if sync fails
                            // Memory is already saved locally and will sync later
                        }
                    }

                    return SmsProcessingResult.Success(
                        result.data.id,
                        "SMS from $displayName saved as memory"
                    )
                }
                is ProcessingResult.Error -> {
                    Logger.e("ProcessSmsUseCase", "Failed to create memory: ${result.message}")
                    return SmsProcessingResult.Failed("Failed to save SMS: ${result.message}", result.throwable)
                }
                is ProcessingResult.Loading -> {
                    // This shouldn't happen for a synchronous operation
                    return SmsProcessingResult.Failed("Unexpected loading state", null)
                }
            }
            
        } catch (e: Exception) {
            Logger.e("ProcessSmsUseCase", "Error processing SMS", e)
            return SmsProcessingResult.Failed("Unexpected error processing SMS", e)
        }
    }
    
    private fun resolveContactName(phoneNumber: String): String? {
        return try {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(phoneNumber)
                .build()
            
            val cursor = contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return it.getString(nameIndex)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Logger.e("ProcessSmsUseCase", "Error resolving contact name for $phoneNumber", e)
            null
        }
    }
    
    private fun formatSmsAsMemory(smsMessage: SmsMessage): String {
        val timestamp = dateFormat.format(Date(smsMessage.timestamp))
        val sender = smsMessage.contactName ?: smsMessage.phoneNumber
        
        return buildString {
            appendLine("📱 SMS Message")
            appendLine("From: $sender")
            appendLine("Received: $timestamp")
            appendLine()
            appendLine(smsMessage.content)
        }
    }
}