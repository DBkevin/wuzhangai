package com.tencent.mm.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 保活前台服务（极简，无任何业务逻辑）。
 *
 * 为什么需要它：
 *   MIUI / HyperOS 会把仅持有无障碍绑定的后台进程放进 cgroup freezer 冻结
 *   （实测 /sys/fs/cgroup/.../cgroup.freeze = 1），进程被冻结后 Handler 轮询停止、
 *   无障碍回调也不再执行，表现为"服务已绑定但完全没反应"。
 *   提升为前台服务后进程不再被冻结，轮询与读取才能持续。
 *
 * 边界：
 *   * 只做保活，不读取任何数据、不访问网络、不操作其他应用；
 *   * 通知常驻但内容固定，不包含任何聊天信息。
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "kf_keepalive"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Throwable) {
                // 启动失败不影响只读主流程（只是可能被冻结）
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (_: Throwable) {
                // ignore
            }
        }
    }
}
