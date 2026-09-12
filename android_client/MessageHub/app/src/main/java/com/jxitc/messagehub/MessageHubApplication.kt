package com.jxitc.messagehub

import android.app.Application
import android.content.Intent
import android.os.Build
import com.jxitc.messagehub.di.AppContainer
import com.jxitc.messagehub.service.KeepAliveService
import com.jxitc.messagehub.utils.CrashReporter
import com.jxitc.messagehub.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MessageHubApplication : Application() {

    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 初始化手机端文件日志(持久化, 被杀后仍可读取)
        Logger.init(this)
        // 崩溃采集：Java 未捕获异常处理器（越早装越好）
        CrashReporter.install(this, appContainer.appPreferences)
        // 启动前台保活服务，避免 OPPO/ColorOS 杀后台导致错过通知/短信采集
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // 上报上次崩溃：先从 ApplicationExitInfo 取回 native/ANR 类崩溃，
        // 再把待传队列发到服务器。网络不通不影响启动，队列留待下次。
        backgroundScope.launch {
            try {
                CrashReporter.collectExitInfos(this@MessageHubApplication)
                CrashReporter.reportPending(this@MessageHubApplication, appContainer.appPreferences)
            } catch (e: Throwable) {
                Logger.w("MessageHubApplication", "Crash reporting skipped: ${e.message}")
            }
        }
    }
}
