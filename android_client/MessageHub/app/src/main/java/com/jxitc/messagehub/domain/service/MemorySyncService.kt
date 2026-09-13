package com.jxitc.messagehub.domain.service

import com.jxitc.messagehub.data.local.AppPreferences
import com.jxitc.messagehub.data.remote.MessageHubApiClient
import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.MemoryCreationRequest
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.repository.MemoryRepository
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.flow.first

class MemorySyncService(
    private val memoryRepository: MemoryRepository,
    private val apiClient: MessageHubApiClient,
    private val appPreferences: AppPreferences
) {
    
    /**
     * Syncs all pending memories to the server
     * Each sync session tries each memory up to MAX_RETRY_COUNT times
     * Next sync session will try again (fresh retry count)
     * @return Pair<uploaded count, failed count>
     */
    suspend fun syncPendingMemories(): Pair<Int, Int> {
        if (!appPreferences.autoSync) {
            Logger.i("Auto-sync disabled, skipping sync")
            return Pair(0, 0)
        }

        Logger.i("Starting sync of pending memories...")

        val pendingMemories = try {
            memoryRepository.getPendingUploads().first()
        } catch (e: Exception) {
            Logger.e("Failed to get pending uploads", e)
            return Pair(0, 0)
        }

        if (pendingMemories.isEmpty()) {
            Logger.i("No pending memories to sync")
            return Pair(0, 0)
        }

        Logger.i("Found ${pendingMemories.size} pending memories to sync")

        var uploadedCount = 0
        var failedCount = 0

        // Track retry count per session (not permanently in DB)
        val sessionRetryMap = mutableMapOf<Long, Int>()

        for (memory in pendingMemories) {
            try {
                val success = syncSingleMemoryWithRetry(memory, sessionRetryMap)
                if (success) {
                    uploadedCount++
                    Logger.i("Successfully synced memory ${memory.id}: '${memory.title}'")
                } else {
                    failedCount++
                    Logger.w("Failed to sync memory ${memory.id} after ${MAX_RETRY_COUNT} attempts in this session")
                }
            } catch (e: Exception) {
                failedCount++
                Logger.e("Exception syncing memory ${memory.id}: '${memory.title}'", e)
            }
        }

        Logger.i("Sync completed: $uploadedCount uploaded, $failedCount failed")
        return Pair(uploadedCount, failedCount)
    }

    /**
     * Attempts to sync a single memory with retry logic within this session
     * @param memory The memory to sync
     * @param sessionRetryMap Tracks retry count for this sync session only
     * @return true if successfully uploaded, false if all retries exhausted
     */
    private suspend fun syncSingleMemoryWithRetry(
        memory: Memory,
        sessionRetryMap: MutableMap<Long, Int>
    ): Boolean {
        val currentRetries = sessionRetryMap.getOrDefault(memory.id, 0)

        // Try up to MAX_RETRY_COUNT times within this sync session
        for (attempt in currentRetries until MAX_RETRY_COUNT) {
            sessionRetryMap[memory.id] = attempt + 1

            Logger.d("MemorySyncService", "Syncing memory ${memory.id}, attempt ${attempt + 1}/$MAX_RETRY_COUNT")

            val request = MemoryCreationRequest(
                content = memory.content,
                sourceType = memory.sourceType,
                metadata = memory.metadata
            )

            when (val result = apiClient.createMemory(request)) {
                is ProcessingResult.Success -> {
                    // Successfully uploaded - mark as uploaded in local database
                    memoryRepository.updateMemoryUploadStatus(memory.id, true)
                    // 顺手记下服务器 id：以后要查这条消息的附件/提取状态就靠它。
                    result.data.serverMessageId?.let { serverId ->
                        memoryRepository.attachServerMessageId(memory.id, serverId)
                    }
                    Logger.d("MemorySyncService", "Memory ${memory.id} uploaded successfully on attempt ${attempt + 1}")
                    return true
                }
                is ProcessingResult.Error -> {
                    Logger.w("Attempt ${attempt + 1}/$MAX_RETRY_COUNT failed for memory ${memory.id}: ${result.message}")
                    // Continue to next retry attempt
                }
                ProcessingResult.Loading -> {
                    Logger.w("Unexpected loading state for memory ${memory.id}")
                    // Continue to next retry attempt
                }
            }

            // Add small delay between retries to avoid hammering server
            if (attempt < MAX_RETRY_COUNT - 1) {
                kotlinx.coroutines.delay(1000L * (attempt + 1)) // 1s, 2s, 3s, 4s backoff
            }
        }

        // All retries exhausted for this sync session
        return false
    }
    
    companion object {
        private const val MAX_RETRY_COUNT = 5
    }
}