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

    private var logFile: File? = null
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

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

    fun d(tag: String, message: String) { Log.d(tag, message); writeFile("D", tag, message) }
    fun d(message: String) { d(TAG, message) }

    fun i(tag: String, message: String) { Log.i(tag, message); writeFile("I", tag, message) }
    fun i(message: String) { i(TAG, message) }

    fun w(tag: String, message: String) { Log.w(tag, message); writeFile("W", tag, message) }
    fun w(message: String) { w(TAG, message) }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        writeFile("E", tag, message + (throwable?.let { "\n${it.stackTraceToString()}" } ?: ""))
    }
    fun e(message: String) { e(TAG, message) }
    fun e(message: String, throwable: Throwable) { e(TAG, message, throwable) }

    fun v(tag: String, message: String) { Log.v(tag, message); writeFile("V", tag, message) }
    fun v(message: String) { v(TAG, message) }
}
