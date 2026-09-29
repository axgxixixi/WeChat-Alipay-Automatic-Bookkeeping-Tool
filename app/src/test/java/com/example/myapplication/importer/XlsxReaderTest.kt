package com.example.myapplication.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxReaderTest {

    /** 在内存里拼一个最小 xlsx（ZIP 包） */
    private fun xlsx(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sharedStrings(vararg items: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
           <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">${
            items.joinToString("") { "<si><t>$it</t></si>" }
        }</sst>"""

    private fun sheet(rowsXml: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
           <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
             <sheetData>$rowsXml</sheetData>
           </worksheet>"""

    /**
     * 真实微信账单里 `sheet1.xml` 排在 `sharedStrings.xml` **之前**，
     * 单遍流式解析拿不到字符串表 —— 这正是需要两遍扫描的原因。
     */
    @Test
    fun resolvesSharedStringsWhenSheetEntryComesFirst() {
        val bytes = xlsx(
            "xl/worksheets/sheet1.xml" to sheet(
                """<row r="1"><c r="A1" t="s"><v>0</v></c></row>"""
            ),
            "xl/sharedStrings.xml" to sharedStrings("交易时间")
        )

        val rows = XlsxReader.readFirstSheet(bytes, headerMarker = "交易时间")
        assertEquals(1, rows.size)
        assertEquals("交易时间", rows[0].cells[0])
    }

    /** 一个 <si> 可能由多个富文本段组成，必须拼接 */
    @Test
    fun concatenatesMultipleTextRunsInOneSharedString() {
        val bytes = xlsx(
            "xl/sharedStrings.xml" to
                """<?xml version="1.0" encoding="UTF-8"?>
                   <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                     <si><r><t>已支</t></r><r><t>付¥0.82</t></r></si>
                   </sst>""",
            "xl/worksheets/sheet1.xml" to sheet(
                """<row r="1"><c r="A1" t="s"><v>0</v></c></row>"""
            )
        )

        val rows = XlsxReader.readFirstSheet(bytes)
        assertEquals("已支付¥0.82", rows[0].cells[0])
    }

    /** 空单元格会被省略，必须按列索引取值而不是按位置 */
    @Test
    fun keepsColumnPositionsWhenCellsAreOmitted() {
        val bytes = xlsx(
            "xl/sharedStrings.xml" to sharedStrings("A", "C"),
            "xl/worksheets/sheet1.xml" to sheet(
                """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>"""
            )
        )

        val row = XlsxReader.readFirstSheet(bytes)[0]
        assertEquals(setOf(0, 2), row.cells.keys)   // B 列缺席
        assertEquals("A", row.cells[0])
        assertEquals("C", row.cells[2])
    }

    /** 无 t 属性的单元格是数字，直接取原文 */
    @Test
    fun readsNumericCells() {
        val bytes = xlsx(
            "xl/worksheets/sheet1.xml" to sheet(
                """<row r="19"><c r="A19"><v>46293.96523148148</v></c><c r="F19"><v>16.33</v></c></row>"""
            )
        )

        val row = XlsxReader.readFirstSheet(bytes)[0]
        assertEquals("46293.96523148148", row.cells[0])
        assertEquals("16.33", row.cells[5])
    }

    /** 多字母列号：AA = 26 */
    @Test
    fun resolvesMultiLetterColumnReference() {
        val bytes = xlsx(
            "xl/sharedStrings.xml" to sharedStrings("Z", "AA"),
            "xl/worksheets/sheet1.xml" to sheet(
                """<row r="1"><c r="Z1" t="s"><v>0</v></c><c r="AA1" t="s"><v>1</v></c></row>"""
            )
        )

        val row = XlsxReader.readFirstSheet(bytes)[0]
        assertEquals(25, row.cells.keys.first { row.cells[it] == "Z" })
        assertEquals(26, row.cells.keys.first { row.cells[it] == "AA" })
    }

    /** sheet1 不含表头标记时应继续找 sheet2 */
    @Test
    fun picksSheetContainingHeaderMarker() {
        val bytes = xlsx(
            "xl/sharedStrings.xml" to sharedStrings("无关内容", "交易时间"),
            "xl/worksheets/sheet1.xml" to sheet(
                """<row r="1"><c r="A1" t="s"><v>0</v></c></row>"""
            ),
            "xl/worksheets/sheet2.xml" to sheet(
                """<row r="1"><c r="A1" t="s"><v>1</v></c></row>"""
            )
        )

        val rows = XlsxReader.readFirstSheet(bytes, headerMarker = "交易时间")
        assertEquals("交易时间", rows[0].cells[0])
    }

    /** 读到行号 */
    @Test
    fun readsRowNumbers() {
        val bytes = xlsx(
            "xl/sharedStrings.xml" to sharedStrings("x"),
            "xl/worksheets/sheet1.xml" to sheet(
                """<row r="17"><c r="A17" t="s"><v>0</v></c></row>"""
            )
        )

        assertEquals(17, XlsxReader.readFirstSheet(bytes)[0].number)
    }

    @Test
    fun missingSharedStringsEntryDoesNotThrow() {
        val bytes = xlsx(
            "xl/worksheets/sheet1.xml" to sheet(
                """<row r="1"><c r="A1" t="s"><v>0</v></c></row>"""
            )
        )

        val rows = XlsxReader.readFirstSheet(bytes)
        assertTrue(rows.isEmpty() || rows[0].cells[0].orEmpty().isEmpty())
    }
}