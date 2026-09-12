package com.jxitc.messagehub.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志工具。除了写 logcat，还同步写入 app 私有目录的本地文件日志。
 *
 * 文件日志持久化在手机端，即使 app 进程被 OPPO 杀掉，日志文件仍在，
 * 可通过 run-as 读取，用于追溯 app 死前的行为/异常。
 * 日志文件: <filesDir>/logs/messagehub.log (超过 MAX_SIZE 轮转一份 .old)
 */
object Logger {
    private const val TAG = "MessageHub"
    private const val MAX_SIZE = 1_000_000L // 1MB, 超了轮转
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "messagehub.log"

    /**
     * 单条日志的 UTF-8 字节上限。Android logd 单条 payload 上限约 4068 字节，
     * 超过时系统要走日志切分路径；Android 16 上该路径会因 ubsan 溢出检查 abort
     * 整个进程（只有 SIGABRT、没有 Java 堆栈，表现为"静默闪退"）。
     *
     * 必须按**字节**判断：中文 UTF-8 每字符 3 字节，只限制字符数并不安全。
     * 这是安全阀——任何调用点打超长文本都不会再拖垮进程。
     */
    private const val MAX_LOG_BYTES = 3500

    private var logFile: File? = null
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 截断超长日志，保证单条不超 logd 上限 */
    private fun clamp(message: String): String {
        if (message.length < MAX_LOG_BYTES / 3) return message // 最坏情况也不超限
        val total = message.toByteArray(Charsets.UTF_8).size
        if (total <= MAX_LOG_BYTES) return message
        val sb = StringBuilder()
        var used = 0
        for (ch in message) {
            val len = ch.toString().toByteArray(Charsets.UTF_8).size
            if (used + len > MAX_LOG_BYTES) break
            sb.append(ch)
            used += len
        }
        return sb.append("…[truncated, total ").append(total).append(" bytes]").toString()
    }

    /** 在 Application.onCreate 调用一次，初始化文件日志路径 */
    fun init(context: Context) {
        try {
            val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
            logFile = File(dir, LOG_FILE)
            Log.i(TAG, "File log enabled: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            logFile = null
            Log.w(TAG, "Failed to init file log: ${e.message}")
        }
    }

    private fun writeFile(level: String, tag: String, message: String) {
        val f = logFile ?: return
        try {
            // 简单轮转
            if (f.length() > MAX_SIZE) {
                val old = File(f.parentFile, "$LOG_FILE.old")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            val line = "${timeFmt.format(Date())} [$level] $tag: $message\n"
            synchronized(this) {
                f.appendText(line)
            }
        } catch (e: Exception) {
            // 文件日志失败不影响 logcat
        }
    }

    fun d(tag: String, message: String) { val m = clamp(message); Log.d(tag, m); writeFile("D", tag, m) }
    fun d(message: String) { d(TAG, message) }

    fun i(tag: String, message: String) { val m = clamp(message); Log.i(tag, m); writeFile("I", tag, m) }
    fun i(message: String) { i(TAG, message) }

    fun w(tag: String, message: String) { val m = clamp(message); Log.w(tag, m); writeFile("W", tag, m) }
    fun w(message: String) { w(TAG, message) }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val m = clamp(message)
        if (throwable != null) Log.e(tag, m, throwable) else Log.e(tag, m)
        // 堆栈同样截断：长堆栈也可能超单条上限
        writeFile("E", tag, clamp(m + (throwable?.let { "\n${it.stackTraceToString()}" } ?: "")))
    }
    fun e(message: String) { e(TAG, message) }
    fun e(message: String, throwable: Throwable) { e(TAG, message, throwable) }

    fun v(tag: String, message: String) { val m = clamp(message); Log.v(tag, m); writeFile("V", tag, m) }
    fun v(message: String) { v(TAG, message) }
}
