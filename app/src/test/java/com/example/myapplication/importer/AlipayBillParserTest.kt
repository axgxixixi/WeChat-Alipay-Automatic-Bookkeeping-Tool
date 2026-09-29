package com.example.myapplication.importer

import com.example.myapplication.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AlipayBillParserTest {

    private fun format(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("GMT+08:00") }
            .format(Date(millis))

    private fun gbkCsv(vararg lines: String): ByteArray =
        lines.joinToString("\r\n").toByteArray(Charset.forName("GB18030"))

    private val header =
        "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,"

    // ==================== 真实账单 ====================

    private val real by lazy { AlipayBillParser.parse(TestFixtures.bytes("alipay_sample.csv")) }

    /**
     * 真实账单元数据（按「交易分类」统计）与逐笔「收/支」列并不一致 ——
     * 账单「特别提示」第 6 条承认了这点。这里断言的是以「收/支」列为准的结果：
     * 54 行明细 = 支出 50 + 收入 2 + 不计收支 2。
     */
    @Test
    fun realBillIsParsedByDirectionColumn() {
        assertEquals(52, real.records.size)
        assertEquals(50, real.records.count { it.type == Transaction.TYPE_EXPENSE })
        assertEquals(2, real.records.count { it.type == Transaction.TYPE_INCOME })
        assertEquals(2, real.neutralSkipped)
        assertEquals(0, real.invalidSkipped)
    }

    /**
     * 账单里有**两笔** 5900 元：09:45:04「交易成功」和 09:38:08「交易关闭」。
     * 后者收/支=不计收支，因此被排除 —— 不会把一笔没成交的交易记成收入。
     */
    @Test
    fun realBillExcludesCancelledTransaction() {
        val bigOnes = real.records.filter { it.amount == 5900.0 }
        assertEquals(1, bigOnes.size)
        assertEquals("2026-08-31 09:45:04", format(bigOnes.single().timestamp))
        assertEquals(Transaction.TYPE_INCOME, bigOnes.single().type)
    }

    @Test
    fun realBillTimesUseBillTimeZone() {
        val earliest = real.records.minBy { it.timestamp }
        assertEquals("2026-08-31 09:45:04", format(earliest.timestamp))
        val latest = real.records.maxBy { it.timestamp }
        assertEquals("2026-09-27 17:37:53", format(latest.timestamp))
    }

    /** 交易订单号字段带尾部制表符，必须被清掉 */
    @Test
    fun realBillOrderNumbersAreTrimmed() {
        val orderNos = real.records.mapNotNull { it.orderNo }
        assertEquals(52, orderNos.size)
        assertTrue(orderNos.none { it.contains("\t") })
        assertTrue(orderNos.none { it.isBlank() })
        assertEquals(52, orderNos.toSet().size)
    }

    @Test
    fun realBillRecordsCarrySource() {
        assertTrue(real.records.all { it.source == Transaction.SOURCE_ALIPAY })
        assertTrue(real.records.all { it.notificationId == null })
    }

    // ==================== 合成账单 ====================

    @Test
    fun parsesGbkEncodedCsv() {
        val bytes = gbkCsv(
            "导出信息：",
            "姓名：测试",
            "共3笔记录",
            "------------------------支付宝支付科技有限公司  电子客户回单------------------------",
            header,
            "2026-09-27 17:37:53,转账红包,张三,155******25,转账,支出,0.01,账户余额,交易成功,ORDER001\t,\t,,",
            "2026-09-26 17:46:39,生活服务,某公司,x@y.com,充值,支出,3.90,账户余额,交易成功,ORDER002\t,\t,,",
            "2026-09-25 10:00:00,转账红包,李四,188******88,收款,收入,20.00,账户余额,交易成功,ORDER003\t,\t,,"
        )

        val outcome = AlipayBillParser.parse(bytes)
        assertEquals(3, outcome.records.size)
        assertEquals(2, outcome.records.count { it.type == Transaction.TYPE_EXPENSE })
        assertEquals(1, outcome.records.count { it.type == Transaction.TYPE_INCOME })
    }

    @Test
    fun skipsNeutralAndInvalidRows() {
        val bytes = gbkCsv(
            header,
            "2026-09-27 17:37:53,投资理财,网商银行,/,收益发放,不计收支,0.15,,交易成功,ORDER001\t,,",
            "2026-09-25 17:37:53,转账红包,张三,/,转账,支出,1.00,账户余额,交易成功,\t,,",
            "2026-09-23 17:37:53,转账红包,张三,/,转账,支出,-,账户余额,交易成功,ORDER005\t,,",
            "2026-09-24 17:37:53,转账红包,张三,/,转账,支出,2.00,账户余额,交易成功,ORDER004\t,,"
        )

        val outcome = AlipayBillParser.parse(bytes)
        assertEquals(1, outcome.records.size)
        assertEquals(1, outcome.neutralSkipped)
        assertEquals(2, outcome.invalidSkipped)   // 订单号为空 + 金额无法解析
    }

    /**
     * 0.00 元也是真实交易 —— 真实账单 50 笔支出里有 11 笔是 0 元订单,
     * 如果按「金额为 0 就跳过」处理会静默丢掉 11 笔真实记录。
     */
    @Test
    fun importsZeroAmountTransactions() {
        val bytes = gbkCsv(
            header,
            "2026-09-27 17:37:53,转账红包,张三,/,转账,支出,0.00,账户余额,交易成功,ORDER001\t,,"
        )

        val outcome = AlipayBillParser.parse(bytes)
        assertEquals(1, outcome.records.size)
        assertEquals(0, outcome.invalidSkipped)
        assertEquals(0, outcome.neutralSkipped)
        assertEquals(0.0, outcome.records[0].amount, 0.001)
    }

    /** 商品说明含逗号且被引号包裹时不应错位 */
    @Test
    fun handlesQuotedFieldContainingComma() {
        val bytes = gbkCsv(
            header,
            """2026-09-27 17:37:53,生活服务,某商户,/,外卖,满减,支出,12.50,账户余额,交易成功,ORDER001\t,,"""
                .replace("外卖,满减", "\"外卖,满减\"")
        )

        val outcome = AlipayBillParser.parse(bytes)
        assertEquals(1, outcome.records.size)
        assertEquals(12.50, outcome.records[0].amount, 0.001)
    }

    /** 账单元数据行不应被当成明细 */
    @Test
    fun ignoresMetadataBeforeHeader() {
        val bytes = gbkCsv(
            "不计收支：3笔 5901.15元",
            "1.本回单内容可表明支付宝受理了相应支付交易申请",
            header,
            "2026-09-27 17:37:53,转账红包,张三,/,转账,支出,0.01,账户余额,交易成功,ORDER001\t,,"
        )

        val outcome = AlipayBillParser.parse(bytes)
        assertEquals(1, outcome.records.size)
        assertEquals(0, outcome.neutralSkipped)
    }

    @Test
    fun throwsHeaderNotFoundWhenNoHeaderRow() {
        val bytes = gbkCsv("随便什么内容", "还是随便什么内容")

        val error = runCatching { AlipayBillParser.parse(bytes) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is BillImportException)
        assertEquals(ImportError.HeaderNotFound, (error as BillImportException).error)
    }

    @Test
    fun throwsOnEmptyFile() {
        val error = runCatching { AlipayBillParser.parse(ByteArray(0)) }.exceptionOrNull()
        assertEquals(ImportError.EmptyFile, (error as BillImportException).error)
    }
}