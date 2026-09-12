package com.jxitc.messagehub.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.jxitc.messagehub.data.local.AppPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 崩溃采集与上报。
 *
 * 目标：**崩了也能远程知道**，不必再用 USB + adb logcat。两条采集路径：
 *
 *  1. Java 未捕获异常 —— 装 [Thread.setDefaultUncaughtExceptionHandler]，
 *     崩溃瞬间把堆栈写进本地待传队列（写文件，不依赖网络）。
 *  2. native 崩溃 / ANR / 低内存 —— 从 [ApplicationExitInfo] 读回（Android 11+，无需 root）。
 *     native 崩溃（例如 SIGABRT）拿不到 Java 堆栈，只有这条路能拿到系统 tombstone 文本。
 *     注：本次线上事故就是 native abort（Android 16 日志分片 ubsan 溢出），
 *     正是靠这条路径才能看到真相。
 *
 * 上报：下次启动时把队列 POST 到 `/api/v1/diagnostics/crashes`，成功后删除。
 * 失败的留在队列里下次重试。已处理过的 ApplicationExitInfo 记录在 SharedPreferences
 * 里，避免每次启动重复上报同一条历史崩溃。
 *
 * 稳定性要求：本类在崩溃路径上执行，**任何异常都不能外泄**（否则雪崩），
 * 所以每个环节都用 try/catch 兜住，且不经过 Logger 的长文本截断（堆栈直写文件）。
 */
object CrashReporter {

    private const val PREFS = "crash_reporter"
    private const val KEY_HANDLED_EXITS = "handled_exits"   // Set<String>: "<timestamp>:<reason>"
    private const val PENDING_DIR = "crash_pending"         // files/crash_pending/*.json
    private const val TAG = "CrashReporter"
    private const val MAX_TRACE_CHARS = 200_000             // 保护：异常大的 trace 截断

    /** 由 install() 注入；崩溃回调里用它取 deviceId（每台设备唯一、可读）。 */
    @Volatile private var prefs: AppPreferences? = null

