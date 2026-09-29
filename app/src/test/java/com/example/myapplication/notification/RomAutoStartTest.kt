package com.example.myapplication.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单测: 不碰任何 android.* 类型, 所以不需要 Robolectric。
 *
 * 这能跑起来本身就是个约束 —— [RomAutoStart] 一旦引入 `android.os.Build`
 * 或 `ComponentName`, 这里立刻会以 "Stub!" 失败。
 */
class RomAutoStartTest {

    // ==================== 厂商判定 ====================

    @Test
    fun detectsXiaomiFamilyIncludingSubBrands() {
        assertEquals(RomVendor.XIAOMI, RomAutoStart.detectVendor("Xiaomi", "Xiaomi"))
        // Redmi / POCO 常常只在 brand 里体现
        assertEquals(RomVendor.XIAOMI, RomAutoStart.detectVendor("Xiaomi", "Redmi"))
        assertEquals(RomVendor.XIAOMI, RomAutoStart.detectVendor("Xiaomi", "POCO"))
        assertEquals(RomVendor.XIAOMI, RomAutoStart.detectVendor(null, "Redmi"))
    }

    @Test
    fun detectsOplusFamily() {
        assertEquals(RomVendor.OPLUS, RomAutoStart.detectVendor("OPPO", "OPPO"))
        assertEquals(RomVendor.OPLUS, RomAutoStart.detectVendor("OnePlus", "OnePlus"))
        assertEquals(RomVendor.OPLUS, RomAutoStart.detectVendor("realme", "realme"))
        // 新统一平台可能上报 oplus
        assertEquals(RomVendor.OPLUS, RomAutoStart.detectVendor("OPPO", "oplus"))
    }

    @Test
    fun detectsVivoAndIqoo() {
        assertEquals(RomVendor.VIVO, RomAutoStart.detectVendor("vivo", "vivo"))
        assertEquals(RomVendor.VIVO, RomAutoStart.detectVendor("vivo", "iQOO"))
    }

    /**
     * 判定顺序的守卫: 荣耀已从华为独立(包名 com.hihonor.*),
     * 但部分机器 manufacturer/brand 仍上报 huawei —— 如果哪天有人把 honor 的分支
     * 挪到 huawei 后面, 这条会立刻失败。
     */
    @Test
    fun honorIsNotSwallowedByHuawei() {
        assertEquals(RomVendor.HONOR, RomAutoStart.detectVendor("HONOR", "HONOR"))
        assertEquals(RomVendor.HONOR, RomAutoStart.detectVendor("hihonor", "hihonor"))
        assertNotEquals(
            "honor 的分支必须在 huawei 之前",
            RomVendor.HUAWEI,
            RomAutoStart.detectVendor("HONOR", "HONOR")
        )
        // 真正的华为仍要判成 HUAWEI
        assertEquals(RomVendor.HUAWEI, RomAutoStart.detectVendor("HUAWEI", "HUAWEI"))
    }

    @Test
    fun detectionIsCaseInsensitiveAndTrims() {
        assertEquals(RomVendor.OPLUS, RomAutoStart.detectVendor("  ONEPLUS  ", "oneplus"))
        assertEquals(RomVendor.XIAOMI, RomAutoStart.detectVendor("XIAOMI", "xiaomi"))
    }

    @Test
    fun unknownOrBlankFallsBackToOther() {
        assertEquals(RomVendor.OTHER, RomAutoStart.detectVendor("Google", "google"))
        assertEquals(RomVendor.OTHER, RomAutoStart.detectVendor(null, null))
        assertEquals(RomVendor.OTHER, RomAutoStart.detectVendor("", ""))
        assertEquals(RomVendor.OTHER, RomAutoStart.detectVendor("   ", "   "))
    }

    // ==================== 候选表 ====================

