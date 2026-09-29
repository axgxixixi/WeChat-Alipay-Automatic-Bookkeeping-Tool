package com.example.myapplication.importer

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 引号感知的 CSV 解析器。
 *
 * 支付宝导出的明细虽然多数字段不带引号，但商品说明里可能出现逗号，
 * 因此按 RFC4180 的规则处理引号，避免字段错位。
 */
internal object CsvReader {

    /**
     * 解析 CSV 文本为二维字段表。
     *
     * 规则：
     * - 只有**字段首字符**的 `"` 才开启引用；字段中间的引号按字面字符处理
     *   （这样文件里偶发的多余引号不会破坏整行结构）
     * - 引用内的 `""` 表示一个字面引号
     * - 引用内的 `,` / 换行不切分字段
     * - 每个字段做 trim（去掉支付宝订单号字段尾部的制表符、CRLF 残留等）
     */
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldStarted = false
        var i = 0

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"')   // "" → 一个字面引号
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(c)
                    }
                }

                // 仅字段首字符的引号才是引用开始
                c == '"' && field.isEmpty() && !fieldStarted -> {
                    inQuotes = true
                    fieldStarted = true
                }

                c == ',' -> {
                    row.add(clean(field))
                    field.setLength(0)
                    fieldStarted = false
                }

                c == '\r' || c == '\n' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(clean(field))
                    field.setLength(0)
                    fieldStarted = false
                    rows.add(row)
                    row = mutableListOf()
                }

                else -> {
                    field.append(c)
                    fieldStarted = true
                }
            }
            i++
        }

        // 收尾：最后一行没有换行符结尾时也要收进来
        if (field.isNotEmpty() || fieldStarted || row.isNotEmpty()) {
            row.add(clean(field))
            rows.add(row)
        }
        return rows
    }

    /** trim 掉空白，含全角空格 U+3000（Kotlin 默认的 trim 只处理 <= ' '） */
    private fun clean(field: StringBuilder): String =
        field.toString().trim { it <= ' ' || it == '　' }
}

/**
 * 账单文件文本解码。
 *
 * 支付宝导出的 csv 是 **GBK/GB18030** 编码（不是 UTF-8），微信的 xlsx 内部是 UTF-8。
 */
internal object CsvTextDecoder {

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. UTF-8 BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }

        // 2. 严格 UTF-8 探测：能干净解码且含中文 → 视为 UTF-8
        val utf8 = decodeStrictUtf8(bytes)
        if (utf8 != null && utf8.any { it.code in 0x4E00..0x9FFF }) return utf8

        // 3. 回退 GB18030（GBK 的超集，Android 与 JVM 都内置）
        return try {
            String(bytes, Charset.forName("GB18030"))
        } catch (e: Exception) {
            throw BillImportException(ImportError.DecodeFailed)
        }
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: Exception) {
        null
    }
}