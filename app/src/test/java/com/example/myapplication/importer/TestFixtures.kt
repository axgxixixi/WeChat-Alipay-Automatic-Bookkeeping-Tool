package com.example.myapplication.importer

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 测试用的账单样本与合成 xlsx 工具。 */
internal object TestFixtures {

    /** 读取 app/src/test/resources/bills/ 下的真实账单样本 */
    fun bytes(resourceName: String): ByteArray =
        TestFixtures::class.java.classLoader
            ?.getResourceAsStream("bills/$resourceName")
            ?.readBytes()
            ?: error("缺少测试账单资源: bills/$resourceName")

    fun xlsx(vararg entries: Pair<String, String>): ByteArray {
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

    fun sheet(rowsXml: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
           <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
             <sheetData>$rowsXml</sheetData>
           </worksheet>"""

    /**
     * 用内联字符串合成一个只含单表的 xlsx。
     *
     * 值形如数字（且长度较短）的写成数字单元格，其余写成内联字符串 ——
     * 后者可避免超长的交易单号被当成数字。
     */
    fun syntheticXlsx(rows: List<List<String?>>): ByteArray {
        val rowsXml = rows.mapIndexed { rowIndex, row ->
            val cells = row.mapIndexedNotNull { colIndex, value ->
                if (value == null) return@mapIndexedNotNull null
                val ref = columnName(colIndex) + (rowIndex + 1)
                if (looksNumeric(value)) {
                    """<c r="$ref"><v>$value</v></c>"""
                } else {
                    """<c r="$ref" t="inlineStr"><is><t>${escapeXml(value)}</t></is></c>"""
                }
            }.joinToString("")
            """<row r="${rowIndex + 1}">$cells</row>"""
        }.joinToString("")

        return xlsx("xl/worksheets/sheet1.xml" to sheet(rowsXml))
    }

    private fun looksNumeric(value: String): Boolean =
        value.length <= 15 && Regex("""^-?\d+(\.\d+)?$""").matches(value)

    private fun columnName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + i % 26))
            i = i / 26 - 1
            if (i < 0) break
        }
        return sb.toString()
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}