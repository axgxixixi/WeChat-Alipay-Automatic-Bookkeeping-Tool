package com.example.myapplication.importer

import com.example.myapplication.model.Transaction

/**
 * 支付宝账单（csv）解析器。
 *
 * 账单结构（已对真实文件验证）：
 * - 文件编码是 **GBK/GB18030**，不是 UTF-8
 * - 前 22 行是元数据，随后是回单分隔线、表头，最后是明细
 * - 「交易时间」是 `yyyy-MM-dd HH:mm:ss` 字符串（UTC+08:00）
 * - 「交易订单号」字段尾部带制表符，需 trim
 * - 「收/支」取值有 `收入` / `支出` / `不计收支`
 *
 * ⚠️ 账单元数据里的统计数字（收入/支出/不计收支笔数）是按「交易分类」汇总的，
 * 与逐笔的「收/支」列并不一致 —— 账单「特别提示」第 6 条自己承认了这点。
 * 因此这里**一律以「收/支」列为准**。
 */
internal object AlipayBillParser {

    private const val HEADER_TIME = "交易时间"
    private const val HEADER_CATEGORY = "交易分类"
    private const val HEADER_COUNTERPARTY = "交易对方"
    private const val HEADER_PRODUCT = "商品说明"
    private const val HEADER_DIRECTION = "收/支"
    private const val HEADER_AMOUNT = "金额"
    private const val HEADER_ORDER_NO = "交易订单号"
    private const val HEADER_NOTE = "备注"

    fun parse(bytes: ByteArray): ParseOutcome {
        val text = CsvTextDecoder.decode(bytes)
        if (text.isBlank()) throw BillImportException(ImportError.EmptyFile)

        val allRows = CsvReader.parse(text)

        // 表头行：同时含「交易时间」和「收/支」的那一行（元数据里不会同时出现这两个词）
        val headerIndex = allRows.indexOfFirst { row ->
            row.any { it == HEADER_TIME } && row.any { it == HEADER_DIRECTION }
        }
        if (headerIndex < 0) throw BillImportException(ImportError.HeaderNotFound)

        val columns = allRows[headerIndex].withIndex()
            .associate { (index, name) -> name.trim() to index }
        val timeCol = columns[HEADER_TIME]
        val directionCol = columns[HEADER_DIRECTION]
        val amountCol = columns[HEADER_AMOUNT]
        val orderNoCol = columns[HEADER_ORDER_NO]
        if (timeCol == null || directionCol == null || amountCol == null || orderNoCol == null) {
            throw BillImportException(ImportError.HeaderNotFound)
        }
        val requiredSize = maxOf(timeCol, directionCol, amountCol, orderNoCol) + 1

        val records = mutableListOf<Transaction>()
        var neutralSkipped = 0
        var invalidSkipped = 0

        for (i in headerIndex + 1..allRows.lastIndex) {
            val fields = allRows[i]
            if (fields.size < requiredSize) {   // 表尾/元数据残行
                invalidSkipped++
                continue
            }

            val timestamp = ExcelDate.parseAlipayTime(fields[timeCol])
            if (timestamp == null) {            // 非明细行
                invalidSkipped++
                continue
            }

            val type = BillFields.directionToType(fields[directionCol])
            if (type == null) {                 // 不计收支 / 空值
                neutralSkipped++
                continue
            }

            // 金额为 0.00 的行仍然是真实交易(账单里大量存在 0 元订单),
            // 只有字段缺失/无法解析/为负才算无效 —— 否则会静默丢掉真实记录。
            val amount = BillFields.parseAmount(fields[amountCol])
            if (amount == null || amount < 0.0) {
                invalidSkipped++
                continue
            }

            // trim() 同时去掉订单号尾部残留的制表符
            val orderNo = fields[orderNoCol].trim()
            if (orderNo.isEmpty()) {
                invalidSkipped++
                continue
            }

            records += Transaction(
                source = Transaction.SOURCE_ALIPAY,
                type = type,
                amount = amount,
                description = BillFields.buildDescription(
                    fields.at(columns[HEADER_COUNTERPARTY]),
                    fields.at(columns[HEADER_PRODUCT]),
                    fallback = "支付宝交易"
                ),
                rawText = "账单导入：" + fields.at(columns[HEADER_CATEGORY]),
                timestamp = timestamp,
                orderNo = orderNo,
                note = BillFields.normalizeNote(fields.at(columns[HEADER_NOTE]))
            )
        }

        return ParseOutcome(records, neutralSkipped, invalidSkipped)
    }

    /** 按列索引安全取值；列不存在或该行字段不足时返回空串 */
    private fun List<String>.at(index: Int?): String =
        if (index != null && index in indices) this[index] else ""
}