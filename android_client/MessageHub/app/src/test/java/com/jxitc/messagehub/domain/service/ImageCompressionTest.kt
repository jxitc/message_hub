package com.jxitc.messagehub.domain.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯函数：图片压缩策略。
 *
 * 这里用一个「假编码器」代替 BitmapFactory —— 它按 (长边, 质量) 造出确定大小的字节，
 * 于是"什么时候压、压到哪一档、压不动怎么办"这套逻辑可以被精确断言，
 * 不需要模拟器也不需要真机。
 */
class ImageCompressionTest {

    private val maxBytes = AttachmentPolicy.DEFAULT_MAX_BYTES

    /** 假编码器：把"质量越低越小"这件事变成可计算的字节数。 */
    private class FakeEncoder(
        private val bytesFor: (maxLongEdge: Int, quality: Int) -> Int,
        private val failForQuality: Set<Int> = emptySet()
    ) : ImageEncoder {
        val calls = mutableListOf<Pair<Int, Int>>() // (maxLongEdge, quality)

        override fun encode(source: ByteArray, maxLongEdge: Int, quality: Int): ByteArray? {
            calls += maxLongEdge to quality
            if (quality in failForQuality) return null
            return ByteArray(bytesFor(maxLongEdge, quality))
        }
    }

    private fun image(size: Int) = ByteArray(size)

    private fun compressedOf(outcome: CompressionOutcome): CompressionOutcome.Compressed {
        assertTrue("expected Compressed but was $outcome", outcome is CompressionOutcome.Compressed)
        return outcome as CompressionOutcome.Compressed
    }

    // ---------------------------------------------------------------- 该不该压

    @Test
    fun shouldCompress_falseAtOrBelowTheLimit() {
        assertFalse(ImageCompression.shouldCompress(0L, maxBytes))
        assertFalse(ImageCompression.shouldCompress(900_000L, maxBytes))
        // 边界：恰好等于上限 —— 原样上传，不重新编码
        assertFalse(ImageCompression.shouldCompress(maxBytes, maxBytes))
    }

    @Test
    fun shouldCompress_trueAboveTheLimit() {
        assertTrue(ImageCompression.shouldCompress(maxBytes + 1, maxBytes))
        assertTrue(ImageCompression.shouldCompress(8_000_000L, maxBytes))
    }

    // ---------------------------------------------------------------- 原样上传

    @Test
    fun compressToLimit_leavesSmallEnoughImageUntouched() {
        val encoder = FakeEncoder({ _, _ -> 10 })
        val original = image(500_000)

        val outcome = ImageCompression.compressToLimit(original, encoder)

        assertTrue(outcome is CompressionOutcome.Unchanged)
        // 关键点：**没有调用编码器** —— 不重新编码，避免一次白送的有损处理
        assertTrue(encoder.calls.isEmpty())
        assertSame(original, (outcome as CompressionOutcome.Unchanged).bytes)
    }

    @Test
    fun compressToLimit_treatsExactlyOneMegabyteAsSmallEnough() {
        val encoder = FakeEncoder({ _, _ -> 10 })
        val outcome = ImageCompression.compressToLimit(image(maxBytes.toInt()), encoder)
        assertTrue(outcome is CompressionOutcome.Unchanged)
        assertTrue(encoder.calls.isEmpty())
    }

    // ---------------------------------------------------------------- 质量阶梯

    @Test
    fun compressToLimit_returnsTheFirstQualityThatFits() {
        // q80 出来 2MB（超标），q70 出来 800KB（合格）
        val encoder = FakeEncoder({ _, quality -> if (quality == 80) 2_000_000 else 800_000 })
        val outcome = compressedOf(ImageCompression.compressToLimit(image(4_000_000), encoder))

        assertEquals(70, outcome.quality)
        assertEquals(800_000, outcome.bytes.size)
        assertEquals(2048, outcome.longEdge)
        // 只试了两档就停：70 合格之后不该再试 60/50
        assertEquals(listOf(2048 to 80, 2048 to 70), encoder.calls)
    }

    @Test
    fun compressToLimit_walksTheWholeLadderDownToFifty() {
        val encoder = FakeEncoder({ _, quality ->
            when (quality) {
                80 -> 3_000_000
                70 -> 2_000_000
                60 -> 1_500_000
                else -> 900_000
            }
        })
        val outcome = compressedOf(ImageCompression.compressToLimit(image(9_000_000), encoder))

        assertEquals(50, outcome.quality)
        assertEquals(listOf(80, 70, 60, 50), encoder.calls.map { it.second })
        assertTrue(encoder.calls.all { it.first == 2048 })
    }

