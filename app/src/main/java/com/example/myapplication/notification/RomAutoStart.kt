package com.example.myapplication.notification

/**
 * 厂商 ROM 归类。
 *
 * 只用于挑选「自启动管理页」的候选组件, 不代表本应用"支持"某个品牌 ——
 * 下表的组件名都是厂商私有的 ROM 内部实现, 任何一次 OTA 都可能变。
 */
enum class RomVendor { OPLUS, XIAOMI, HUAWEI, HONOR, VIVO, SAMSUNG, MEIZU, OTHER }

/**
 * 一个候选跳转目标。
 *
 * ⚠️ 这里**不能**用 `android.content.ComponentName`: 它是 android.* 类型,
 * 构造时就会走到 android.jar 的桩方法上抛 "Stub!", 会让整个文件无法在 JVM 单测里跑。
 * 因此只存两个字符串, 由 [AutoStartLauncher] 组装成 ComponentName。
 */
data class AutoStartTarget(
    val packageName: String,
    /** 必须是**全限定**类名, 不能以 `.` 开头 —— 见 [RomAutoStart] 的说明 */
    val activityName: String,
    /**
     * 该页面在 AndroidManifest 里声明的 intent-filter action。
     *
     * 有些厂商给自启动页挂了 action, 用 action 隐式拉起比硬编码类名更耐 ROM 改版。
     * 为 null 表示只声明了组件名、没有可用的 action。
     */
    val action: String? = null
)

/**
 * 「自启动管理」页的候选组件表。
 *
 * ## 为什么这里全是硬编码
 *
 * Android **没有任何公开 API** 能查询或申请自启动权限, 厂商把开关放在自己的
 * 私有页面里。唯一可行的办法就是硬编码 `包名/Activity` 深链跳过去。
 * 因此这张表天然是"猜"的, 可靠性完全依赖 [AutoStartLauncher] 的逐级兜底。
 *
 * ## 本文件禁止 import android.*
 *
 * 沿用了 `importer/` 包的做法: android.jar 的桩方法只在**被调用**时才抛异常,
 * 所以只要不碰 `Build` 等类型, 就能在纯 JVM 单测里覆盖厂商判定和整张表。
 * 这也是 [detectVendor] 的入参必须显式传入、而**不能**写成
 * `manufacturer: String = Build.MANUFACTURER` 的原因 —— 默认值会在调用点求值。
 */
object RomAutoStart {

    /**
     * 判定厂商。
     *
     * 判定用 manufacturer 和 brand **两者**的并集: 同一厂商不同机型上报的字段并不一致
     * (小米的子品牌 Redmi/POCO 常常只在 brand 里体现)。
     *
     * 判定顺序敏感 —— **honor 必须排在 huawei 之前**: 荣耀已从华为独立、包名换成
     * `com.hihonor.*`, 但部分机器 `brand` 仍上报 `huawei`, 先判 honor 才不会走错分支。
     */
    fun detectVendor(manufacturer: String?, brand: String?): RomVendor {
        val m = manufacturer?.trim()?.lowercase().orEmpty()
        val b = brand?.trim()?.lowercase().orEmpty()
        return when {
            m in XIAOMI || b in XIAOMI -> RomVendor.XIAOMI
            m in HONOR || b in HONOR -> RomVendor.HONOR
            m in HUAWEI || b in HUAWEI -> RomVendor.HUAWEI
            m in OPLUS || b in OPLUS -> RomVendor.OPLUS
            m in VIVO || b in VIVO -> RomVendor.VIVO
            m in SAMSUNG || b in SAMSUNG -> RomVendor.SAMSUNG
            m in MEIZU || b in MEIZU -> RomVendor.MEIZU
            else -> RomVendor.OTHER
        }
    }

