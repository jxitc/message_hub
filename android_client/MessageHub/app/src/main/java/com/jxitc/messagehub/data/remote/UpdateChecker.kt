package com.jxitc.messagehub.data.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jxitc.messagehub.data.local.AppPreferences
import com.jxitc.messagehub.domain.model.ProcessingResult
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内自动更新（自建 OTA）。
 *
 * 流程：`GET /api/v1/releases/latest-info` → 比较 versionCode → 下载 APK 到
 * cacheDir/updates/ → FileProvider + ACTION_VIEW 拉起系统安装器。
 *
 * 服务器侧的 latest.json 由 `scripts/publish-apk.sh` 在每次发布时写入。
 *
 * 限制：Android 不允许 App 静默自升级，最后一步必须由用户在系统安装器里点"安装"
 * （除非是设备所有者/MDM）。我们能做的是把"找地址-下载-点开"这三步省掉。
 */
class UpdateChecker(
    private val context: Context,
    private val preferences: AppPreferences,
) {

    data class ReleaseInfo(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val sizeMb: Double,
        val notes: String,
    )

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // APK 可能几十 MB
            .build()
    }

    /** 当前已安装版本，例如 "1.0 (2)"。 */
    fun currentVersion(): Pair<String, Int> = try {
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt() else pi.versionCode
        (pi.versionName ?: "?") to code
    } catch (_: Throwable) {
        "?" to 0
    }

    /**
     * 检查服务器上是否有更新。
     * @return Success(null) = 已是最新；Success(info) = 有新版本
     */
    suspend fun checkForUpdate(): ProcessingResult<ReleaseInfo?> = withContext(Dispatchers.IO) {
        try {
            val base = preferences.serverUrl.trimEnd('/')
            if (base.isBlank()) return@withContext ProcessingResult.Error("Server URL not configured")

            val request = Request.Builder()
                .url("$base/api/v1/releases/latest-info")
                .apply { if (preferences.apiKey.isNotBlank()) header("X-API-Key", preferences.apiKey) }
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ProcessingResult.Error("HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val remoteCode = json.optInt("version_code", 0)
                val remoteName = json.optString("version_name", "?")
                val filename = json.optString("filename", "")
                val url = json.optString("download_url").ifBlank {
                    if (filename.isNotBlank()) "/api/v1/releases/$filename" else ""
                }

                if (url.isBlank()) return@withContext ProcessingResult.Error("Release metadata has no download URL")

                val (_, localCode) = currentVersion()
                Logger.i("UpdateChecker", "local=$localCode remote=$remoteCode ($remoteName)")

                if (remoteCode <= localCode) {
                    ProcessingResult.Success(null)   // 已是最新
                } else {
                    ProcessingResult.Success(
                        ReleaseInfo(
                            versionName = remoteName,
                            versionCode = remoteCode,
                            downloadUrl = url,
                            sizeMb = json.optDouble("size_mb", 0.0),
                            notes = json.optString("notes", ""),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Logger.e("UpdateChecker", "checkForUpdate failed: ${e.message}")
            ProcessingResult.Error("Check failed: ${e.message}")
        }
    }

    /** 下载 APK 到私有 cache 目录。onProgress 回调百分比 0..100。 */
    suspend fun downloadApk(
        info: ReleaseInfo,
        onProgress: (Int) -> Unit = {},
    ): ProcessingResult<File> = withContext(Dispatchers.IO) {
        try {
            val base = preferences.serverUrl.trimEnd('/')
            val url = if (info.downloadUrl.startsWith("http")) info.downloadUrl else "$base${info.downloadUrl}"

            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(dir, "messagehub-${info.versionCode}.apk")
            if (outFile.exists()) outFile.delete()

            val request = Request.Builder().url(url)
                .apply { if (preferences.apiKey.isNotBlank()) header("X-API-Key", preferences.apiKey) }
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ProcessingResult.Error("Download failed: HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext ProcessingResult.Error("Empty response body")
                val total = body.contentLength()

                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            copied += n
                            if (total > 0) {
                                val pct = ((copied * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }
            Logger.i("UpdateChecker", "downloaded ${outFile.length()} bytes -> ${outFile.name}")
            ProcessingResult.Success(outFile)
        } catch (e: Exception) {
            Logger.e("UpdateChecker", "downloadApk failed: ${e.message}")
            ProcessingResult.Error("Download failed: ${e.message}")
        }
    }

    /** 是否已被允许安装未知来源应用（Android 8+ 需要，每个来源应用单独授权）。 */
    fun canInstallPackages(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    } catch (_: Throwable) {
        true
    }

    /** 跳到"安装未知应用"授权页（用户点一下允许后才能装）。 */
    fun openInstallPermissionSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Logger.w("UpdateChecker", "cannot open install permission settings: ${e.message}")
        }
    }

    /**
     * 拉起系统安装器。用户仍需在系统界面点"安装"（Android 不允许静默自升级）。
     */
    fun installApk(file: File): ProcessingResult<Unit> = try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        ProcessingResult.Success(Unit)
    } catch (e: Exception) {
        Logger.e("UpdateChecker", "installApk failed: ${e.message}")
        ProcessingResult.Error("Cannot start installer: ${e.message}")
    }
}
