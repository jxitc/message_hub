package com.jxitc.messagehub.data.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jxitc.messagehub.domain.service.ImageCompression
import com.jxitc.messagehub.domain.service.ImageEncoder
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * [ImageEncoder] 的 Android 实现：降采样解码 → 长边缩到 maxLongEdge → JPEG 编码。
 *
 * 任何一步失败都返回 null（交给上层按"解码失败"处理），这里不抛异常：
 * 一张读不动的图不该让整个提交流程崩掉。
 */
class AndroidImageEncoder : ImageEncoder {

    override fun encode(source: ByteArray, maxLongEdge: Int, quality: Int): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = ImageCompression.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null

            val decodedLongEdge = maxOf(bitmap.width, bitmap.height)
            if (decodedLongEdge > maxLongEdge) {
                val targetLongEdge = ImageCompression.targetLongEdge(bitmap.width, bitmap.height, maxLongEdge)
                val scale = targetLongEdge.toFloat() / decodedLongEdge
                val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }

            val output = ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            bitmap.recycle()
            if (!ok) null else output.toByteArray()
        } catch (t: Throwable) {
            // OutOfMemoryError 也在内：宁可不压、由上层拒绝这个文件，也不要崩。
            null
        }
    }
}
