package com.jxitc.messagehub.data.attachment

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.jxitc.messagehub.data.remote.MessageHubApiClient
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.domain.model.ServerAttachment
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 拉取附件**原件**（非图片附件要打开就得先落地，图片缩略图走图片库那套）。
 *
 * 两条规则：
 *  - 地址用 `key` + 配置的 serverUrl 拼（[MessageHubApiClient.blobUrlFor]），不用接口的 `url`；
 *  - 存到 `cacheDir/attachments/`（可被系统回收，**不进 Room、不进备份**），
 *    然后靠 FileProvider 以 `content://` 交给系统查看器 —— 应用内更新用的也是同一个 provider。
 */
class AttachmentDownloader(
    private val context: Context,
    private val apiClient: MessageHubApiClient
) {

    suspend fun download(attachment: ServerAttachment): ProcessingResult<File> =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
                val target = File(dir, cacheFileName(attachment))
                if (target.exists() && target.length() > 0) {
                    return@withContext ProcessingResult.Success(target)
                }

                val url = apiClient.blobUrlFor(attachment.key)
                Logger.i("Downloading attachment original: ${attachment.name} (${attachment.readableSize})")
                val request = Request.Builder().url(url).get().build()
                apiClient.httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ProcessingResult.Error("下载失败：HTTP ${response.code}")
                    }
                    val body = response.body
                        ?: return@withContext ProcessingResult.Error("下载失败：响应为空")
                    // 先写临时文件再改名：中途断网不会留下一个"看起来完整"的半截文件，
                    // 下次进来还能重下。
                    val tmp = File(dir, target.name + ".part")
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        return@withContext ProcessingResult.Error("下载失败：无法保存文件")
                    }
                }
                ProcessingResult.Success(target)
            } catch (e: Exception) {
                Logger.w("Attachment download failed: ${e.message}")
                ProcessingResult.Error("下载失败：${e.message}")
            }
        }

    /** 用系统查看器打开已下载的原件；没有可处理的应用时返回 null（调用方提示用户）。 */
    fun viewIntent(file: File, attachment: ServerAttachment): Intent? {
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mime.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }.takeIf { it.resolveActivity(context.packageManager) != null }
        } catch (e: Exception) {
            Logger.w("No viewer for attachment: ${e.message}")
            null
        }
    }

    /**
     * 缓存文件名：`<sha256 前 8 位>_<清理过的原名>`。
     *
     * 前缀用内容哈希保证同名文件不互相覆盖；原名只做展示用途，**必须清理** ——
     * 服务端来的名字是用户可控的，`../` 这类字符不能进路径。
     */
    private fun cacheFileName(attachment: ServerAttachment): String {
        val prefix = attachment.sha256?.take(8)
            ?: Integer.toHexString(attachment.key.hashCode())
        val safeName = attachment.name
            .replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
            .trim('.', '_', ' ')
            .takeLast(80)
            .ifBlank { "attachment" }
        return "${prefix}_$safeName"
    }

    companion object {
        /** 与 res/xml/file_paths.xml 里的 `<cache-path name="attachments" .../>` 对应。 */
        const val CACHE_DIR_NAME = "attachments"
    }
}
