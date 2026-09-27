package com.example.myapplication.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R

/**
 * 前台保活服务。
 *
 * [NotificationService] 虽然由系统绑定, 但 App **进程** 在国产 ROM (ColorOS / MIUI / EMUI)
 * 上会被冻结或杀死, 进程一死通知回调就断了。
 *
 * 本服务用一个常驻通知把进程钉在前台, 显著降低被杀概率:
 * - 前台服务 + 常驻通知 → 系统不再轻易冻结进程
 * - [START_STICKY] → 服务被杀后系统会尝试重启
 * - 与 [BootReceiver] 配合 → 开机后自动恢复
 *
 * Android 14+ 要求声明 `foregroundServiceType`; 这里使用 `specialUse`
 * (Android 15+ 仍允许从 BOOT_COMPLETED 启动该类型, 而 dataSync/camera 等类型已被禁止)。
 *
 * 注意: 常驻通知在 Android 8+ 无法隐藏, 这是前台服务的必然代价。
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "前台保活服务已启动")
        } catch (t: Throwable) {
            // 例如后台启动受限 (ForegroundServiceStartNotAllowedException)
            Log.e(TAG, "启动前台服务失败", t)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ledger)
            .setContentTitle("记账助手正在后台运行")
            .setContentText("正在监听微信 / 支付宝账单通知")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台运行状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持账单通知监听服务存活, 关闭后可能导致自动记账失效"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val NOTIFICATION_ID = 1001

        /**
         * 启动保活服务。
         *
         * 需要 App 处于前台 (Android 12+ 限制后台启动前台服务);
         * 开机场景由 [BootReceiver] 处理, BOOT_COMPLETED 属于允许的例外。
         */
        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "启动保活服务失败", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (t: Throwable) {
                Log.e(TAG, "停止保活服务失败", t)
            }
        }
    }
}