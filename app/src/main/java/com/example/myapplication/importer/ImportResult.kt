package com.example.myapplication.importer

import com.example.myapplication.model.Transaction

/**
 * 解析阶段的结果。
 *
 * @param records        成功解析出的、可入库的交易记录
 * @param neutralSkipped 跳过的中性交易数（微信「中性交易」/支付宝「不计收支」）
 * @param invalidSkipped 跳过的无效记录数（金额缺失/非法、订单号为空、字段不足）
 *
 * 注意：金额为 0.00 的记录**不算无效** —— 账单里存在大量 0 元订单，
 * 它们是真实交易，丢弃会造成静默的数据缺失。
 */
data class ParseOutcome(
    val records: List<Transaction>,
    val neutralSkipped: Int,
    val invalidSkipped: Int
)

/**
 * 一次导入的最终结果。
 *
 * @param inserted       实际写入数据库的条数
 * @param duplicate      因订单号重复被跳过的条数
 * @param neutralSkipped 因是中性交易被跳过的条数
 * @param invalidSkipped 因记录无效被跳过的条数
 */
data class ImportResult(
    val inserted: Int,
    val duplicate: Int,
    val neutralSkipped: Int = 0,
    val invalidSkipped: Int = 0
) {
    /** 拼给用户看的结果文案，数量为 0 的项会被省略。 */
    fun message(): String {
        val parts = mutableListOf("成功导入 $inserted 笔")
        if (duplicate > 0) parts += "跳过重复 $duplicate 笔"
        if (neutralSkipped > 0) parts += "忽略不计收支 $neutralSkipped 笔"
        if (invalidSkipped > 0) parts += "跳过无效记录 $invalidSkipped 笔"
        return parts.joinToString("，")
    }
}

/** 导入过程中可能出现的错误，每种都带一句可直接展示给用户的中文说明。 */
sealed class ImportError(val userMessage: String) {
    data object EmptyFile : ImportError("文件为空，请重新选择")

    data object FileTooLarge : ImportError("文件过大，请导出更短时间范围的账单")

    data object ReadFailed : ImportError("读取文件失败，请重新选择")

    data object DecodeFailed : ImportError("账单编码解析失败，请勿修改导出文件")

    data object HeaderNotFound : ImportError("未找到账单表头，文件可能不完整或已被修改")

    data object UnrecognizedFile : ImportError("无法识别的账单文件，请选择微信或支付宝导出的原始账单")

    /** 选错了账单来源（比如在「微信账单」里选了支付宝的 csv）。 */
    data class WrongBillType(val actual: BillType) : ImportError(
        "所选文件是${actual.displayName}，请选择「${actual.displayName}」后重试"
    )

    data object NoRecords : ImportError("账单中没有可导入的收入 / 支出记录")
}

/** 把 [ImportError] 当异常抛出用，便于在协程里用 runCatching 统一处理。 */
class BillImportException(val error: ImportError) : Exception(error.userMessage)