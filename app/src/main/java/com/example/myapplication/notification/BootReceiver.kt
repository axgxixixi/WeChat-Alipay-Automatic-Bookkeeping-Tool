package com.example.myapplication.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启广播接收器。
 *
 * 手机重启后, [NotificationService] 的绑定会丢失, 保活服务也不会自动运行。
 * 收到开机广播后重新拉起 [KeepAliveService], 并请求重新绑定通知监听。
 *
 * 需要 `RECEIVE_BOOT_COMPLETED` 权限。
 * Android 15+ 禁止从 BOOT_COMPLETED 启动 dataSync / camera / mediaPlayback 等
 * 类型的前台服务, 但 `specialUse` 仍被允许, 因此本方案可行。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT_POWERON
        ) {
            return
        }

        Log.d(TAG, "收到开机广播: $action, 拉起保活服务")
        KeepAliveService.start(context)
    }

    companion object {
        private const val TAG = "BootReceiver"

        /** 部分国产 ROM (如小米) 使用的快速开机广播 */
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}