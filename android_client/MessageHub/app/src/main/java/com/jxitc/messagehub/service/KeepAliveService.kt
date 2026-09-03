package com.jxitc.messagehub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jxitc.messagehub.MessageHubApplication
import com.jxitc.messagehub.MainActivity
import com.jxitc.messagehub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 前台保活服务。
 *
 * ColorOS/OPPO 会对纯后台 Service 做"速冻/杀进程"，导致通知监听、短信接收
 * 这些采集组件失效。本服务以可见的"前台服务"形式运行，显著抬高进程优先级，
 * 极大降低被系统杀掉的概率。
 *
 * - START_STICKY：进程被杀后系统会尝试重建服务
 * - 常驻通知：标识服务在运行，动态显示"过去 1 天采集的消息数"
 * - 仅 debug/side-load 场景使用，用于后台采集通知与短信
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastCount = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(-1)) // -1 表示还在统计
        startCountUpdater()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MessageHub 后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 MessageHub 运行，持续采集通知和短信"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun startCountUpdater() {
        scope.launch {
            while (isActive) {
                val count = getTodayCount()
                if (count != lastCount) {
                    lastCount = count
                    updateNotification(count)
                }
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private suspend fun getTodayCount(): Int {
        return try {
            val app = applicationContext as? MessageHubApplication ?: return 0
            val all = app.appContainer.memoryRepository.getAllMemories().first()
            all.count { it.createdAt.isAfter(LocalDateTime.now().minusDays(1)) }
        } catch (e: Exception) {
            0
        }
    }

    private fun updateNotification(count: Int) {
        val noti = buildNotification(count)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, noti)
        } catch (e: Exception) {
            // 通知权限未授予等场景，忽略（服务仍在前台运行）
        }
    }

    private fun buildNotification(count: Int): Notification {
        val text = if (count >= 0) "过去 1 天已采集 $count 条消息" else "正在统计采集量..."
        // 点击通知跳转到主页面
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MessageHub 正在运行")
            .setContentText(text)
            .setContentIntent(pending)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "messagehub_keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL_MS = 30_000L
    }
}
