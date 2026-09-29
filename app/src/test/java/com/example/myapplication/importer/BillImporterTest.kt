package com.example.myapplication.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入入口：类型识别与校验。
 *
 * 类型判断以**文件内容**为准，不看扩展名 —— 系统文件选择器只按 MIME 匹配，
 * 用户完全可能选错来源。
 */
class BillImporterTest {

    private val wechatBytes by lazy { TestFixtures.bytes("wechat_sample.xlsx") }
    private val alipayBytes by lazy { TestFixtures.bytes("alipay_sample.csv") }

    @Test
    fun sniffsXlsxAsWechat() {
        assertEquals(BillType.WECHAT, BillImporter.sniff(wechatBytes))
    }

    @Test
    fun sniffsAlipayCsvAsAlipay() {
        assertEquals(BillType.ALIPAY, BillImporter.sniff(alipayBytes))
    }

    @Test
    fun sniffReturnsNullForGarbage() {
        assertNull(BillImporter.sniff("这不是账单".toByteArray()))
        assertNull(BillImporter.sniff(ByteArray(0)))
    }

    @Test
    fun parsesMatchingTypes() {
        assertEquals(166, BillImporter.parse(BillType.WECHAT, wechatBytes).records.size)
        assertEquals(52, BillImporter.parse(BillType.ALIPAY, alipayBytes).records.size)
    }

    /** 在「微信账单」里选了支付宝的 csv */
    @Test
    fun rejectsAlipayFileWhenWechatSelected() {
        val error = runCatching { BillImporter.parse(BillType.WECHAT, alipayBytes) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is BillImportException)
        assertEquals(ImportError.WrongBillType(BillType.ALIPAY), (error as BillImportException).error)
    }

    /** 在「支付宝账单」里选了微信的 xlsx */
    @Test
    fun rejectsWechatFileWhenAlipaySelected() {
        val error = runCatching { BillImporter.parse(BillType.ALIPAY, wechatBytes) }.exceptionOrNull()
        assertEquals(ImportError.WrongBillType(BillType.WECHAT), (error as BillImportException).error)
    }

    @Test
    fun rejectsEmptyFile() {
        val error = runCatching { BillImporter.parse(BillType.WECHAT, ByteArray(0)) }.exceptionOrNull()
        assertEquals(ImportError.EmptyFile, (error as BillImportException).error)
    }

    @Test
    fun rejectsUnrecognizedFile() {
        // 注意: 这段文字里不能出现「支付宝」「交易订单号」等嗅探关键字,
        // 否则会被识别成支付宝账单、进而以 HeaderNotFound 失败, 测不到这里的路径。
        val error = runCatching {
            BillImporter.parse(BillType.ALIPAY, "随便一段文字，什么都识别不出来".toByteArray())
        }.exceptionOrNull()
        assertEquals(ImportError.UnrecognizedFile, (error as BillImportException).error)
    }

    /** 错误文案里要带上实际识别出的来源，方便用户纠正 */
    @Test
    fun wrongTypeMessageNamesActualSource() {
        val message = ImportError.WrongBillType(BillType.ALIPAY).userMessage
        assertTrue(message.contains("支付宝账单"))
        assertTrue(message.contains("支付宝账单") && message.contains("重试"))
    }
}