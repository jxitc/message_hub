package com.jxitc.messagehub.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * NotificationDeduplicator 的单元测试。
 *
 * 重点是 [concurrentBurstOnlyOnePasses]：它复现 tasks.md 3.4.12 / Dedup Issue 1 的
 * 线上故障——闹钟通知一次入库 7 条完全相同记录。原实现"读-比-写"非原子，一批并发
 * 到达的相同通知全部通过；修复后用 synchronized 串行化，只有第一条放行。
 */
class NotificationDeduplicatorTest {

    @Test
    fun consecutiveDuplicatesCollapseToOne() {
        val d = NotificationDeduplicator()
        assertFalse("第一条应放行", d.isDuplicate("com.android.deskclock", "起床", "07:30 的闹钟即将响铃"))
        assertTrue("紧邻重复应丢弃 1", d.isDuplicate("com.android.deskclock", "起床", "07:30 的闹钟即将响铃"))
        assertTrue("紧邻重复应丢弃 2", d.isDuplicate("com.android.deskclock", "起床", "07:30 的闹钟即将响铃"))
    }

    @Test
    fun interruptedByOtherAppIsKept() {
        // A B A → 3 条：B 打断后 A 重新开始，不做跨来源去重
        val d = NotificationDeduplicator()
        assertFalse(d.isDuplicate("pkg.a", "t", "c"))
        assertFalse(d.isDuplicate("pkg.b", "t", "c"))
        assertFalse(d.isDuplicate("pkg.a", "t", "c"))
    }

    @Test
    fun differentContentIsKept() {
        val d = NotificationDeduplicator()
        assertFalse(d.isDuplicate("pkg.a", "t", "c1"))
        assertFalse(d.isDuplicate("pkg.a", "t", "c2"))
    }

    /**
     * 并发 burst：模拟 NotificationListener 对每个通知起协程并发调用。
     * 100 个线程同时投递完全相同的通知，只能有 1 条被放行。
     */
    @Test
    fun concurrentBurstOnlyOnePasses() {
        val d = NotificationDeduplicator()
        val threads = 100
        val pool = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val passed = AtomicInteger(0)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.submit {
                try {
                    startGate.await() // 尽量同时开跑，制造竞态窗口
                    if (!d.isDuplicate("com.android.deskclock", "起床", "07:30 的闹钟即将响铃")) {
                        passed.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        startGate.countDown() // 放闸
        assertTrue("并发测试超时", done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals("100 个并发相同通知应只放行 1 条", 1, passed.get())
    }
}
