package com.jxitc.messagehub.domain.service

/**
 * 图片压缩的**纯逻辑**，零 Android 依赖。
 *
 * 解码 / 缩放 / JPEG 编码这些必须靠 BitmapFactory 的动作，通过 [ImageEncoder] 注入，
 * 于是"什么时候压、压到哪一档、压不动怎么办"这套判断可以完整地在 JVM 单测里跑
 * （见 ImageCompressionTest）。
 */
sealed class CompressionOutcome {
    /**
     * 原图已经 ≤ 上限：**原样上传，不重新编码**。
     * 重新编码一次 JPEG 是一次白送的有损处理 + 丢掉 EXIF，没有任何好处。
     */
    class Unchanged(val bytes: ByteArray) : CompressionOutcome() {
        override fun toString() = "Unchanged(${bytes.size} bytes)"
    }

    /** 压缩成功：[bytes] 是 JPEG 字节，[quality] 是最终生效的质量，[longEdge] 是长边像素上限。 */
    class Compressed(
        val bytes: ByteArray,
        val quality: Int,
        val longEdge: Int
    ) : CompressionOutcome() {
        override fun toString() = "Compressed(${bytes.size} bytes, q$quality, longEdge=$longEdge)"
    }

    /** 质量阶梯降到最低仍然超限（极端情况）：拒绝该文件，并说清原因。 */
    class TooLarge(val finalBytes: Long, val maxBytes: Long) : CompressionOutcome() {
        val reason: String
            get() = "图片压缩后仍有 ${AttachmentPolicy.formatSize(finalBytes)}，" +
                "超过上限 ${AttachmentPolicy.formatSize(maxBytes)}，请换更小的图片或先裁剪"

        override fun toString() = "TooLarge($finalBytes > $maxBytes)"
    }

    /** 不是能解码的图片（文件损坏 / 其实是别的格式）。 */
    class Undecodable(val reason: String) : CompressionOutcome() {
        override fun toString() = "Undecodable($reason)"
    }
}

/**
 * 把一张图按给定长边上限与 JPEG 质量重新编码。失败（解码不了）返回 null。
 * 实现见 `data/attachment/AndroidImageEncoder`。
 */
fun interface ImageEncoder {
    fun encode(source: ByteArray, maxLongEdge: Int, quality: Int): ByteArray?
}

object ImageCompression {

    /** 需要压缩吗？**恰好等于上限不算超**（≤ 上限一律原样上传）。 */
    fun shouldCompress(originalSizeBytes: Long, maxBytes: Long = AttachmentPolicy.DEFAULT_MAX_BYTES): Boolean =
        originalSizeBytes > maxBytes

    /**
     * 超过上限的图片：降采样解码 →（长边缩到 [maxLongEdge]）→ 用 [qualities] 逐级试
     * JPEG 质量，第一个 ≤ [maxBytes] 的结果即为答案；全部试完仍超标就返回
     * [CompressionOutcome.TooLarge]（调用方据此拒绝该文件）。
     *
     * @param source 原始文件字节（未解码）
     * @param maxBytes 单文件上限（来自 `GET /api/v1/attachments/limits`）
     * @param maxLongEdge 长边像素上限（2048）
     * @param qualities JPEG 质量阶梯（80 → 70 → 60 → 50）
     */
    fun compressToLimit(
        source: ByteArray,
        encoder: ImageEncoder,
        maxBytes: Long = AttachmentPolicy.DEFAULT_MAX_BYTES,
        maxLongEdge: Int = AttachmentPolicy.MAX_IMAGE_LONG_EDGE,
        qualities: List<Int> = AttachmentPolicy.JPEG_QUALITY_LADDER
    ): CompressionOutcome {
        if (source.size <= maxBytes) return CompressionOutcome.Unchanged(source)

        var lastAttempt: ByteArray? = null
        var lastQuality = 0
        for (quality in qualities) {
            val encoded = encoder.encode(source, maxLongEdge, quality) ?: continue
            lastAttempt = encoded
            lastQuality = quality
            if (encoded.size <= maxBytes) {
                return CompressionOutcome.Compressed(encoded, lastQuality, maxLongEdge)
            }
        }

        val best = lastAttempt
            ?: return CompressionOutcome.Undecodable("图片解码失败（文件可能已损坏或不是图片）")
        return CompressionOutcome.TooLarge(best.size.toLong(), maxBytes)
    }

    /**
     * 2 的幂次降采样系数：解码时就按这个比例缩，避免一张 8000px 的图先解码成
     * 巨大 bitmap 再缩放（那一步最容易 OOM）。
     *
     * 取"解码后长边仍 ≥ [maxLongEdge]"的最大 2 次幂——宁可解大一点，
     * 之后再用 createScaledBitmap 精确缩到 2048（这一步的缩放质量比降采样好）。
     */
    fun sampleSizeFor(width: Int, height: Int, maxLongEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxLongEdge <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxLongEdge) {
            sample *= 2
        }
        return sample
    }

    /** 精确缩放后长边的像素数：只缩不放；上限非法/尺寸为 0 时至少给 1px。 */
    fun targetLongEdge(width: Int, height: Int, maxLongEdge: Int): Int =
        (maxOf(width, height).coerceAtMost(maxLongEdge)).coerceAtLeast(1)
}