    /**
     * 钉死实测结论 —— 这条是全文件最重要的测试。
     *
     * OnePlus PKG110(ColorOS 16) 上, 自启动页在 `com.oplus.battery` 里, 而网上流传的
     * 清单大多写 `com.coloros.safecenter` / `com.oppo.safe` / `com.oneplus.security`
     * —— 这三个包在该机上**都不存在**。谁要是"顺手改回"那些名字, 这里会挡住。
     */
    @Test
    fun oplusVerifiedTargetIsFirstAndExact() {
        val first = RomAutoStart.autoStartTargets(RomVendor.OPLUS, 36).first()
        assertEquals(RomAutoStart.OPLUS_VERIFIED, first)
        assertEquals("com.oplus.battery", first.packageName)
        assertEquals(
            "com.oplus.startupapp.view.StartupAppListActivity",
            first.activityName
        )
        assertEquals(
            "com.oplus.battery.permission.startup.StartupAppListActivity",
            first.action
        )
    }

    /** 旧 ColorOS(API 26 之前)用的是 com.color.safecenter, 按 sdkInt 分支 */
    @Test
    fun oplusBranchesOnSdkVersion() {
        assertEquals(
            "com.color.safecenter",
            RomAutoStart.autoStartTargets(RomVendor.OPLUS, 25).first().packageName
        )
        assertEquals(
            "com.oplus.battery",
            RomAutoStart.autoStartTargets(RomVendor.OPLUS, 26).first().packageName
        )
        assertEquals(
            "com.oplus.battery",
            RomAutoStart.autoStartTargets(RomVendor.OPLUS, 36).first().packageName
        )
    }

    /** 荣耀优先试 hihonor 包, 同时保留旧荣耀机的 huawei 退路 */
    @Test
    fun honorTriesHihonorFirstAndKeepsHuaweiFallback() {
        val targets = RomAutoStart.autoStartTargets(RomVendor.HONOR, 34)
        assertEquals("com.hihonor.systemmanager", targets.first().packageName)
        assertTrue(
            "旧荣耀机(仍报 huawei)需要 com.huawei.systemmanager 退路",
            targets.any { it.packageName == "com.huawei.systemmanager" }
        )
    }

    @Test
    fun otherVendorHasNoTargetsSoItFallsThrough() {
        assertTrue(RomAutoStart.autoStartTargets(RomVendor.OTHER, 36).isEmpty())
    }

    /**
     * 全表不变量扫描。
     *
     * 覆盖每个厂商 × 几个 sdkInt, 守住两类高发 typo:
     * - 相对类名(`.Foo`): `ComponentName(pkg, ".Foo")` 会把它拼成 `pkg..Foo`, 静默跳错页
     * - 空字段: 拼出非法组件名
     *
     * ⚠️ 这里故意**不**断言 `activityName.startsWith(packageName)` —— 实测的 OPLUS 目标
     * (包 `com.oplus.battery`、类 `com.oplus.startupapp.view.StartupAppListActivity`)
     * 就不满足该关系, 加了会误伤正确数据。
     */
    @Test
    fun everyTargetHasUsableComponentNames() {
        val sdks = listOf(24, 25, 26, 30, 34, 36)
        var checked = 0

        for (vendor in RomVendor.entries) {
            for (sdk in sdks) {
                for (t in RomAutoStart.autoStartTargets(vendor, sdk)) {
                    checked++
                    val where = "$vendor/$sdk: $t"

                    assertTrue("包名不能为空 -> $where", t.packageName.isNotBlank())
                    assertTrue("类名不能为空 -> $where", t.activityName.isNotBlank())
                    assertFalse(
                        "类名必须是全限定名, 不能以 '.' 开头 -> $where",
                        t.activityName.startsWith(".")
                    )
                    assertTrue(
                        "类名必须含包分隔的 '.' -> $where",
                        t.activityName.contains('.')
                    )
                    t.action?.let {
                        assertTrue("action 要么为 null 要么非空 -> $where", it.isNotBlank())
                    }
                }
            }
        }

        assertTrue("扫描到 0 条说明表格坏了", checked > 0)
    }

    /** 除 OTHER 外每个厂商都得至少有一条候选, 否则等于该厂商永远走兜底 */
    @Test
    fun everyKnownVendorHasAtLeastOneTarget() {
        for (vendor in RomVendor.entries) {
            if (vendor == RomVendor.OTHER) continue
            assertTrue(
                "$vendor 没有任何候选目标",
                RomAutoStart.autoStartTargets(vendor, 36).isNotEmpty()
            )
        }
    }
}