package com.example.myapplication.notification

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.myapplication.data.DatabaseHelper
import com.example.myapplication.model.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通知监听服务。
 *
 * 需要在 AndroidManifest 中声明：
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 * <service
 *     android:name=".notification.NotificationService"
 *     android:exported="false"
 *     android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
 *     <intent-filter>
 *         <action android:name="android.service.notification.NotificationListenerService" />
 *     </intent-filter>
 * </service>
 *
 * 用户需在系统设置中授予"通知读取权限"：
 * 设置 → 通知和状态栏 → 通知访问权限 → 开启本应用
 */
class NotificationService : NotificationListenerService() {

    /**
     * 服务级协程作用域。
     *
     * `onNotificationPosted()` 运行在系统的 binder 线程上, 绝不能阻塞它 ——
     * 使用 `runBlocking` 会卡住系统通知分发线程, 触发 ANR, 反而让进程更容易被杀。
     * 数据库写入统一丢到 IO 线程异步执行。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 断连后延迟重绑定: 立即重绑往往无效, 延迟一小段时间成功率更高 */
    private val rebindRunnable = Runnable {
        try {
            requestRebind(ComponentName(this, NotificationService::class.java))
            Log.d(TAG, "已请求重新绑定通知监听")
        } catch (t: Throwable) {
            Log.e(TAG, "重新绑定通知监听失败", t)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        try {
            processNotification(sbn)
        } catch (t: Throwable) {
            Log.e(TAG, "处理通知时异常", t)
        }
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // === 调试：记录所有通知的完整信息 ===
        val notification = sbn.notification
        val extras = notification.extras

        val debugTitle = extras?.getString(EXTRA_TITLE) ?: ""
        val debugText = extras?.getString(EXTRA_TEXT) ?: ""
        val debugBigText = extras?.getCharSequence(EXTRA_BIG_TEXT)?.toString() ?: ""

        Log.d(TAG, "═══════════ 收到通知 ═══════════")
        Log.d(TAG, "包名: $packageName")
        Log.d(TAG, "title: $debugTitle")
        Log.d(TAG, "text: $debugText")
        Log.d(TAG, "bigText: $debugBigText")
        Log.d(TAG, "════════════════════════════════")

        // 过滤：只处理微信和支付宝（大小写不敏感）
        if (!packageName.contains("tencent.mm", ignoreCase = true) &&
            !packageName.contains("alipay", ignoreCase = true)) {
            return
        }

        if (extras == null) return

        // 尝试从多个 keys 读取通知文本
        val title = extras.getString(EXTRA_TITLE) ?: ""
        val text = extras.getString(EXTRA_TEXT) ?: ""
        val bigText = extras.getCharSequence(EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getString(EXTRA_SUB_TEXT) ?: ""

        // 拼接所有非空文本
        val fullText = listOf(title, text, bigText, subText)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (fullText.isBlank()) return

        Log.d(TAG, "包名: $packageName")
        Log.d(TAG, "title: $title")
        Log.d(TAG, "text: $text")
        Log.d(TAG, "bigText: $bigText")
        Log.d(TAG, "subText: $subText")
        Log.d(TAG, "fullText: $fullText")

        if (fullText.isBlank()) return

        // 解析通知
        val result = TransactionParser.parse(
            packageName = packageName,
            text = fullText
        )

        if (result == null) {
            Log.w(TAG, "解析失败，原文: $fullText")
            return
        }

        Log.d(TAG, "解析结果: ${result.source} ${result.type} ¥${result.amount} - ${result.description}")

        // 存入数据库
        val transaction = Transaction(
            source = result.source,
            type = result.type,
            amount = result.amount,
            description = result.description,
            rawText = fullText,
            timestamp = System.currentTimeMillis(),
            notificationId = sbn.id
        )

        scope.launch {
            try {
                val db = DatabaseHelper.getInstance(this@NotificationService)
                db.insert(transaction)
                Log.d(TAG, "已存入数据库: ${result.source} ${result.type} ¥${result.amount}")
            } catch (e: Exception) {
                Log.e(TAG, "存入数据库失败", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 不需要处理
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "通知监听已连接")
        // 连接成功, 取消待执行的重绑定
        mainHandler.removeCallbacks(rebindRunnable)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "通知监听已断开, ${REBIND_DELAY_MS}ms 后尝试重新绑定")
        // 系统解除绑定后主动请求重连, 避免监听彻底失效
        mainHandler.removeCallbacks(rebindRunnable)
        mainHandler.postDelayed(rebindRunnable, REBIND_DELAY_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(rebindRunnable)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotificationService"
        private const val REBIND_DELAY_MS = 3000L
        private const val EXTRA_TITLE = "android.title"
        private const val EXTRA_TEXT = "android.text"
        private const val EXTRA_BIG_TEXT = "android.bigText"
        private const val EXTRA_SUB_TEXT = "android.subText"
    }
}