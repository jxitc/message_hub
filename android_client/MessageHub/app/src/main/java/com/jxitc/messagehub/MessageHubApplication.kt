package com.jxitc.messagehub

import android.app.Application
import android.content.Intent
import android.os.Build
import com.jxitc.messagehub.di.AppContainer
import com.jxitc.messagehub.service.KeepAliveService
import com.jxitc.messagehub.utils.Logger

class MessageHubApplication : Application() {

    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化手机端文件日志(持久化, 被杀后仍可读取)
        Logger.init(this)
        // 启动前台保活服务，避免 OPPO/ColorOS 杀后台导致错过通知/短信采集
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
