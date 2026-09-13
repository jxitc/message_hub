package com.jxitc.messagehub.domain.service

/**
 * 提取状态的轮询节奏（**纯规则，有单测**）。
 *
 * 服务端的提取是异步的（后台线程 30s 轮询取待办），一张扫描件可能要几十秒，
 * 所以客户端上传后必须**退避着问**，不能无延迟死循环：
 *
 * ```
 * 第 1 次（上传后立刻）→ 3s → 5s → 10s → 20s → 30s → 30s … （总计上限 3 分钟）
 * ```
 *
 * 上限 3 分钟的理由：服务端提取队列本身是 30s 一轮，超过 3 分钟还没结果的，
 * 多半是卡在队列里或引擎不可用 —— 继续问既费电又费流量，不如停下让用户手动刷新。
 */
object ExtractionPollSchedule {

    /** 前几次的退避阶梯（毫秒）。 */
    val BACKOFF_MILLIS = listOf(3_000L, 5_000L, 10_000L, 20_000L)

    /** 阶梯走完后的稳定间隔。 */
    const val STEADY_INTERVAL_MILLIS = 30_000L

    /** 一次轮询会话的总时长上限。 */
    const val MAX_TOTAL_MILLIS = 3 * 60 * 1000L

    /** 连续失败多少次就放弃（网络断/服务器 5xx 时不要一直重试）。 */
    const val MAX_CONSECUTIVE_FAILURES = 3

    /** 第 [attempt] 次等待（attempt 从 0 开始）：3s, 5s, 10s, 20s, 30s, 30s …。 */
    fun delayForAttempt(attempt: Int): Long {
        if (attempt < 0) return BACKOFF_MILLIS.first()
        return if (attempt < BACKOFF_MILLIS.size) BACKOFF_MILLIS[attempt] else STEADY_INTERVAL_MILLIS
    }

    /** 已花 [elapsedMillis] 后还能否再等 [delayMillis]（超出总预算就别等了，直接收工）。 */
    fun hasBudgetFor(elapsedMillis: Long, delayMillis: Long): Boolean =
        elapsedMillis + delayMillis <= MAX_TOTAL_MILLIS
}
