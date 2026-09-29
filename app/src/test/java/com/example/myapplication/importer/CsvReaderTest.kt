package com.example.myapplication.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class CsvReaderTest {

    @Test
    fun splitsPlainFields() {
        val rows = CsvReader.parse("a,b,c\n1,2,3")
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b", "c"), rows[0])
        assertEquals(listOf("1", "2", "3"), rows[1])
    }

    @Test
    fun handlesCrlf() {
        val rows = CsvReader.parse("a,b\r\nc,d\r\n")
        assertEquals(2, rows.size)
        assertEquals(listOf("c", "d"), rows[1])
    }

    @Test
    fun keepsCommaInsideQuotes() {
        val rows = CsvReader.parse("""a,"b,c",d""")
        assertEquals(listOf("a", "b,c", "d"), rows[0])
    }

    @Test
    fun unescapesDoubledQuote() {
        val rows = CsvReader.parse("a,\"say \"\"hi\"\"\",c")
        assertEquals(listOf("a", "say \"hi\"", "c"), rows[0])
    }

    @Test
    fun keepsNewlineInsideQuotes() {
        val rows = CsvReader.parse("a,\"line1\nline2\",c")
        assertEquals(1, rows.size)
        assertEquals("line1\nline2", rows[0][1])
    }

    /** 字段中间的引号按字面处理，不应破坏整行结构 */
    @Test
    fun quoteInMiddleOfFieldIsLiteral() {
        val rows = CsvReader.parse("a,b\"c,d")
        assertEquals(listOf("a", "b\"c", "d"), rows[0])
    }

    /** 支付宝订单号字段尾部带制表符，trim 必须去掉 */
    @Test
    fun trimsTrailingTab() {
        val rows = CsvReader.parse("x,20260927200040011100110026775850\t,y")
        assertEquals("20260927200040011100110026775850", rows[0][1])
    }

    @Test
    fun keepsTrailingEmptyFields() {
        val rows = CsvReader.parse("a,b,,,")
        assertEquals(5, rows[0].size)
        assertEquals(listOf("a", "b", "", "", ""), rows[0])
    }

    @Test
    fun trimsFullWidthSpace() {
        val rows = CsvReader.parse("a,　b　,c")
        assertEquals("b", rows[0][1])
    }

    @Test
    fun lastRowWithoutNewlineIsKept() {
        val rows = CsvReader.parse("a,b\nc,d")
        assertEquals(2, rows.size)
        assertEquals(listOf("c", "d"), rows[1])
    }
}

class CsvTextDecoderTest {

    @Test
    fun decodesGbkChinese() {
        val gbk = "支付宝交易明细".toByteArray(Charset.forName("GB18030"))
        // 前提校验: 这串 GBK 字节不是合法 UTF-8, 解码器必须回退到 GB18030 才能正确还原
        val asUtf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .let { decoder ->
                try {
                    decoder.decode(ByteBuffer.wrap(gbk)).toString()
                } catch (e: Exception) {
                    null
                }
            }
        val isValidUtf8WithCjk = asUtf8?.any { it.code in 0x4E00..0x9FFF } == true
        assertFalse("样例字节不应被误判为含中文的 UTF-8", isValidUtf8WithCjk)

        assertEquals("支付宝交易明细", CsvTextDecoder.decode(gbk))
    }

    @Test
    fun stripsUtf8Bom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val text = "交易时间".toByteArray(Charsets.UTF_8)
        assertEquals("交易时间", CsvTextDecoder.decode(bom + text))
    }

    @Test
    fun decodesUtf8Chinese() {
        assertEquals("支付宝交易明细", CsvTextDecoder.decode("支付宝交易明细".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun decodesPlainAscii() {
        assertTrue(CsvTextDecoder.decode("a,b,c".toByteArray()).startsWith("a,b,c"))
    }
}