package com.jxitc.messagehub.domain.service

import com.jxitc.messagehub.domain.model.Memory
import com.jxitc.messagehub.domain.model.MemoryCreationRequest
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.model.SourceType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the 2026-09-20 duplicate flood.
 *
 * A notification burst produced 15,745 rows for ~991 real events because every
 * arriving message triggered a *full* sweep of the pending queue from its own
 * coroutine, with nothing serialising them: ~56 concurrent sweeps × 5 retries per
 * item = the 281 copies of one Chrome notification that the hub received.
 *
 * These tests hold the two rules that replace it. They run on the JVM because the
 * service takes its dependencies as functions (same shape as
 * AttachmentExtractionPoller), so no device and no Robolectric are needed.
 */
/** A local memory row, as Room would hand it back. */
private fun localMemory(
    id: Long,
    uploaded: Boolean = false,
    serverMessageId: String? = null
) = Memory(
    id = id,
    title = "记忆 $id",
    content = "内容 $id",
    sourceType = SourceType.NOTIFICATION,
    createdAt = LocalDateTime.now(),
    updatedAt = LocalDateTime.now(),
    isUploaded = uploaded,
    serverMessageId = serverMessageId
)

class MemorySyncServiceConcurrencyTest {

    /** Records uploads and the highest number of them in flight at once. */
    private class FakeUploader(private val failAlways: Boolean = false) {
        val requests = mutableListOf<MemoryCreationRequest>()
        val maxConcurrent = AtomicInteger()
        private val inFlight = AtomicInteger()
        var delayMs = 20L

        suspend fun upload(request: MemoryCreationRequest): ProcessingResult<Memory> {
            val now = inFlight.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, now) }
            requests += request
            try {
                delay(delayMs)
                if (failAlways) return ProcessingResult.Error("server said no")
                return ProcessingResult.Success(
                    localMemory(id = requests.size.toLong(), uploaded = true,
                                serverMessageId = "server-${requests.size}")
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private class Harness(
        val memories: MutableList<Memory>,
        failAlways: Boolean = false
    ) {
        val uploader = FakeUploader(failAlways)
        val markedUploaded = mutableListOf<Long>()
        val recordedServerIds = mutableListOf<Pair<Long, String?>>()
        val waits = mutableListOf<Long>()

        val service = MemorySyncService(
            loadPending = { memories.filter { !it.isUploaded } },
            loadOne = { id -> memories.firstOrNull { it.id == id } },
            markUploaded = { id, serverId ->
                markedUploaded += id
                recordedServerIds += id to serverId
            },
            upload = { uploader.upload(it) },
            autoSyncEnabled = { true },
            delayFn = { waits += it }
        )
    }

    @Test
    fun `a burst of per-message syncs uploads each message exactly once`() = runBlocking {
        // 40 notifications arriving together, each uploading only itself.
        val harness = Harness((1L..40L).map { localMemory(it) }.toMutableList())

        (1L..40L).map { id -> async { harness.service.syncMemory(id) } }.awaitAll()

        assertEquals("one upload per message", 40, harness.uploader.requests.size)
        assertEquals(40, harness.markedUploaded.size)
    }

    @Test
    fun `uploads never run at the same time`() = runBlocking {
        val harness = Harness((1L..12L).map { localMemory(it) }.toMutableList())

        (1L..12L).map { id -> async { harness.service.syncMemory(id) } }.awaitAll()

        assertEquals("uploads must be serialised, not parallel",
            1, harness.uploader.maxConcurrent.get())
    }

    @Test
    fun `a sweep already running is not started a second time`() = runBlocking {
        val harness = Harness((1L..6L).map { localMemory(it) }.toMutableList())
        harness.uploader.delayMs = 40

        // Ten list refreshes at once: one sweep runs, the other nine are dropped.
        val results = (1..10).map { async { harness.service.syncPendingMemories() } }.awaitAll()

        assertEquals("only one sweep may run", 6, harness.uploader.requests.size)
        assertEquals("nine callers were refused", 9, results.count { it == Pair(0, 0) })
    }

    @Test
    fun `the server id is recorded so attachments can be polled later`() = runBlocking {
        val harness = Harness(mutableListOf(localMemory(7L)))

        assertTrue(harness.service.syncMemory(7L))

        assertEquals(listOf(7L to "server-1"), harness.recordedServerIds)
    }

    @Test
    fun `a message already on the server is not uploaded again`() = runBlocking {
        val harness = Harness(mutableListOf(localMemory(1L, uploaded = true), localMemory(2L)))

        assertTrue(harness.service.syncMemory(1L))
        assertEquals(0, harness.uploader.requests.size)

        assertTrue(harness.service.syncMemory(2L))
        assertEquals(1, harness.uploader.requests.size)
    }

    @Test
    fun `a memory that is not in the local database is reported as not uploaded`() = runBlocking {
        val harness = Harness(mutableListOf())

        assertFalse(harness.service.syncMemory(999L))
        assertEquals(0, harness.uploader.requests.size)
    }

    @Test
    fun `auto-sync disabled means nothing leaves the device`() = runBlocking {
        val harness = Harness(mutableListOf(localMemory(1L)))
        val disabled = MemorySyncService(
            loadPending = { harness.memories },
            loadOne = { id -> harness.memories.firstOrNull { it.id == id } },
            markUploaded = { _, _ -> },
            upload = { harness.uploader.upload(it) },
            autoSyncEnabled = { false }
        )

        assertFalse(disabled.syncMemory(1L))
        assertEquals(Pair(0, 0), disabled.syncPendingMemories())
        assertEquals(0, harness.uploader.requests.size)
    }

    @Test
    fun `a memory failing every attempt is counted once, then left alone`() = runBlocking {
        val harness = Harness(mutableListOf(localMemory(1L)), failAlways = true)

        val (uploaded, failed) = harness.service.syncPendingMemories()

        assertEquals(0, uploaded)
        assertEquals(1, failed)
        assertEquals("5 attempts per sweep, then give up", 5, harness.uploader.requests.size)
        assertEquals("backoff between attempts", listOf(1000L, 2000L, 3000L, 4000L), harness.waits)
        assertTrue("a failure must not be marked as uploaded", harness.markedUploaded.isEmpty())
    }

    @Test
    fun `a sweep uploads the whole backlog when it is the only one running`() = runBlocking {
        val harness = Harness((1L..25L).map { localMemory(it) }.toMutableList())

        val (uploaded, failed) = harness.service.syncPendingMemories()

        assertEquals(25, uploaded)
        assertEquals(0, failed)
        assertEquals(25, harness.markedUploaded.size)
    }
}
