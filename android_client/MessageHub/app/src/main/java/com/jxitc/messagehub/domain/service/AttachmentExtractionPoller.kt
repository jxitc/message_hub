package com.jxitc.messagehub.domain.service

import com.jxitc.messagehub.domain.model.MessageAttachmentDetail
import com.jxitc.messagehub.domain.model.ProcessingResult
import kotlinx.coroutines.delay

/**
 * 上传后轮询附件的提取状态，直到全部进入终态（或超时/页面不可见/连续失败）。
 *
 * 依赖全部以函数注入（取数、落库、等待、可见性判断），所以**整条循环能在 JVM 单测里跑**
 * —— 用假的 delay 记录等待序列，就能断言退避表真的是 3/5/10/20/30 秒，而不是"大概有退避"。
 *
 * 三条边界（都来自需求）：
 *  - **页面不可见就停**：每轮开始前查 [isVisible]，返回 false 立即退出；
 *    实际调用方还会在页面销毁时取消协程，正在 `delay` 的等待因此也会被中断。
 *  - **总时长上限 3 分钟**：等下一次之前先问 [ExtractionPollSchedule.hasBudgetFor]，
 *    不够就立刻收工（不是"睡过头再说"）。
 *  - **不做无延迟死循环**：每轮之间一定有一次等待（第一轮是上传后立刻查，那是必要的首个结果）。
 */
class AttachmentExtractionPoller(
    /** 取一条消息的附件现状（`GET /api/v1/messages/<id>`）。 */
    private val fetch: suspend (String) -> ProcessingResult<MessageAttachmentDetail>,
    /** 落库（离线也能看）。 */
    private val persist: suspend (MessageAttachmentDetail) -> Unit = {},
    /** 每轮结束后的钩子（例如刷新列表）。 */
    private val onRoundFinished: suspend () -> Unit = {},
    /** 等待函数，注入是为了单测不真的等。 */
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) {

    suspend fun poll(messageId: String, isVisible: () -> Boolean): PollOutcome =
        poll(listOf(messageId), isVisible)

    suspend fun poll(messageIds: List<String>, isVisible: () -> Boolean): PollOutcome {
        if (messageIds.isEmpty()) return PollOutcome.Settled(rounds = 0, waitedMillis = 0L)

        var attempt = 0
        var waited = 0L
        var rounds = 0
        var consecutiveFailureRounds = 0
        var lastError: String? = null

        while (true) {
            if (!isVisible()) {
                return PollOutcome.Stopped(rounds, PollOutcome.StopReason.SCREEN_HIDDEN, waited)
            }

            var stillPending = false
            var failedFetches = 0
            for (id in messageIds) {
                when (val result = fetch(id)) {
                    is ProcessingResult.Success -> {
                        persist(result.data)
                        if (result.data.hasPendingExtraction) stillPending = true
                    }
                    is ProcessingResult.Error -> {
                        failedFetches++
                        lastError = result.message
                        // 取不到不等于终态：下一轮再试。
                        stillPending = true
                    }
                    ProcessingResult.Loading -> stillPending = true
                }
            }
            rounds++
            onRoundFinished()

            consecutiveFailureRounds =
                if (failedFetches == messageIds.size) consecutiveFailureRounds + 1 else 0

            if (consecutiveFailureRounds >= ExtractionPollSchedule.MAX_CONSECUTIVE_FAILURES) {
                return PollOutcome.Failed(rounds, lastError ?: "状态查询连续失败", waited)
            }
            if (!stillPending) return PollOutcome.Settled(rounds, waited)

            val wait = ExtractionPollSchedule.delayForAttempt(attempt)
            if (!ExtractionPollSchedule.hasBudgetFor(waited, wait)) {
                return PollOutcome.TimedOut(rounds, waited)
            }
            delayFn(wait)
            waited += wait
            attempt++
        }
    }
}

/** 轮询结束的原因。UI/日志据此决定说什么（超时和"页面不可见"不是一回事）。 */
sealed interface PollOutcome {
    val rounds: Int
    val waitedMillis: Long

    /** 所有附件都进了终态。 */
    data class Settled(
        override val rounds: Int,
        override val waitedMillis: Long = 0L
    ) : PollOutcome

    /** 到了 3 分钟上限还没全部终态（服务端队列慢/引擎不可用），让用户手动刷新。 */
    data class TimedOut(
        override val rounds: Int,
        override val waitedMillis: Long
    ) : PollOutcome

    /** 页面不可见 → 主动停止（符合"不可见时停止轮询"）。 */
    data class Stopped(
        override val rounds: Int,
        val reason: StopReason,
        override val waitedMillis: Long = 0L
    ) : PollOutcome

    /** 连续若干轮全部失败（断网/服务器错误）。 */
    data class Failed(
        override val rounds: Int,
        val message: String,
        override val waitedMillis: Long = 0L
    ) : PollOutcome

    enum class StopReason { SCREEN_HIDDEN }
}
