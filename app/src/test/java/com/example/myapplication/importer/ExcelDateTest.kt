package com.example.myapplication.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Excel 日期序列号转换。
 *
 * 这是最容易出错的地方：按 UTC 朴素换算会得到比真实时刻晚 8 小时的 millis，
 * 账单上 `09-28 23:09` 会显示成 `09-29 07:09`。
 */
class ExcelDateTest {

    private val billZone: TimeZone = TimeZone.getTimeZone("GMT+08:00")

    private fun format(millis: Long, zone: TimeZone = billZone): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = zone }
            .format(Date(millis))

    /** 微信账单首条：46293.96523148148 */
    @Test
    fun serialConvertsToBillZoneWallClock() {
        val millis = ExcelDate.serialToMillis(46293.96523148148)
        assertEquals("2026-09-28 23:09:56", format(millis))
    }

    /** 微信账单末条：46262.54015046296 */
    @Test
    fun serialConvertsSecondSample() {
        val millis = ExcelDate.serialToMillis(46262.54015046296)
        assertEquals("2026-08-28 12:57:49", format(millis))
    }

    /**
     * 钉死时区 bug：朴素 UTC 公式（serial - 25569）* 86400000 的结果
     * 恰好比正确值晚 8 小时。
     */
    @Test
    fun naiveUtcFormulaIsExactlyEightHoursOff() {
        val serial = 46293.96523148148
        val correct = ExcelDate.serialToMillis(serial)
        val naive = Math.round((serial - 25569.0) * 86_400_000.0)

        assertEquals(28_800_000L, naive - correct)
        // 并且朴素值在账单时区下会显示成第二天早上
        assertEquals("2026-09-29 07:09:56", format(naive))
    }

    /** 整数序列号应落在账单时区的 00:00:00 */
    @Test
    fun integerSerialIsMidnight() {
        val millis = ExcelDate.serialToMillis(46293.0)
        assertEquals("2026-09-28 00:00:00", format(millis))
    }

    @Test
    fun alipayTimeIsParsedInBillZone() {
        val millis = ExcelDate.parseAlipayTime("2026-09-27 17:37:53")
        assertNotNull(millis)
        assertEquals("2026-09-27 17:37:53", format(millis!!))
    }

    @Test
    fun alipayTimeTrimsSurroundingWhitespace() {
        val millis = ExcelDate.parseAlipayTime("  2026-09-27 17:37:53\t")
        assertEquals("2026-09-27 17:37:53", format(millis!!))
    }

    @Test
    fun invalidAlipayTimeReturnsNull() {
        assertNull(ExcelDate.parseAlipayTime("不是时间"))
        assertNull(ExcelDate.parseAlipayTime(""))
        assertNull(ExcelDate.parseAlipayTime("2026-13-45 99:99:99"))
    }
}