    private val http by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)   // 同上：301 当作错误
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // ------------------------------------------------------------------
    // 1) Java 未捕获异常
    // ------------------------------------------------------------------

    /** 在 Application.onCreate 调用一次。 */
    fun install(context: Context, preferences: AppPreferences) {
        prefs = preferences
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = throwable.stackTraceToString().take(MAX_TRACE_CHARS)
                enqueue(
                    app,
                    reason = "CRASH_JAVA",
                    reasonCode = null,
                    summary = "${throwable.javaClass.name}: ${throwable.message}".take(500),
                    stacktrace = "Thread: ${thread.name}\n$trace",
                    occurredAtMillis = System.currentTimeMillis(),
                )
            } catch (_: Throwable) {
                // 崩溃处理路径绝不能再次抛出
            }
            // 交回原处理器，保持系统默认行为（进程照常结束）
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ------------------------------------------------------------------
    // 2) ApplicationExitInfo：native 崩溃 / ANR / 被杀
    // ------------------------------------------------------------------

    /** 收集 [ApplicationExitInfo] 里尚未处理过的崩溃，写进待传队列。 */
    fun collectExitInfos(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return   // API 30+
        val app = context.applicationContext
        try {
            val am = app.getSystemService(ActivityManager::class.java) ?: return
            val handled = handledExits(app)
            val newlyHandled = mutableSetOf<String>()

            // getHistoricalProcessExitReasons(packageName, pid, maxNum): 0 = 不限 pid
            val exitInfos = am.getHistoricalProcessExitReasons(app.packageName, 0, 20)
            for (info in exitInfos) {
                val reason = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "CRASH_JAVA"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                    ApplicationExitInfo.REASON_ANR -> "ANR"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    ApplicationExitInfo.REASON_OTHER -> "OTHER"
                    else -> "UNKNOWN"
                }
                // 只上报真正的异常退出（正常退出/被用户划掉不报）
                if (reason != "CRASH_JAVA" && reason != "CRASH_NATIVE" && reason != "ANR") continue

                // Java 崩溃已由 UncaughtExceptionHandler 在崩溃瞬间记录（堆栈更完整、
                // 还能拿到线程名），这里跳过，避免同一次崩溃上报两条。
                // 本路径存在的意义是补 handler 拿不到的类型：native abort / ANR。
                if (reason == "CRASH_JAVA") continue

                val key = "${info.timestamp}:${info.reason}"
                if (handled.contains(key)) continue

                val trace = try {
                    info.traceInputStream?.bufferedReader()?.use { it.readText() }
                } catch (_: Throwable) {
                    null
                }
                val summary = (info.description ?: "").ifBlank { reason }.take(500)

                enqueue(
                    app,
                    reason = reason,
                    reasonCode = info.reason,
                    summary = summary,
                    stacktrace = buildString {
                        appendLine("reason=$reason (code=${info.reason})")
                        appendLine("timestamp=${info.timestamp}")
                        info.description?.let { appendLine("description=$it") }
                        appendLine("--- system trace (tombstone) ---")
                        append(trace?.take(MAX_TRACE_CHARS) ?: "(no trace available)")
                    },
                    occurredAtMillis = info.timestamp,
                )
                newlyHandled += key
            }

            if (newlyHandled.isNotEmpty()) {
                markHandled(app, handled + newlyHandled)
            }
        } catch (e: Throwable) {
            Logger.w(TAG, "collectExitInfos failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 3) 上报
    // ------------------------------------------------------------------

    /**
     * 把所有待传崩溃 POST 到 MH。启动时后台调用。
     * 单条失败不影响其它条目；成功的删除本地文件。
     */
    fun reportPending(context: Context, prefs: AppPreferences) {
        try {
            val dir = pendingDir(context) ?: return
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
            if (files.isEmpty()) return

            val serverUrl = prefs.effectiveServerUrl.trimEnd('/')
            val apiKey = prefs.apiKey
            if (serverUrl.isBlank()) return

            val reports = JSONArray()
            val sent = mutableListOf<File>()
            for (f in files) {
                try {
                    reports.put(JSONObject(f.readText()))
                    sent += f
                } catch (_: Throwable) {
                    f.delete()  // 损坏的文件直接丢，别卡住队列
                }
            }
            if (reports.length() == 0) return

            val body = JSONObject().put("reports", reports).toString()
            val request = Request.Builder()
                .url("$serverUrl/api/v1/diagnostics/crashes")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { if (apiKey.isNotBlank()) header("X-API-Key", apiKey) }
                .build()

            http.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    sent.forEach { it.delete() }
                    Logger.i(TAG, "Uploaded ${sent.size} crash report(s) -> HTTP ${resp.code}")
                } else {
                    Logger.w(TAG, "Crash upload rejected: HTTP ${resp.code}")
                }
            }
        } catch (e: Throwable) {
            Logger.w(TAG, "reportPending failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun pendingDir(context: Context): File? = try {
        File(context.filesDir, PENDING_DIR).apply { mkdirs() }
    } catch (_: Throwable) {
        null
    }

    private fun enqueue(
        context: Context,
        reason: String,
        reasonCode: Int?,
        summary: String,
        stacktrace: String,
        occurredAtMillis: Long,
    ) {
        val dir = pendingDir(context) ?: return
        val reportId = "crash-$occurredAtMillis-$reason"

        val json = JSONObject().apply {
            put("source_device_id", prefs?.deviceId ?: "unknown-device")
            put("reason", reason)
            reasonCode?.let { put("reason_code", it) }
            put("summary", summary)
            put("stacktrace", stacktrace)
            put("occurred_at", isoUtc(occurredAtMillis))
            put("client_report_id", reportId)
            put("app_version", appVersion(context))
            put("build_type", if (isDebuggable(context)) "debug" else "release")
            put("device_model", Build.MODEL ?: "unknown")
            put("android_version", Build.VERSION.RELEASE ?: "?")
        }
        try {
            File(dir, "$reportId.json").writeText(json.toString())
        } catch (_: Throwable) {
        }
    }

    private fun handledExits(context: Context): Set<String> = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_HANDLED_EXITS, emptySet()) ?: emptySet()
    } catch (_: Throwable) {
        emptySet()
    }

    private fun markHandled(context: Context, value: Set<String>) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_HANDLED_EXITS, value).apply()
        } catch (_: Throwable) {
        }
    }

    private fun appVersion(context: Context): String = try {
        val pm = context.packageManager
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
        "${pi.versionName} ($code)"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun isoUtc(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).toString()
}
