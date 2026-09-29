package com.example.myapplication.model

data class Transaction(
    val id: Long = 0,
    val source: String,                // "微信" / "支付宝"
    val type: String,                  // "收入" / "支出"
    val amount: Double,
    val description: String,           // 交易描述
    val rawText: String,               // 原始通知全文
    val timestamp: Long = System.currentTimeMillis(),
    val notificationId: Int? = null,   // 通知 ID，用于防重复
    val note: String = "",              // 用户备注
    val orderNo: String? = null         // 账单导入的订单号(交易单号/交易订单号)；
                                        // 通知监听写入的行保持 null
) {
    companion object {
        const val SOURCE_WECHAT = "微信"
        const val SOURCE_ALIPAY = "支付宝"
        const val TYPE_INCOME = "收入"
        const val TYPE_EXPENSE = "支出"
    }
}

data class MonthlySummary(
    val totalIncome: Double = 0.0,
    val totalExpense: Double = 0.0
) {
    val netIncome: Double get() = totalIncome - totalExpense
}