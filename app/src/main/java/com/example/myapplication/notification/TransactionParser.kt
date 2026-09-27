package com.example.myapplication.notification

import com.example.myapplication.model.Transaction

/**
 * 解析微信/支付宝通知文本，提取交易信息。
 *
 * 微信包名: com.tencent.mm
 * 支付宝包名: com.eg.android.AlipayGphone
 *
 * 微信和支付宝的解析逻辑完全独立，互不影响。
 */
object TransactionParser {

    data class ParseResult(
        val source: String,
        val type: String,
        val amount: Double,
        val description: String
    )

    // ======================== 微信专用正则 ========================
    // 微信通知格式：title="微信支付", text="已支付¥0.82"
    // 金额永远是 ¥/￥ 开头，直接匹配
    private val WECHAT_AMOUNT_REGEX = Regex("""[¥￥]\s*(\d+\.\d{2})""")

    // ======================== 支付宝专用正则 ========================
    // 支付宝通知格式：title="交易提醒", text="你有一笔0.01元的支出..."
    // 金额可能在 元 前面，也可能带 ¥/￥ 符号，按优先级尝试
    private val ALIPAY_AMOUNT_REGEXES = listOf(
        Regex("""[¥￥]\s*(\d+\.\d{2})"""),              // ¥0.01 或 ￥0.01
        Regex("""(\d+\.\d{2})\s*元"""),                    // 0.01元
        Regex("""(\d+\.\d{2})"""),                          // 0.01（纯数字）
        // 兜底：匹配可能包含全角字符的数字 (０．０１)
        Regex("""[¥￥]?\s*([\d０-９]+[\.．][\d０-９]{2})""")
    )

    /**
     * 解析通知文本。
     */
    fun parse(
        packageName: String,
        text: String
    ): ParseResult? {
        return when {
            packageName.contains("tencent.mm", ignoreCase = true) -> parseWechat(text)
            packageName.contains("alipay", ignoreCase = true) -> parseAlipay(text)
            else -> null
        }
    }

    // ======================== 微信解析（保持不变） ========================

    private fun parseWechat(text: String): ParseResult? {
        val amountMatch = WECHAT_AMOUNT_REGEX.find(text) ?: return null
        val amount = amountMatch.groupValues[1].toDoubleOrNull() ?: return null
        val cleanText = text.replace(WECHAT_AMOUNT_REGEX, "").trim()

        val type = detectType(text)
        val description = extractDescription(text, Transaction.SOURCE_WECHAT, type, cleanText)

        return ParseResult(
            source = Transaction.SOURCE_WECHAT,
            type = type,
            amount = amount,
            description = description
        )
    }

    // ======================== 支付宝解析（重新设计） ========================

    private fun parseAlipay(text: String): ParseResult? {
        val amount = extractAlipayAmount(text) ?: return null
        val cleanText = removeAlipayAmount(text).trim()

        val type = detectAlipayType(text)
        val description = extractAlipayDescription(text, cleanText)

        return ParseResult(
            source = Transaction.SOURCE_ALIPAY,
            type = type,
            amount = amount,
            description = description
        )
    }

    /**
     * 支付宝金额提取：按优先级尝试多个正则。
     */
    private fun extractAlipayAmount(text: String): Double? {
        for (regex in ALIPAY_AMOUNT_REGEXES) {
            val match = regex.find(text)
            if (match != null) {
                val raw = match.groupValues[1]
                // 如果匹配到全角数字，转换为半角
                val normalized = normalizeDigits(raw)
                val amount = normalized.toDoubleOrNull()
                if (amount != null && amount > 0) {
                    return amount
                }
            }
        }
        return null
    }

    /**
     * 从文本中移除已匹配的金额。
     */
    private fun removeAlipayAmount(text: String): String {
        var result = text
        for (regex in ALIPAY_AMOUNT_REGEXES) {
            result = result.replace(regex, "")
        }
        return result
    }

    /**
     * 支付宝类型检测：使用独立的关键词列表。
     */
    private fun detectAlipayType(text: String): String {
        // 收入关键词
        val incomeKeywords = listOf(
            "到账", "收款", "收到", "转入", "入账",
            "已转到", "已存入",
            "退款", "退货", "理赔", "报销",
            "红包", "收入", "收益"
        )
        // 支出关键词
        val expenseKeywords = listOf(
            "支出", "付款", "消费", "支付", "转账",
            "购买", "下单", "缴费", "还款", "扣款",
            "代付", "充值", "花费", "缴纳"
        )

        for (kw in incomeKeywords) {
            if (text.contains(kw)) return Transaction.TYPE_INCOME
        }
        for (kw in expenseKeywords) {
            if (text.contains(kw)) return Transaction.TYPE_EXPENSE
        }
        return Transaction.TYPE_EXPENSE
    }

    /**
     * 支付宝描述提取：优先从 text 提取有意义的内容。
     */
    private fun extractAlipayDescription(fullText: String, cleanText: String): String {
        // 尝试提取 "你有一笔X元的XX" 中的 XX（交易类型）
        val typePattern = Regex("""一?\s*笔\s*\S+\s*元的?\s*(\S+)""")
        val typeMatch = typePattern.find(fullText)
        if (typeMatch != null) {
            return typeMatch.groupValues[1].trim().take(20)
        }

        // 从清理后的文本中提取短内容
        val cleaned = cleanText
            .replace(Regex("""[，。！？、\s]+"""), " ")
            .trim()
            .take(30)
            .ifBlank { "支付宝交易" }

        return cleaned
    }

    // ======================== 通用方法 ========================

    /**
     * 类型检测（微信共用此方法）。
     */
    private fun detectType(text: String): String {
        val incomeKeywords = listOf(
            "到账", "收款", "收到", "转入", "入账",
            "已转到你的", "已存入",
            "退款", "退货", "理赔", "报销",
            "红包", "收入"
        )
        val expenseKeywords = listOf(
            "支出", "付款", "消费", "支付", "转账",
            "购买", "下单", "缴费", "还款", "扣款",
            "代付", "充值"
        )

        for (kw in incomeKeywords) {
            if (text.contains(kw)) return Transaction.TYPE_INCOME
        }
        for (kw in expenseKeywords) {
            if (text.contains(kw)) return Transaction.TYPE_EXPENSE
        }
        return Transaction.TYPE_EXPENSE
    }

    /**
     * 描述提取（微信共用此方法）。
     */
    private fun extractDescription(
        fullText: String,
        source: String,
        type: String,
        cleanText: String
    ): String {
        val namePatterns = listOf(
            Regex("""收[款付].*?(\S{2,10})(?:[，。]|$)"""),
            Regex("""(?:对方|商户|商家)[：:](\S{2,10})""")
        )

        for (pattern in namePatterns) {
            val match = pattern.find(fullText)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }

        val cleaned = cleanText
            .replace(Regex("""[，。！？、\s]+"""), " ")
            .trim()
            .take(30)
            .ifBlank { "${source}交易" }

        return cleaned
    }

    /**
     * 将全角数字转换为半角（例如 ０→0, １→1）。
     */
    private fun normalizeDigits(text: String): String {
        return text.map { c ->
            when {
                c in '０'..'９' -> ('0' + (c - '０'))
                c in 'Ａ'..'Ｚ' -> ('A' + (c - 'Ａ'))
                c in 'ａ'..'ｚ' -> ('a' + (c - 'ａ'))
                c == '．' -> '.'
                c == '，' -> ','
                else -> c
            }
        }.joinToString("")
    }
}