package com.jxitc.messagehub.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 附件状态轮询的退避时间表（纯规则）。 */
class ExtractionPollScheduleTest {

    @Test
    fun backoffIsThreeFiveTenTwentyThenThirtySeconds() {
        assertEquals(3_000L, ExtractionPollSchedule.delayForAttempt(0))
        assertEquals(5_000L, ExtractionPollSchedule.delayForAttempt(1))
        assertEquals(10_000L, ExtractionPollSchedule.delayForAttempt(2))
        assertEquals(20_000L, ExtractionPollSchedule.delayForAttempt(3))
        // 阶梯走完后稳定在 30s，不会退化回短间隔（否则等于无退避地打服务器）。
        assertEquals(30_000L, ExtractionPollSchedule.delayForAttempt(4))
        assertEquals(30_000L, ExtractionPollSchedule.delayForAttempt(5))
        assertEquals(30_000L, ExtractionPollSchedule.delayForAttempt(100))
    }

    @Test
    fun negativeAttemptFallsBackToTheFirstDelay() {
        assertEquals(3_000L, ExtractionPollSchedule.delayForAttempt(-1))
    }

    @Test
    fun totalBudgetIsAboutThreeMinutes() {
        assertEquals(180_000L, ExtractionPollSchedule.MAX_TOTAL_MILLIS)
        assertTrue(ExtractionPollSchedule.hasBudgetFor(0L, 3_000L))
        assertTrue(ExtractionPollSchedule.hasBudgetFor(150_000L, 30_000L))
        // 正好用尽可以等；超一点就不再等了。
        assertTrue(ExtractionPollSchedule.hasBudgetFor(150_000L, 30_000L))
        assertFalse(ExtractionPollSchedule.hasBudgetFor(155_000L, 30_000L))
        assertFalse(ExtractionPollSchedule.hasBudgetFor(180_000L, 3_000L))
    }

    @Test
    fun noDelayInTheScheduleIsZero() {
        // 不允许出现 0 延迟 —— 那就是死循环。
        (0..20).forEach { attempt ->
            assertTrue(
                "attempt=$attempt 的等待必须为正",
                ExtractionPollSchedule.delayForAttempt(attempt) > 0L
            )
        }
    }

    @Test
    fun consecutiveFailureLimitIsSmallButNotOne() {
        assertEquals(3, ExtractionPollSchedule.MAX_CONSECUTIVE_FAILURES)
    }
}
