package com.jxitc.messagehub.domain.service

import com.jxitc.messagehub.domain.model.AttachmentExtraction
import com.jxitc.messagehub.domain.model.ExtractionStatus
import com.jxitc.messagehub.domain.model.MessageAttachmentDetail
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.model.ServerAttachment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮询循环本身（不是只有退避表）：用假的 delay 记录等待序列，
 * 断言"到底等了几次、每次多久、什么时候停"。
 */
class AttachmentExtractionPollerTest {

    private fun attachment(status: ExtractionStatus) = ServerAttachment(
        key = "ab/cd/$status.png",
        name = "scan.png",
        mime = "image/png",
        size = 1024,
        kind = "image",
        extraction = AttachmentExtraction(status = status)
    )

    private fun detail(id: String, vararg statuses: ExtractionStatus) = MessageAttachmentDetail(
        serverMessageId = id,
        attachments = statuses.map { attachment(it) }
    )

    /** 记录每次等待时长的假 delay（不真的睡）。 */
    private class RecordingDelay {
        val waits = mutableListOf<Long>()
        suspend fun delay(millis: Long) { waits += millis }
    }

    @Test
    fun pollsUntilEveryAttachmentIsTerminalWithTheContractBackoff() = runBlocking {
        val delay = RecordingDelay()
        val stored = mutableListOf<MessageAttachmentDetail>()
        var round = 0
        val poller = AttachmentExtractionPoller(
            fetch = { id ->
                round++
                when (round) {
                    1 -> ProcessingResult.Success(detail(id, ExtractionStatus.PENDING))
                    2 -> ProcessingResult.Success(detail(id, ExtractionStatus.DONE, ExtractionStatus.PENDING))
                    else -> ProcessingResult.Success(detail(id, ExtractionStatus.DONE, ExtractionStatus.FAILED))
                }
            },
            persist = { stored += it },
            delayFn = { delay.delay(it) }
        )

        val outcome = poller.poll("uuid-1", isVisible = { true })

        assertEquals(PollOutcome.Settled(rounds = 3, waitedMillis = 8_000L), outcome)
        assertEquals(listOf(3_000L, 5_000L), delay.waits)
        assertEquals(3, stored.size)
    }

    @Test
    fun stopsImmediatelyWhenTheScreenIsNotVisible() = runBlocking {
        val delay = RecordingDelay()
        var round = 0
        val poller = AttachmentExtractionPoller(
            fetch = { id -> round++; ProcessingResult.Success(detail(id, ExtractionStatus.PENDING)) },
            delayFn = { delay.delay(it) }
        )

        // 第一轮开始时可见，第二轮开始时已不可见（用户离开了页面）。
        var checks = 0
        val outcome = poller.poll("uuid-1", isVisible = { checks++; checks == 1 })

        assertEquals(PollOutcome.Stopped(1, PollOutcome.StopReason.SCREEN_HIDDEN, 3_000L), outcome)
        assertEquals(1, round)
        assertEquals(listOf(3_000L), delay.waits)
    }

    @Test
    fun neverPollsAnEmptyTargetList() = runBlocking {
        val poller = AttachmentExtractionPoller(
            fetch = { error("不应该发起请求") },
            delayFn = { error("不应该等待") }
        )
        assertEquals(PollOutcome.Settled(0, 0L), poller.poll(emptyList(), isVisible = { true }))
    }

    @Test
    fun givesUpAtTheThreeMinuteBudget() = runBlocking {
        val delay = RecordingDelay()
        var rounds = 0
        val poller = AttachmentExtractionPoller(
            fetch = { id -> rounds++; ProcessingResult.Success(detail(id, ExtractionStatus.PENDING)) },
            delayFn = { delay.delay(it) }
        )

        val outcome = poller.poll("uuid-1", isVisible = { true })

        assertTrue("必须是超时收工：$outcome", outcome is PollOutcome.TimedOut)
        val timedOut = outcome as PollOutcome.TimedOut
        // 累计等待不超过 3 分钟，且不会为了"再多等一次"突破上限。
        assertTrue("累计等待 ${timedOut.waitedMillis} 超出预算", timedOut.waitedMillis <= ExtractionPollSchedule.MAX_TOTAL_MILLIS)
        assertEquals(158_000L, timedOut.waitedMillis)
        assertEquals(9, timedOut.rounds)
        assertEquals(listOf(3_000L, 5_000L, 10_000L, 20_000L, 30_000L, 30_000L, 30_000L, 30_000L), delay.waits)
    }

    @Test
    fun stopsAfterThreeConsecutiveFailedRounds() = runBlocking {
        val delay = RecordingDelay()
        var rounds = 0
        val poller = AttachmentExtractionPoller(
            fetch = {
                rounds++
                ProcessingResult.Error("HTTP 500")
            },
            delayFn = { delay.delay(it) }
        )

        val outcome = poller.poll("uuid-1", isVisible = { true })

        assertEquals(PollOutcome.Failed(3, "HTTP 500", 8_000L), outcome)
        assertEquals(3, rounds)
        assertEquals(listOf(3_000L, 5_000L), delay.waits)
    }

    @Test
    fun oneFailingMessageAmongSeveralDoesNotCountAsAFailureRound() = runBlocking {
        val delay = RecordingDelay()
        val poller = AttachmentExtractionPoller(
            // 两条消息：一条一直失败，一条立刻到终态 —— 这不是"整体失败"，还要继续问。
            fetch = { id ->
                if (id == "broken") ProcessingResult.Error("HTTP 502")
                else ProcessingResult.Success(detail(id, ExtractionStatus.DONE))
            },
            delayFn = { delay.delay(it) }
        )

        val outcome = poller.poll(listOf("broken", "ok"), isVisible = { true })

        assertTrue("应该因超时结束而不是失败结束：$outcome", outcome is PollOutcome.TimedOut)
        // 一直问到总预算耗尽为止（同"全部 pending"的情形），说明失败的那条没有让循环提前认输。
        assertTrue("等待次数应当不少：${delay.waits.size}", delay.waits.size > 5)
    }

    @Test
    fun aFetchThatRecoversCountsAsSuccess() = runBlocking {
        val delay = RecordingDelay()
        var round = 0
        val poller = AttachmentExtractionPoller(
            fetch = { id ->
                round++
                if (round <= 2) ProcessingResult.Error("网络抖了一下")
                else ProcessingResult.Success(detail(id, ExtractionStatus.DONE))
            },
            delayFn = { delay.delay(it) }
        )

        val outcome = poller.poll("uuid-1", isVisible = { true })

        assertEquals(PollOutcome.Settled(rounds = 3, waitedMillis = 8_000L), outcome)
        assertEquals(listOf(3_000L, 5_000L), delay.waits)
    }

    @Test
    fun attachmentsWithoutExtractionFieldAreTreatedAsSettled() = runBlocking {
        val delay = RecordingDelay()
        val poller = AttachmentExtractionPoller(
            fetch = { id ->
                ProcessingResult.Success(
                    MessageAttachmentDetail(
                        serverMessageId = id,
                        attachments = listOf(
                            ServerAttachment(key = "a/b/c.png", name = "c.png", mime = "image/png", size = 10)
                        )
                    )
                )
            },
            delayFn = { delay.delay(it) }
        )

        val outcome = poller.poll("uuid-1", isVisible = { true })

        assertEquals(PollOutcome.Settled(rounds = 1, waitedMillis = 0L), outcome)
        assertTrue(delay.waits.isEmpty())
    }
}
