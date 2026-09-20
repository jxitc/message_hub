package com.jxitc.messagehub.domain.service

import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.MemoryCreationRequest
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.delay as coroutineDelay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Uploads local memories to the hub.
 *
 * Why this class is careful about concurrency
 * -------------------------------------------
 * On 2026-09-20 this app put 15,745 rows into the hub for ~991 real events: one
 * Chrome notification was uploaded 271 times in ten seconds. The mechanism was
 * here, not on the server. Every incoming notification and SMS called
 * [syncPendingMemories] — a sweep of the *whole* pending list — from its own
 * coroutine, with nothing serialising them. A burst of ~56 notifications started
 * ~56 sweeps that all walked the same list from the top, each item retried up to 5
 * times: 56 × 5 = 280, and the hub received 281 copies. The arrivals are still
 * grouped in that order in the data, which is how the signature was identified.
 *
 * Two rules replace it, and both matter:
 *
 * 1. Uploading one thing uploads *that* thing ([syncMemory]). A full sweep is for
 *    catching up, not for reacting to a single event.
 * 2. Uploads never overlap, and an overlapping sweep is dropped rather than
 *    queued ([syncPendingMemories] uses `tryLock`). Dropping is right: the sweep
 *    already running will pick up whatever the dropped one would have.
 *
 * The hub now also refuses exact repeats, which is the backstop if this logic ever
 * goes wrong again — but a phone that sends each event once is the actual fix.
 *
 * Dependencies are function types, matching [AttachmentExtractionPoller]: that is
 * what lets the concurrency rules above be tested on the JVM with fakes instead of
 * being asserted in a comment.
 */
class MemorySyncService(
    /** Everything still waiting to go up. */
    private val loadPending: suspend () -> List<Memory>,
    /** One memory by local id, or null when it is not in the local database. */
    private val loadOne: suspend (Long) -> Memory?,
    /** Mark local row `id` as uploaded and remember the server's message id. */
    private val markUploaded: suspend (Long, String?) -> Unit,
    /** The actual upload. */
    private val upload: suspend (MemoryCreationRequest) -> ProcessingResult<Memory>,
    /** User's auto-sync setting. */
    private val autoSyncEnabled: () -> Boolean = { true },
    /** Injected so tests do not really sleep through the backoff. */
    private val delayFn: suspend (Long) -> Unit = { coroutineDelay(it) }
) {

    /** Serialises uploads. Held for one attempt at one memory. */
    private val uploadLock = Mutex()

    /** Held for the duration of a full sweep, so a second sweep can be refused. */
    private val sweepLock = Mutex()

    /**
     * Syncs every pending memory to the server.
     *
     * Each session tries each memory up to MAX_RETRY_COUNT times; the next session
     * starts with a fresh retry count. Returns `Pair(uploaded, failed)`.
     *
     * If a sweep is already running this returns `Pair(0, 0)` immediately instead of
     * queueing another pass over the same list — that queueing is what turned a
     * notification burst into a flood.
     */
    suspend fun syncPendingMemories(): Pair<Int, Int> {
        if (!autoSyncEnabled()) {
            Logger.i("Auto-sync disabled, skipping sync")
            return Pair(0, 0)
        }

        if (!sweepLock.tryLock()) {
            Logger.i("A sweep is already running — not starting a second one")
            return Pair(0, 0)
        }
        try {
            return sweep()
        } finally {
            sweepLock.unlock()
        }
    }

    private suspend fun sweep(): Pair<Int, Int> {
        Logger.i("Starting sync of pending memories...")

        val pending = try {
            loadPending()
        } catch (e: Exception) {
            Logger.e("Failed to get pending uploads", e)
            return Pair(0, 0)
        }

        if (pending.isEmpty()) {
            Logger.i("No pending memories to sync")
            return Pair(0, 0)
        }

        Logger.i("Found ${pending.size} pending memories to sync")

        var uploadedCount = 0
        var failedCount = 0
        for (memory in pending) {
            if (uploadMemory(memory)) {
                uploadedCount++
            } else {
                failedCount++
                Logger.w("Failed to sync memory ${memory.id} after $MAX_RETRY_COUNT attempts in this session")
            }
        }

        Logger.i("Sync completed: $uploadedCount uploaded, $failedCount failed")
        return Pair(uploadedCount, failedCount)
    }

    /**
     * Uploads exactly one memory, by local id.
     *
     * This is what an incoming notification or SMS should call: the event that just
     * arrived is the only thing that needs to reach the hub right now.
     *
     * @return true if it is now on the server (or already was).
     */
    suspend fun syncMemory(memoryId: Long): Boolean {
        if (!autoSyncEnabled()) {
            Logger.i("Auto-sync disabled, skipping upload of memory $memoryId")
            return false
        }
        val memory = loadOne(memoryId)
        if (memory == null) {
            Logger.w("Memory $memoryId is not in the local database — nothing to upload")
            return false
        }
        if (memory.isUploaded) {
            Logger.d("MemorySyncService", "Memory $memoryId is already on the server")
            return true
        }
        return uploadMemory(memory)
    }

    /**
     * One memory, at most MAX_RETRY_COUNT attempts, serialised against every other
     * upload. The lock is taken per attempt rather than around the whole retry loop
     * so that one slow failure cannot hold the queue through the entire backoff.
     */
    private suspend fun uploadMemory(memory: Memory): Boolean {
        for (attempt in 0 until MAX_RETRY_COUNT) {
            Logger.d("MemorySyncService", "Syncing memory ${memory.id}, attempt ${attempt + 1}/$MAX_RETRY_COUNT")

            val request = MemoryCreationRequest(
                content = memory.content,
                sourceType = memory.sourceType,
                metadata = memory.metadata
            )

            when (val result = uploadLock.withLock { upload(request) }) {
                is ProcessingResult.Success -> {
                    // A 200 with duplicate=true lands here too — the hub answers
                    // "already have it" as success precisely so a retrying client
                    // stops retrying — and it carries the id of the stored row,
                    // which is the one worth recording.
                    markUploaded(memory.id, result.data.serverMessageId)
                    Logger.d("MemorySyncService", "Memory ${memory.id} uploaded on attempt ${attempt + 1}")
                    return true
                }
                is ProcessingResult.Error -> {
                    Logger.w("Attempt ${attempt + 1}/$MAX_RETRY_COUNT failed for memory ${memory.id}: ${result.message}")
                }
                ProcessingResult.Loading -> {
                    Logger.w("Unexpected loading state for memory ${memory.id}")
                }
            }

            // Short backoff: nobody is waiting on this upload, and anything that
            // needs a longer wait is better served by the next sweep than by
            // sleeping here holding a slot.
            if (attempt < MAX_RETRY_COUNT - 1) {
                delayFn(1000L * (attempt + 1))    // 1s, 2s, 3s, 4s
            }
        }

        return false
    }

    companion object {
        private const val MAX_RETRY_COUNT = 5
    }
}
