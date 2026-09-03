package com.jxitc.messagehub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.jxitc.messagehub.MessageHubApplication
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for intercepting incoming SMS messages
 * Processes SMS content and stores it as memories in MessageHub
 */
class SmsReceiver : BroadcastReceiver() {
    
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    override fun onReceive(context: Context?, intent: Intent?) {
        // Use both Logger and Android Log for debugging
        android.util.Log.d("SmsReceiver", "========== SMS RECEIVER TRIGGERED ==========")
        android.util.Log.d("SmsReceiver", "Context: ${context != null}, Intent: ${intent != null}")
        android.util.Log.d("SmsReceiver", "Action: ${intent?.action}")
        android.util.Log.d("SmsReceiver", "Intent extras: ${intent?.extras?.keySet()?.joinToString()}")

        Logger.d("SmsReceiver", "onReceive called with action: ${intent?.action}")

        if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION && context != null) {
            android.util.Log.d("SmsReceiver", "SMS_RECEIVED_ACTION matched, processing...")
            try {
                val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                android.util.Log.d("SmsReceiver", "Extracted ${smsMessages?.size ?: 0} messages from intent")

                val appContainer = (context.applicationContext as MessageHubApplication).appContainer

                Logger.d("SmsReceiver", "Received ${smsMessages?.size ?: 0} SMS messages")

                smsMessages?.forEach { smsMessage ->
                    val sender = smsMessage.originatingAddress ?: "Unknown"
                    val body = smsMessage.messageBody?.take(50) ?: "Empty"
                    android.util.Log.d("SmsReceiver", "Processing SMS from: $sender, body: $body")

                    coroutineScope.launch {
                        try {
                            // Get SMS processor from app container (to be created)
                            appContainer.smsProcessor.processSmsMessage(smsMessage)
                            android.util.Log.d("SmsReceiver", "SMS processing launched for: $sender")
                        } catch (e: Exception) {
                            android.util.Log.e("SmsReceiver", "Error processing SMS message from $sender", e)
                            Logger.e("SmsReceiver", "Error processing SMS message", e)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SmsReceiver", "Error in SMS receiver", e)
                Logger.e("SmsReceiver", "Error in SMS receiver", e)
            }
        } else {
            android.util.Log.w("SmsReceiver", "Invalid intent or context - Context: ${context != null}, Action: ${intent?.action}")
            Logger.w("SmsReceiver", "Invalid intent or context received")
        }
    }
}