    @Test
    fun compressToLimit_usesTheConfiguredLadder() {
        val encoder = FakeEncoder({ _, _ -> 100 })
        val outcome = compressedOf(
            ImageCompression.compressToLimit(image(2_000_000), encoder, qualities = listOf(95))
        )
        assertEquals(95, outcome.quality)
        assertEquals(listOf(2048 to 95), encoder.calls)
    }

    @Test
    fun compressToLimit_honoursACustomLongEdge() {
        val encoder = FakeEncoder({ _, _ -> 100 })
        compressedOf(
            ImageCompression.compressToLimit(
                image(2_000_000), encoder, maxLongEdge = 1600
            )
        )
        assertEquals(listOf(1600 to 80), encoder.calls)
    }

    // ---------------------------------------------------------------- 极端情况：拒绝

    @Test
    fun compressToLimit_rejectsWhenEvenTheLowestQualityIsTooLarge() {
        val encoder = FakeEncoder({ _, _ -> 1_500_000 })
        val outcome = ImageCompression.compressToLimit(image(8_000_000), encoder)

        assertTrue("expected TooLarge but was $outcome", outcome is CompressionOutcome.TooLarge)
        val tooLarge = outcome as CompressionOutcome.TooLarge
        assertEquals(1_500_000L, tooLarge.finalBytes)
        assertEquals(maxBytes, tooLarge.maxBytes)
        // 整条阶梯都试过了才拒绝
        assertEquals(listOf(80, 70, 60, 50), encoder.calls.map { it.second })
        // 提示里要报出真实数字，用户才知道差多少
        assertTrue(tooLarge.reason, tooLarge.reason.contains("1.4 MB"))
        assertTrue(tooLarge.reason, tooLarge.reason.contains("1 MB"))
    }

    @Test
    fun compressToLimit_reportsUndecodableWhenEncoderAlwaysFails() {
        val encoder = FakeEncoder({ _, _ -> 0 }, failForQuality = setOf(80, 70, 60, 50))
        val outcome = ImageCompression.compressToLimit(image(3_000_000), encoder)
        assertTrue("expected Undecodable but was $outcome", outcome is CompressionOutcome.Undecodable)
    }

    @Test
    fun compressToLimit_keepsTryingAfterASingleEncoderFailure() {
        // q80 编码失败（比如解码读不动），q70 成功 —— 不该因为一档失败就整体放弃
        val encoder = FakeEncoder(
            { _, quality -> if (quality == 80) 0 else 700_000 },
            failForQuality = setOf(80)
        )
        val outcome = compressedOf(ImageCompression.compressToLimit(image(3_000_000), encoder))
        assertEquals(70, outcome.quality)
    }

    @Test
    fun compressToLimit_returnsTheEncodedBytes() {
        val encoded = ByteArray(1024) { 7 }
        val encoder = object : ImageEncoder {
            override fun encode(source: ByteArray, maxLongEdge: Int, quality: Int) = encoded
        }
        val outcome = compressedOf(ImageCompression.compressToLimit(image(5_000_000), encoder))
        assertArrayEquals(encoded, outcome.bytes)
    }

    // ---------------------------------------------------------------- 降采样系数

    @Test
    fun sampleSizeFor_keepsDecodedLongEdgeAtOrAboveTarget() {
        assertEquals(2, ImageCompression.sampleSizeFor(8000, 6000, 2048))
        assertEquals(1, ImageCompression.sampleSizeFor(2048, 1536, 2048))
        assertEquals(1, ImageCompression.sampleSizeFor(1000, 800, 2048))
        assertEquals(8, ImageCompression.sampleSizeFor(16384, 12288, 2048))
    }

    @Test
    fun sampleSizeFor_isSafeOnDegenerateInput() {
        assertEquals(1, ImageCompression.sampleSizeFor(0, 0, 2048))
        assertEquals(1, ImageCompression.sampleSizeFor(-1, 100, 2048))
        assertEquals(1, ImageCompression.sampleSizeFor(100, 100, 0))
    }

    @Test
    fun targetLongEdge_neverUpscales() {
        assertEquals(2048, ImageCompression.targetLongEdge(8000, 6000, 2048))
        assertEquals(1500, ImageCompression.targetLongEdge(1500, 1000, 2048))
        assertEquals(1, ImageCompression.targetLongEdge(0, 0, 2048))
    }
}
