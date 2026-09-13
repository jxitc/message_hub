package com.jxitc.messagehub.data.attachment

import com.jxitc.messagehub.domain.model.Attachment
import com.jxitc.messagehub.domain.model.AttachmentLimits
import com.jxitc.messagehub.domain.model.AttachmentPayload
import com.jxitc.messagehub.domain.service.AttachmentPolicy
import com.jxitc.messagehub.domain.service.CompressionOutcome
import com.jxitc.messagehub.domain.service.ImageCompression
import com.jxitc.messagehub.domain.service.ImageEncoder
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一个附件处理完的结果：可上传，或带着原因被拒绝。 */
sealed class AttachmentPrepResult {
    data class Accepted(val attachment: Attachment) : AttachmentPrepResult()
    data class Rejected(val fileName: String, val reason: String) : AttachmentPrepResult()
}

/**
 * 把用户选中的 URI 变成"可以直接上传的附件"，规则全部按契约来：
 *
 *  1. 类型必须在服务器允许清单里（清单来自 `GET /api/v1/attachments/limits`）；
 *  2. **图片 ≤ 上限 → 原样上传，不重新编码**；
 *  3. 图片 > 上限 → 长边缩到 2048 → JPEG q80，仍超就 q70/q60/q50，直到 ≤ 上限；
 *  4. 图片压到 q50 仍超 → 拒绝，并给出明确提示；
 *  5. **非图片（PDF 等）> 上限 → 直接拒绝**（不去"压缩"PDF，那只会损坏文件）；
 *  6. 超过 [AttachmentPolicy.MAX_READ_BYTES] 的文件连读都不读，直接拒绝。
 *
 * 读取与压缩都在 IO 线程，绝不占主线程。
 */
class AttachmentPreparer(
    private val reader: AttachmentReader,
    private val encoder: ImageEncoder = AndroidImageEncoder()
) {

    suspend fun prepare(uriString: String, limits: AttachmentLimits): AttachmentPrepResult =
        withContext(Dispatchers.IO) {
            when (val read = reader.read(uriString, AttachmentPolicy.MAX_READ_BYTES)) {
                is AttachmentReader.ReadResult.Failed ->
                    AttachmentPrepResult.Rejected(read.fileName, read.reason)
                is AttachmentReader.ReadResult.Ok -> classify(read.file, limits)
            }
        }

    private fun classify(file: AttachmentReader.SourceFile, limits: AttachmentLimits): AttachmentPrepResult {
        val name = file.fileName
        if (AttachmentPolicy.isDangerousExtension(name)) {
            return AttachmentPrepResult.Rejected(
                name,
                "出于安全考虑不支持 HTML/JS/SVG/XML 这类文件"
            )
        }
        if (!AttachmentPolicy.isAcceptableMime(file.mimeType, name, limits.allowedMimeTypes)) {
            return AttachmentPrepResult.Rejected(
                name,
                "不支持的类型（${file.mimeType.ifBlank { "未知" }}）；" +
                    "允许图片（PNG/JPEG/GIF/WebP）、PDF、纯文本"
            )
        }

        val maxBytes = limits.maxBytes
        return if (AttachmentPolicy.isImage(file.mimeType)) {
            prepareImage(file, maxBytes)
        } else {
            prepareNonImage(file, maxBytes)
        }
    }

    private fun prepareImage(file: AttachmentReader.SourceFile, maxBytes: Long): AttachmentPrepResult {
        val originalSize = file.bytes.size.toLong()
        if (!ImageCompression.shouldCompress(originalSize, maxBytes)) {
            // ≤ 上限：原样上传，不做任何有损处理
            return AttachmentPrepResult.Accepted(
                buildAttachment(file, file.bytes, originalSize, compressed = false)
            )
        }

        Logger.i(
            "AttachmentPreparer: compressing ${file.fileName} " +
                "(${AttachmentPolicy.formatSize(originalSize)} > ${AttachmentPolicy.formatSize(maxBytes)})"
        )
        return when (val outcome = ImageCompression.compressToLimit(file.bytes, encoder, maxBytes)) {
            is CompressionOutcome.Compressed -> {
                Logger.i(
                    "AttachmentPreparer: ${file.fileName} -> " +
                        "${AttachmentPolicy.formatSize(outcome.bytes.size.toLong())} " +
                        "(q${outcome.quality}, longEdge<=${outcome.longEdge})"
                )
                AttachmentPrepResult.Accepted(
                    buildAttachment(
                        file = file,
                        // 压缩后统一按 JPEG 发送：解码成 bitmap 再编码这一步本身已经是有损的，
                        // 继续声称是 PNG 只会让服务器/浏览器按错误的类型处理。
                        bytes = outcome.bytes,
                        mimeType = "image/jpeg",
                        originalSize = originalSize,
                        compressed = true
                    )
                )
            }
            is CompressionOutcome.TooLarge ->
                AttachmentPrepResult.Rejected(file.fileName, outcome.reason)
            is CompressionOutcome.Undecodable ->
                AttachmentPrepResult.Rejected(file.fileName, outcome.reason)
            is CompressionOutcome.Unchanged ->
                // compressToLimit 只在超标时调用，这里理论上到不了；真到了就按原图处理。
                AttachmentPrepResult.Accepted(buildAttachment(file, outcome.bytes, originalSize, false))
        }
    }

    private fun prepareNonImage(file: AttachmentReader.SourceFile, maxBytes: Long): AttachmentPrepResult {
        val size = file.bytes.size.toLong()
        if (size > maxBytes) {
            // PDF/文本没有"无损压小"的办法，硬压只会损坏内容 —— 直接拒绝，让用户换小文件。
            return AttachmentPrepResult.Rejected(
                file.fileName,
                "文件 ${AttachmentPolicy.formatSize(size)} 超过上限 " +
                    "${AttachmentPolicy.formatSize(maxBytes)}；" +
                    "这类文件无法在不损坏内容的前提下压缩，请换一个小一些的文件"
            )
        }
        return AttachmentPrepResult.Accepted(buildAttachment(file, file.bytes, size, compressed = false))
    }

    private fun buildAttachment(
        file: AttachmentReader.SourceFile,
        bytes: ByteArray,
        originalSize: Long,
        compressed: Boolean,
        mimeType: String = file.mimeType.ifBlank { AttachmentPolicy.MIME_FALLBACK }
    ): Attachment {
        // 压缩后的字节是 JPEG，文件名也跟着换成 .jpg —— 否则服务器/下载方会被 .png 误导。
        val fileName = if (compressed) file.fileName.withJpegExtension() else file.fileName
        return Attachment(
            uri = file.uri,
            fileName = fileName,
            mimeType = mimeType,
            kind = AttachmentPolicy.kindOf(mimeType),
            payload = AttachmentPayload(fileName = fileName, mimeType = mimeType, bytes = bytes),
            originalSizeBytes = originalSize,
            compressed = compressed
        )
    }

    private fun String.withJpegExtension(): String {
        if (AttachmentPolicy.mimeFromFileName(this) == "image/jpeg") return this
        val base = substringBeforeLast('.', this).ifBlank { this }
        return "$base.jpg"
    }
}
