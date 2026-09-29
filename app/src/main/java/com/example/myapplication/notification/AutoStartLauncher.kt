package com.example.myapplication.notification

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/** 实际跳到了哪一层。UI 依据它在没跳到厂商页面时如实提示用户, 而不是假装成功。 */
enum class AutoStartDestination {
    /** 成功打开厂商的自启动管理页 */
    AUTO_START_PAGE,

    /** 没找到厂商页面, 退到了本应用的应用详情页 */
    APP_DETAILS,

    /** 连应用详情页都打不开, 退到了系统设置首页 */
    SYSTEM_SETTINGS,

    /** 全部失败 */
    FAILED
}

/**
 * 「自启动管理」跳转。
 *
 * ## 为什么不先检查组件是否存在
 *
 * ⚠️ **绝对不要加 `resolveActivity()` / `queryIntentActivities()` 预检。**
 *
 * 本应用 `targetSdk = 37`, 包可见性过滤生效: 未声明 `<queries>` 时, 这两个查询
 * 对其它应用的包一律返回 null / 空列表。而网上流传的写法大多是
 *
 * ```java
 * if (pm.queryIntentActivities(intent, 0).size() > 0) startActivity(intent);
 * ```
 *
 * 照抄过来会**静默地关掉整个功能** —— 表里每个候选都"不存在", 点了没反应也不报错。
 *
 * 而按 Android 官方文档, `startActivity()` (显式与隐式都算) **不受**包可见性限制,
 * 所以正确做法是直接尝试、靠异常判定存在性 —— 就是下面的 [tryLaunch]。
 * 也正因如此, 本功能**不需要**在 AndroidManifest 里加 `<queries>`。
 *
 * ## 只能在用户点击时调用
 *
 * Android 10+ 的后台启动限制(BAL)会让从后台发起的 `startActivity` 被静默丢弃,
 * 所以**不要**从 [KeepAliveService] / [BootReceiver] 里调用本类。
 */
object AutoStartLauncher {

    private const val TAG = "AutoStartLauncher"

    /**
     * 打开厂商的自启动管理页, 逐级兜底。
     *
     * 顺序: 厂商候选(显式组件 → intent action) → 应用详情页 → 系统设置首页。
     */
    fun openAutoStart(context: Context): AutoStartDestination {
        val vendor = RomAutoStart.detectVendor(Build.MANUFACTURER, Build.BRAND)
        val sdkInt = Build.VERSION.SDK_INT

        for (target in RomAutoStart.autoStartTargets(vendor, sdkInt)) {
            if (tryLaunchExplicit(context, target)) return AutoStartDestination.AUTO_START_PAGE
            if (target.action != null && tryLaunchAction(context, target.action)) {
                return AutoStartDestination.AUTO_START_PAGE
            }
        }

        Log.d(TAG, "厂商自启动页均不可用(vendor=$vendor), 退化到应用详情页")
        return openAppDetails(context)
    }

    /**
     * 打开本应用的「应用详情」页。
     *
     * 原先在 `MainActivity.openAppBatterySettings()` 和 `ProfileScreen` 里各内联了一遍,
     * 现在统一走这里。
     */
    fun openAppDetails(context: Context): AutoStartDestination {
        val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSafely(context, detail)) return AutoStartDestination.APP_DETAILS

        val system = Intent(Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startSafely(context, system)) return AutoStartDestination.SYSTEM_SETTINGS

        Log.w(TAG, "连系统设置都打不开")
        return AutoStartDestination.FAILED
    }

    /** 用显式组件名拉起候选页。 */
    private fun tryLaunchExplicit(context: Context, target: AutoStartTarget): Boolean {
        val intent = Intent()
            .setComponent(ComponentName(target.packageName, target.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startSafely(context, intent)
    }

    /** 用候选页声明的 action 隐式拉起（比硬编码类名更耐 ROM 改版）。 */
    private fun tryLaunchAction(context: Context, action: String): Boolean {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startSafely(context, intent)
    }

    /**
     * 启动并对**所有**失败方式返回 false 而不是抛出。
     *
     * 三种异常都要吞:
     * - [ActivityNotFoundException] —— 组件不存在(ROM 改版后最常见的失败)
     * - [SecurityException] —— 组件存在但未导出, 或需要厂商签名权限。
     *   **只 catch 前者是真实存在的 bug**: 华为的
     *   `com.huawei.systemmanager/…appcontrol.activity.StartupAppControlActivity`
     *   就会以 "Permission Denial … requires com.huawei.permission.external_app_settings.USE_COMPONENT" 被拒。
     * - [Exception] —— 组件名非法等意外情况, 兜底避免崩溃
     */
    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.d(TAG, "组件不存在: ${intent.component ?: intent.action}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "无权启动(未导出或需厂商权限): ${intent.component ?: intent.action}", e)
        false
    } catch (e: Exception) {
        Log.w(TAG, "启动失败: ${intent.component ?: intent.action}", e)
        false
    }
}