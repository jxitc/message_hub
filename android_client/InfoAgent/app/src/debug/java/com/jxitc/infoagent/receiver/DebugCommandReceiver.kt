package com.jxitc.infoagent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jxitc.infoagent.InfoAgentApplication
import com.jxitc.infoagent.domain.model.MemoryCreationRequest
import com.jxitc.infoagent.domain.model.ProcessingResult
import com.jxitc.infoagent.domain.model.SourceType
import com.jxitc.infoagent.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * DEBUG-ONLY backdoor for CLI-driven testing via `adb shell am broadcast`.
 *
 * Registered ONLY in the debug build (src/debug/AndroidManifest.xml), so production
 * builds never expose this receiver. This lets the host drive the app end-to-end
 * (add memory, trigger sync, simulate SMS/notification, query state) without any
 * UI interaction or screenshots.
 *
 * Usage from host (all results appear in the "DebugCmd" logger tag):
 *   adb shell am broadcast -a com.jxitc.infoagent.DEBUG_COMMAND --es cmd add_memory \
 *       --es content "Meeting with team Friday 3pm"
 *   adb shell am broadcast -a com.jxitc.infoagent.DEBUG_COMMAND --es cmd sync
 *   adb shell am broadcast -a com.jxitc.infoagent.DEBUG_COMMAND --es cmd simulate_sms \
 *       --es sender "+8613800000000" --es body "Test SMS body"
 *   adb shell am broadcast -a com.jxitc.infoagent.DEBUG_COMMAND --es cmd simulate_notification \
 *       --es pkg com.tencent.mm --es title "WeChat" --es content "New message"
 *   adb shell am broadcast -a com.jxitc.infoagent.DEBUG_COMMAND --es cmd status
 *
 * Read results with:  adb logcat -s DebugCmd
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? InfoAgentApplication ?: return
        val cmd = intent.getStringExtra("cmd") ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (cmd) {
                    "add_memory" -> cmdAddMemory(app, intent)
                    "sync" -> cmdSync(app)
                    "simulate_sms" -> cmdSimulateSms(app, intent)
                    "simulate_notification" -> cmdSimulateNotification(app, intent)
                    "status" -> cmdStatus(app)
                    else -> Logger.w(TAG, "Unknown cmd: $cmd")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error during cmd=$cmd", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun cmdAddMemory(app: InfoAgentApplication, intent: Intent) {
        val content = intent.getStringExtra("content")?.trim().orEmpty()
        if (content.isEmpty()) {
            Logger.w(TAG, "add_memory: content empty")
            return
        }
        val source = sourceTypeOf(intent.getStringExtra("source") ?: "MANUAL")
        val request = MemoryCreationRequest(
            content = content,
            sourceType = source,
            metadata = mapOf("debug_backdoor" to "true")
        )
        when (val result = app.appContainer.createMemoryUseCase.execute(request)) {
            is ProcessingResult.Success -> {
                Logger.i(TAG, "add_memory SUCCESS id=${result.data.id} title=${result.data.title}")
                app.appContainer.syncService.syncPendingMemories()
            }
            is ProcessingResult.Error -> Logger.e(TAG, "add_memory FAILED: ${result.message}")
            else -> Logger.w(TAG, "add_memory unexpected state")
        }
    }

    private suspend fun cmdSync(app: InfoAgentApplication) {
        val (uploaded, failed) = app.appContainer.syncService.syncPendingMemories()
        Logger.i(TAG, "sync done uploaded=$uploaded failed=$failed")
    }

    private suspend fun cmdSimulateSms(app: InfoAgentApplication, intent: Intent) {
        val sender = intent.getStringExtra("sender") ?: "+8613800000000"
        val body = intent.getStringExtra("body")?.trim().orEmpty()
        if (body.isEmpty()) {
            Logger.w(TAG, "simulate_sms: body empty")
            return
        }
        val result = app.appContainer.processSmsUseCase.processSmsMessage(
            sender, body, System.currentTimeMillis()
        )
        Logger.i(TAG, "simulate_sms result=$result")
    }

    private suspend fun cmdSimulateNotification(app: InfoAgentApplication, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: "com.android.systemui"
        val title = intent.getStringExtra("title") ?: ""
        val content = intent.getStringExtra("content")?.trim().orEmpty()
        if (content.isEmpty()) {
            Logger.w(TAG, "simulate_notification: content empty")
            return
        }
        val result = app.appContainer.processNotificationUseCase.processNotification(
            pkg, title, content, System.currentTimeMillis(), 0
        )
        Logger.i(TAG, "simulate_notification result=$result")
    }

    private suspend fun cmdStatus(app: InfoAgentApplication) {
        val all = app.appContainer.memoryRepository.getAllMemories().first()
        val pending = app.appContainer.memoryRepository.getPendingUploads().first()
        val prefs = app.appContainer.appPreferences
        Logger.i(
            TAG,
            "status total=${all.size} pendingUpload=${pending.size} autoSync=${prefs.autoSync} " +
                "wifiOnly=${prefs.syncOnlyOnWifi} serverUrl=${prefs.serverUrl} buildTag=REINSTALL_TEST_01"
        )
    }

    private fun sourceTypeOf(name: String): SourceType = try {
        SourceType.valueOf(name.uppercase())
    } catch (e: Exception) {
        SourceType.MANUAL
    }

    companion object {
        private const val TAG = "DebugCmd"
    }
}
