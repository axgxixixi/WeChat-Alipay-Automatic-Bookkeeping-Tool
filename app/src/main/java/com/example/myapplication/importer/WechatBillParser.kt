package com.example.myapplication.importer

import com.example.myapplication.model.Transaction

/**
 * 微信账单（xlsx）解析器。
 *
 * 账单结构（已对真实文件验证）：
 * - 第 18 行是表头，第 19 行起是数据
 * - 「交易时间」是 Excel 日期序列号，不是字符串
 * - 「金额(元)」是数字
 * - 「收/支」只有 `收入` / `支出`（中性交易为独立分类，不计入收/支）
 *
 * 列位置**按表头文字定位**，不写死列号，以便账单格式微调后仍能工作。
 */
internal object WechatBillParser {

    private const val HEADER_TIME = "交易时间"
    private const val HEADER_TYPE = "交易类型"
    private const val HEADER_COUNTERPARTY = "交易对方"
    private const val HEADER_PRODUCT = "商品"
    private const val HEADER_DIRECTION = "收/支"
    private const val HEADER_AMOUNT = "金额(元)"
    private const val HEADER_AMOUNT_ALT = "金额"
    private const val HEADER_ORDER_NO = "交易单号"
    private const val HEADER_NOTE = "备注"

    fun parse(bytes: ByteArray): ParseOutcome {
        val rows = XlsxReader.readFirstSheet(bytes, HEADER_TIME)
        if (rows.isEmpty()) throw BillImportException(ImportError.HeaderNotFound)

        val headerIndex = rows.indexOfFirst { row ->
            row.cells.values.any { it.trim() == HEADER_TIME }
        }
        if (headerIndex < 0) throw BillImportException(ImportError.HeaderNotFound)

        // 表头文字 → 列索引
        val columns = rows[headerIndex].cells.entries
            .associate { (index, text) -> text.trim() to index }
        val timeCol = columns[HEADER_TIME]
        val directionCol = columns[HEADER_DIRECTION]
        val amountCol = columns[HEADER_AMOUNT] ?: columns[HEADER_AMOUNT_ALT]
        val orderNoCol = columns[HEADER_ORDER_NO]
        if (timeCol == null || directionCol == null || amountCol == null || orderNoCol == null) {
            throw BillImportException(ImportError.HeaderNotFound)
        }

        val records = mutableListOf<Transaction>()
        var neutralSkipped = 0
        var invalidSkipped = 0

        for (i in headerIndex + 1..rows.lastIndex) {
            val cells = rows[i].cells

            // 表尾/空行：时间不是有效序列号就跳过
            val serial = cells[timeCol]?.trim()?.toDoubleOrNull() ?: continue

            val direction = cells[directionCol]?.trim().orEmpty()
            val type = BillFields.directionToType(direction)
            if (type == null) {         // 中性交易 / 空值
                neutralSkipped++
                continue
            }

            // 金额为 0.00 的行仍然是真实交易, 只有字段缺失/无法解析/为负才算无效
            val amount = BillFields.parseAmount(cells[amountCol])
            if (amount == null || amount < 0.0) {
                invalidSkipped++
                continue
            }

            // 没有订单号就无法去重, 宁可跳过也不要造成重复数据
            val orderNo = cells[orderNoCol]?.trim().orEmpty()
            if (orderNo.isEmpty()) {
                invalidSkipped++
                continue
            }

            records += Transaction(
                source = Transaction.SOURCE_WECHAT,
                type = type,
                amount = amount,
                description = BillFields.buildDescription(
                    cells[columns[HEADER_COUNTERPARTY]],
                    cells[columns[HEADER_PRODUCT]],
                    fallback = "微信交易"
                ),
                rawText = "账单导入：" + cells[columns[HEADER_TYPE]]?.trim().orEmpty(),
                timestamp = ExcelDate.serialToMillis(serial),
                orderNo = orderNo,
                note = BillFields.normalizeNote(cells[columns[HEADER_NOTE]])
            )
        }

        return ParseOutcome(records, neutralSkipped, invalidSkipped)
    }
}