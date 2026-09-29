package com.example.myapplication.importer

/**
 * 账单导入入口：识别文件类型并分发给对应解析器。
 *
 * 类型校验以**文件内容**为准，不看扩展名 —— 系统文件选择器只按 MIME 匹配，
 * 而各厂商文件管理器对 xlsx/csv 的 MIME 推断并不一致。
 */
object BillImporter {

    /**
     * 识别账单文件的实际来源。
     *
     * - xlsx 是 ZIP 包，以 `PK` 开头 → 微信
     * - 其余按文本内容判断（支付宝 csv 是 GBK 编码，由解码器统一处理）
     *
     * @return 识别结果；无法识别时返回 null
     */
    fun sniff(bytes: ByteArray): BillType? {
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return BillType.WECHAT
        }
        val text = try {
            CsvTextDecoder.decode(bytes)
        } catch (e: Exception) {
            return null
        }
        return when {
            text.contains("支付宝") && text.contains("交易订单号") -> BillType.ALIPAY
            text.contains("微信支付账单") -> BillType.WECHAT
            else -> null
        }
    }

    /**
     * 解析账单。
     *
     * @param type  用户在 BottomSheet 里选择的来源
     * @param bytes 账单文件内容
     * @throws BillImportException 文件为空 / 无法识别 / 与所选来源不符 / 缺少表头
     */
    fun parse(type: BillType, bytes: ByteArray): ParseOutcome {
        if (bytes.isEmpty()) throw BillImportException(ImportError.EmptyFile)

        val actual = sniff(bytes) ?: throw BillImportException(ImportError.UnrecognizedFile)
        if (actual != type) throw BillImportException(ImportError.WrongBillType(actual))

        return when (type) {
            BillType.WECHAT -> WechatBillParser.parse(bytes)
            BillType.ALIPAY -> AlipayBillParser.parse(bytes)
        }
    }
}