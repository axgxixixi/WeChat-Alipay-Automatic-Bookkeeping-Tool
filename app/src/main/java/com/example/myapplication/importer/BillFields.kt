package com.example.myapplication.importer

import com.example.myapplication.model.Transaction

/**
 * 微信 / 支付宝两个解析器共用的字段处理逻辑。
 */
internal object BillFields {

    /**
     * 「收/支」列 → 交易类型。
     *
     * 返回 null 表示是中性交易（微信「中性交易」/ 支付宝「不计收支」）或空值，
     * 调用方应跳过并计入 neutralSkipped。
     *
     * 注意：始终以「收/支」列为准。支付宝账单头部的统计数字是按「交易分类」
     * 汇总的，与逐笔的「收/支」列并不一致（账单「特别提示」第 6 条自己承认了这点）。
     */
    fun directionToType(direction: String): String? = when (direction.trim()) {
        Transaction.TYPE_INCOME -> Transaction.TYPE_INCOME
        Transaction.TYPE_EXPENSE -> Transaction.TYPE_EXPENSE
        else -> null
    }

    /** 去掉千分位与货币符号后转数字 */
    fun parseAmount(raw: String?): Double? {
        val cleaned = raw?.trim()
            ?.replace(",", "")
            ?.replace("¥", "")
            ?.replace("￥", "")
            .orEmpty()
        return cleaned.toDoubleOrNull()
    }

    /** 拼描述：多个部分用 ` · ` 连接，去重、剔除占位符并截断 */
    fun buildDescription(vararg parts: String?, fallback: String): String {
        val cleaned = parts
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() && it != "/" && it != "-" }
            .distinct()
        return cleaned.joinToString(" · ").take(60).ifBlank { fallback }
    }

    /** 备注列常见占位符 `/`、`-`，归一化为空串 */
    fun normalizeNote(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed == "/" || trimmed == "-") "" else trimmed
    }
}