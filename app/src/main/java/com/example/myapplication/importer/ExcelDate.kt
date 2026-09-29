package com.example.myapplication.importer

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 账单时间转换。
 *
 * 微信账单的「交易时间」是 Excel 日期序列号（如 `46293.96523148148`），
 * 支付宝账单的「交易时间」是 `yyyy-MM-dd HH:mm:ss` 字符串。
 *
 * 两家账单的元数据都注明所有时间为 **UTC+08:00**，因此统一按该时区解释，
 * 保证写入数据库的时间戳与通知监听路径写入的真实时刻语义一致。
 */
internal object ExcelDate {

    /** 账单声明的时区：微信「本账单中所有时间均为UTC+08:00时间」 */
    private val BILL_ZONE: TimeZone = TimeZone.getTimeZone("GMT+08:00")

    private const val MILLIS_PER_DAY = 86_400_000.0

    /**
     * Excel 日期序列号 → 时间戳。
     *
     * Excel 1900 日期系统的原点（含 1900 闰年 bug 修正后）为 1899-12-30。
     * 例：`46293.96523148148` → `2026-09-28 23:09:56 (+08:00)`。
     *
     * 注意：不能按 UTC 朴素换算成 `(serial - 25569) * 86400000` —— 那样得到的
     * millis 比真实时刻晚 8 小时，设备上会显示成 `09-29 07:09`。
     */
    fun serialToMillis(serial: Double): Long {
        val cal = Calendar.getInstance(BILL_ZONE)
        cal.clear()
        cal.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
        // 不能用 cal.add(MILLISECOND, ...): 该参数是 Int, 而 serial 换算出的毫秒数
        // 约 4e12, 远超 Int 上限会溢出。这里先取原点毫秒, 再用 Long 相加。
        return cal.timeInMillis + Math.round(serial * MILLIS_PER_DAY)
    }

    /**
     * 支付宝的 `yyyy-MM-dd HH:mm:ss` 时间字符串 → 时间戳（按 UTC+08:00 解释）。
     *
     * 解析失败返回 null。
     */
    fun parseAlipayTime(text: String): Long? {
        // SimpleDateFormat 非线程安全 → 每次新建, 不缓存在 companion
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
            timeZone = BILL_ZONE
            isLenient = false   // 拒绝 2026-13-45 这类非法日期
        }
        return try {
            format.parse(text.trim())?.time
        } catch (e: ParseException) {
            null
        }
    }
}