    /**
     * 按优先级（best-first）返回候选目标。
     *
     * @param sdkInt 传入 `Build.VERSION.SDK_INT`。ColorOS 在 API 26 前后换了包名,
     *               所以厂商内还要按版本分支; 同样显式传入以便单测。
     */
    fun autoStartTargets(vendor: RomVendor, sdkInt: Int): List<AutoStartTarget> = when (vendor) {
        // OPPO / OnePlus / realme。ColorOS 12 起这些机型统一成 OPlus 平台,
        // 包名从 com.coloros.* / com.oppo.* 陆续迁到 com.oplus.*。
        RomVendor.OPLUS -> if (sdkInt < 26) {
            listOf(
                target(
                    "com.color.safecenter",
                    "com.color.safecenter.permission.startup.StartupAppListActivity"
                )
            )
        } else {
            listOf(
                OPLUS_VERIFIED,
                target(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.startupapp.StartupAppListActivity"
                ),
                target(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                ),
                target(
                    "com.coloros.phonemanager",
                    "com.coloros.phonemanager.startupapp.StartupAppListActivity"
                ),
                target(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                ),
                target(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            )
        }

        RomVendor.XIAOMI -> listOf(
            target(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )

        // 华为系要两个都试: appcontrol 那条在真机上常因缺厂商签名权限被拒(SecurityException),
        // startupmgr 那条才是普通应用能拉起的启动管理页。
        RomVendor.HUAWEI -> listOf(
            target(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            target(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            )
        )

        // 荣耀独立后包名是 com.hihonor.*; 旧荣耀机仍是 com.huawei.*, 故留一条退路。
        RomVendor.HONOR -> listOf(
            target(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            target(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ),
            target(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        )

        RomVendor.VIVO -> listOf(
            target(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            target(
                "com.iqoo.secure",
                "com.iqoo.secure.safeguard.PurviewTabActivity"
            ),
            target(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )
        )

        RomVendor.SAMSUNG -> listOf(
            target(
                "com.samsung.android.lool",
                "com.samsung.android.sm.app.dashboard.SmartManagerDashBoardActivity"
            ),
            target(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            ),
            // 国内版三星是独立包名
            target(
                "com.samsung.android.sm_cn",
                "com.samsung.android.sm.app.dashboard.SmartManagerDashBoardActivity"
            )
        )

        RomVendor.MEIZU -> listOf(
            target("com.meizu.safe", "com.meizu.safe.security.SecureMainActivity"),
            target(
                "com.meizu.safe",
                "com.meizu.safe.permission.SuperPermissionBinderActivity"
            )
        )

        // 认不出厂商就不猜, 直接走兜底(应用详情页) —— 比乱跳一个不存在的页面好
        RomVendor.OTHER -> emptyList()
    }

    /**
     * ✅ **唯一实测验证过的目标**。
     *
     * 实测设备: OnePlus PKG110 / ColorOS(OxygenOS) 16 / Android 16 (API 36)。
     * 用 `adb shell cmd package query-activities --brief --components -p com.oplus.battery`
     * 枚举得到, 并在 `dumpsys package com.oplus.battery` 里确认它声明了 intent-filter action。
     *
     * 注意两点反直觉的地方:
     * 1. 这台机器上 **`com.coloros.safecenter` / `com.oppo.safe` / `com.oneplus.security`
     *    全都不存在** —— 网上流传的清单照抄过来会全部跳转失败。
     * 2. Activity 的类名在 `com.oplus.startupapp.*` 命名空间下, **和声明它的包
     *    `com.oplus.battery` 不同名**。所以不要写"类名必须以包名开头"这种断言。
     */
    internal val OPLUS_VERIFIED = AutoStartTarget(
        packageName = "com.oplus.battery",
        activityName = "com.oplus.startupapp.view.StartupAppListActivity",
        action = "com.oplus.battery.permission.startup.StartupAppListActivity"
    )

    private fun target(pkg: String, cls: String) = AutoStartTarget(pkg, cls)

    private val XIAOMI = setOf("xiaomi", "redmi", "poco")
    private val HUAWEI = setOf("huawei")
    private val HONOR = setOf("honor", "hihonor")
    private val OPLUS = setOf("oppo", "oneplus", "realme", "oplus")
    private val VIVO = setOf("vivo", "iqoo")
    private val SAMSUNG = setOf("samsung")
    private val MEIZU = setOf("meizu")
}