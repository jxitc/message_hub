package com.jxitc.messagehub.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jxitc.messagehub.domain.service.AttachmentPolicy
import com.jxitc.messagehub.utils.Logger
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 从 content:// 读出附件：文件名、类型、声明大小、字节。
 *
 * 只做读取与基础校验，不认识"图片要压缩"这类业务规则 —— 那些在
 * [AttachmentPreparer] / `domain.service.ImageCompression` 里。
 */
class AttachmentReader(private val context: Context) {

    /** 读出来的一份原始文件。 */
    class SourceFile(
        val uri: String,
        val fileName: String,
        val mimeType: String,
        /** 来自 OpenableColumns.SIZE；有些 provider 不给，此时为 null。 */
        val declaredSizeBytes: Long?,
        val bytes: ByteArray
    )

    sealed class ReadResult {
        class Ok(val file: SourceFile) : ReadResult()
        class Failed(val fileName: String, val reason: String) : ReadResult()
    }

    /** 读文件超过上限时抛这个，交给调用方翻译成给用户看的话。 */
    private class TooLargeException(val read: Long) : IOException("file exceeds read cap: $read")

    suspend fun read(uriString: String, maxReadBytes: Long = AttachmentPolicy.MAX_READ_BYTES): ReadResult {
        val fileName = displayName(uriString)
        return try {
            val uri = Uri.parse(uriString)
            val declaredSize = declaredSize(uri)
            if (declaredSize != null && declaredSize > maxReadBytes) {
                return ReadResult.Failed(
                    fileName,
                    "文件太大（${AttachmentPolicy.formatSize(declaredSize)}），" +
                        "超过可处理上限 ${AttachmentPolicy.formatSize(maxReadBytes)}，请换小文件"
                )
            }
            val mime = AttachmentPolicy.normalizeMime(resolverType(uri))
                .ifBlank { AttachmentPolicy.mimeFromFileName(fileName) }
            val bytes = readBytes(uri, maxReadBytes)
            ReadResult.Ok(SourceFile(uriString, fileName, mime, declaredSize, bytes))
        } catch (e: TooLargeException) {
            ReadResult.Failed(
                fileName,
                "文件太大（已读 ${AttachmentPolicy.formatSize(e.read)}），" +
                    "超过可处理上限 ${AttachmentPolicy.formatSize(maxReadBytes)}，请换小文件"
            )
        } catch (e: SecurityException) {
            Logger.w("AttachmentReader: no permission for $uriString: ${e.message}")
            ReadResult.Failed(fileName, "没有读取这个文件的权限，请重新选择")
        } catch (e: Exception) {
            Logger.w("AttachmentReader: failed to read $uriString: ${e.message}")
            ReadResult.Failed(fileName, "读取失败：${e.message ?: "未知错误"}")
        }
    }

    private fun resolverType(uri: Uri): String? = try {
        context.contentResolver.getType(uri)
    } catch (e: Exception) {
        null
    }

    /** OpenableColumns.DISPLAY_NAME，取不到就用 URI 末段。 */
    private fun displayName(uriString: String): String {
        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            return "attachment"
        }
        val fromProvider = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        } catch (e: Exception) {
            null
        }
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "attachment"
    }

    private fun declaredSize(uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
            }
    } catch (e: Exception) {
        null
    }

    /** 带上限地读完整个流：超过上限立刻中止，不把几百 MB 读进内存。 */
    private fun readBytes(uri: Uri, cap: Long): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开输入流")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > cap) throw TooLargeException(total)
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
