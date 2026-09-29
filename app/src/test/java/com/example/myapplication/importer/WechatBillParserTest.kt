package com.example.myapplication.importer

import com.example.myapplication.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class WechatBillParserTest {

    private fun format(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("GMT+08:00") }
            .format(Date(millis))

    // ==================== 真实账单 ====================

    private val real by lazy { WechatBillParser.parse(TestFixtures.bytes("wechat_sample.xlsx")) }

    /**
     * 与账单自身元数据对账：`共166笔记录 / 收入：31笔 7200.42元 / 支出：135笔 7173.60元`。
     * 这条不变量保证解析没有漏行或多行。
     */
    @Test
    fun realBillMatchesItsOwnMetadata() {
        assertEquals(166, real.records.size)
        assertEquals(31, real.records.count { it.type == Transaction.TYPE_INCOME })
        assertEquals(135, real.records.count { it.type == Transaction.TYPE_EXPENSE })
        assertEquals(0, real.neutralSkipped)
        assertEquals(0, real.invalidSkipped)
    }

    @Test
    fun realBillAmountsMatchMetadata() {
        val income = real.records.filter { it.type == Transaction.TYPE_INCOME }.sumOf { it.amount }
        val expense = real.records.filter { it.type == Transaction.TYPE_EXPENSE }.sumOf { it.amount }
        assertEquals(7200.42, income, 0.01)
        assertEquals(7173.60, expense, 0.01)
    }

    /** 时区验证：必须是 09-28 23:09:56，而不是比它晚 8 小时的 09-29 07:09:56 */
    @Test
    fun realBillFirstRecordUsesBillTimeZone() {
        // 账单按时间倒序，最后一条是区间起点
        val earliest = real.records.minBy { it.timestamp }
        assertEquals("2026-08-28 12:57:49", format(earliest.timestamp))
    }

    @Test
    fun realBillOrderNumbersAreUniqueAndClean() {
        val orderNos = real.records.mapNotNull { it.orderNo }
        assertEquals(166, orderNos.size)
        assertTrue(orderNos.none { it.isBlank() })
        assertEquals(166, orderNos.toSet().size)   // 全部唯一, 否则去重会误杀
        assertTrue(orderNos.none { it.contains("\t") || it.contains(" ") })
    }

    @Test
    fun realBillRecordsCarrySourceAndOrderNo() {
        assertTrue(real.records.all { it.source == Transaction.SOURCE_WECHAT })
        assertTrue(real.records.all { it.orderNo != null })
        assertTrue(real.records.all { it.notificationId == null })
    }

    /** 备注列是 "/" 时应归一化为空串 */
    @Test
    fun realBillNormalizesPlaceholderNote() {
        assertTrue(real.records.none { it.note == "/" || it.note == "-" })
    }

    // ==================== 合成账单 ====================

    private val header = listOf(
        "交易时间", "交易类型", "交易对方", "商品", "收/支",
        "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注"
    )

    /**
     * 表头在元数据之后、且位置不固定 —— 解析必须按表头文字定位，
     * 不能写死「第 18 行」。
     */
    @Test
    fun locatesHeaderRowWhereverItIs() {
        val bytes = TestFixtures.syntheticXlsx(
            listOf(
                listOf("微信支付账单明细"),
                listOf("微信昵称：[test]"),
                listOf(null),
                listOf("----------------------微信支付账单明细列表--------------------"),
                header,
                listOf("46293.96523148148", "商户消费", "美团", "外卖", "支出", "16.33", "零钱", "支付成功", "ORDER001", "M001", "/"),
                listOf("46293.53037037037", "二维码收款", "张三", "收款", "收入", "8.00", "零钱", "已收钱", "ORDER002", "M002", "/")
            )
        )

        val outcome = WechatBillParser.parse(bytes)
        assertEquals(2, outcome.records.size)
        assertEquals(1, outcome.records.count { it.type == Transaction.TYPE_EXPENSE })
        assertEquals(1, outcome.records.count { it.type == Transaction.TYPE_INCOME })
    }

    /** 中性交易计入 neutralSkipped，金额非法 / 订单号为空计入 invalidSkipped（0 元不算无效） */
    @Test
    fun skipsNeutralAndInvalidRows() {
        val bytes = TestFixtures.syntheticXlsx(
            listOf(
                header,
                listOf("46293.0", "商户消费", "美团", "外卖", "支出", "16.33", "零钱", "支付成功", "ORDER001", "M001", "/"),
                listOf("46292.0", "零钱通存取", "零钱通", "转入", "/", "100.00", "零钱", "已转账", "ORDER002", "M002", "/"),
                listOf("46291.0", "商户消费", "某商户", "消费", "支出", "0.00", "零钱", "支付成功", "ORDER003", "M003", "/"),
                listOf("46290.0", "商户消费", "无单号商户", "消费", "支出", "1.00", "零钱", "支付成功", "", "M004", "/"),
                listOf("46289.0", "商户消费", "金额缺失商户", "消费", "支出", "-", "零钱", "支付成功", "ORDER005", "M005", "/")
            )
        )

        val outcome = WechatBillParser.parse(bytes)
        assertEquals(2, outcome.records.size)     // 16.33 + 0.00
        assertEquals(1, outcome.neutralSkipped)
        assertEquals(2, outcome.invalidSkipped)   // 订单号为空 + 金额无法解析
    }

    @Test
    fun buildsReadableDescriptionFromCounterpartyAndProduct() {
        val bytes = TestFixtures.syntheticXlsx(
            listOf(
                header,
                listOf("46293.0", "商户消费", "美团", "外卖", "支出", "16.33", "零钱", "支付成功", "ORDER001", "M001", "/")
            )
        )

        val record = WechatBillParser.parse(bytes).records.single()
        assertEquals("美团 · 外卖", record.description)
        assertEquals("账单导入：商户消费", record.rawText)
        assertEquals("", record.note)
    }

    @Test
    fun throwsHeaderNotFoundWhenNoHeaderRow() {
        val bytes = TestFixtures.syntheticXlsx(
            listOf(listOf("这不是账单"), listOf("随便什么内容"))
        )

        val error = runCatching { WechatBillParser.parse(bytes) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is BillImportException)
        assertEquals(ImportError.HeaderNotFound, (error as BillImportException).error)
    }
}