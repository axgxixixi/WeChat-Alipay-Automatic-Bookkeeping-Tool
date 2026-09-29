package com.example.myapplication.importer

import com.example.myapplication.model.Transaction

/**
 * 支持的账单来源。
 *
 * 每种来源对应一个导出文件格式：
 * - 微信：xlsx（ZIP + XML）
 * - 支付宝：csv（GBK 编码）
 */
enum class BillType(
    /** BottomSheet 上显示的名称 */
    val displayName: String,
    /** BottomSheet 上的副标题，说明文件格式 */
    val hint: String,
    /** 对应的 [Transaction.source] 常量 */
    val source: String,
    /**
     * 传给系统文件选择器的 MIME 类型。
     *
     * SAF 只按 MIME 匹配、不认扩展名，而各厂商文件管理器对 xlsx/csv 的
     * MIME 推断并不一致，因此除标准值外统一补一个 application/octet-stream 兜底。
     * 真正的类型校验靠 [BillImporter.sniff] 读文件内容，不依赖这里。
     */
    val mimeTypes: Array<String>
) {
    WECHAT(
        displayName = "微信账单",
        hint = "微信支付账单流水文件 (.xlsx)",
        source = Transaction.SOURCE_WECHAT,
        mimeTypes = arrayOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream",
            "application/zip"
        )
    ),
    ALIPAY(
        displayName = "支付宝账单",
        hint = "支付宝交易明细 (.csv)",
        source = Transaction.SOURCE_ALIPAY,
        mimeTypes = arrayOf(
            "text/csv",
            "text/comma-separated-values",
            "application/csv",
            "text/plain",
            "application/octet-stream"
        )
    );

    /** 用于判断两个类型是否同一个来源 */
    fun isSameAs(other: BillType?): Boolean = this == other

    companion object {
        /** 按文件名后缀粗略推断来源（仅用于提示，不作校验） */
        fun fromFileName(name: String?): BillType? {
            val lower = name?.lowercase() ?: return null
            return when {
                lower.endsWith(".xlsx") || lower.endsWith(".xls") -> WECHAT
                lower.endsWith(".csv") -> ALIPAY
                else -> null
            }
        }
    }
}