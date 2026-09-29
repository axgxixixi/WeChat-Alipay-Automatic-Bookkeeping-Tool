package com.example.myapplication.importer

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * 最小 xlsx 读取器。
 *
 * xlsx 本质是一个 ZIP 包，内含：
 * - `xl/sharedStrings.xml` —— 共享字符串表，单元格用 0 基下标引用
 * - `xl/worksheets/sheetN.xml` —— 单元格数据
 *
 * 这里不复用第三方库：可用方案中 Apache POI 依赖 java.awt、体积巨大，
 * 轻量库（如 fastexcel）基于 StAX，而 **Android 没有 `javax.xml.stream` 包**，
 * 运行时会直接失败。手写反而最简单可靠。
 *
 * 用 SAX 而不是 `android.util.Xml.newPullParser()`：后者是 Android 专有类，
 * 在 JVM 单元测试中会抛 "not implemented"，会让整个解析链路无法离线测试。
 */
internal object XlsxReader {

    /**
     * 一行数据。
     *
     * @param number 行号（1 基，对应 Excel 的行号）
     * @param cells  列索引（0 = A 列）→ 单元格文本。
     *               **空单元格不会出现在 map 里**，因此必须按列索引取，
     *               不能按位置取。
     */
    data class Row(val number: Int, val cells: Map<Int, String>)

    /** sheetData 所在条目的前缀 */
    private const val SHEET_PREFIX = "xl/worksheets/"
    private const val SHEET_SUFFIX = ".xml"
    private const val SHARED_STRINGS_ENTRY = "xl/sharedStrings.xml"

    /**
     * 读取包含 [headerMarker] 的第一个工作表。
     *
     * 分两遍扫描：`sharedStrings.xml` 在真实文件里常排在 sheet 之后，
     * 单遍流式解析拿不到字符串表。
     *
     * @param headerMarker 用于挑选工作表、并作为解析起点标记的表头文字
     */
    fun readFirstSheet(bytes: ByteArray, headerMarker: String = "交易时间"): List<Row> {
        val shared = readSharedStrings(bytes)
        val entries = worksheetEntries(bytes)
        if (entries.isEmpty()) return emptyList()

        for (entry in entries) {
            val rows = readRows(bytes, entry, shared)
            if (rows.any { row -> row.cells.values.any { it.trim() == headerMarker } }) {
                return rows
            }
        }
        // 没有任何工作表含表头标记 → 返回第一个表的内容, 由调用方报「未找到表头」
        return readRows(bytes, entries.first(), shared)
    }

    /** 列出 zip 内所有工作表条目名 */
    private fun worksheetEntries(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory &&
                    name.startsWith(SHEET_PREFIX) &&
                    name.endsWith(SHEET_SUFFIX)
                ) {
                    result += name
                }
                entry = zis.nextEntry
            }
        }
        return result.sorted()   // sheet1, sheet2, ... 顺序稳定
    }

    /** 第一遍：读共享字符串表 */
    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val handler = SharedStringsHandler()
        if (!parseEntry(bytes, SHARED_STRINGS_ENTRY, handler)) return emptyList()
        return handler.strings
    }

    /** 第二遍：读某个工作表 */
    private fun readRows(bytes: ByteArray, entryName: String, shared: List<String>): List<Row> {
        val handler = SheetHandler(shared)
        parseEntry(bytes, entryName, handler)
        return handler.rows
    }

    /** 把指定条目的内容喂给 SAX 解析器 */
    private fun parseEntry(bytes: ByteArray, entryName: String, handler: DefaultHandler): Boolean {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == entryName) {
                    // 先读完整条到内存, 避免 SAX 与 ZipInputStream 的读取位置互相干扰。
                    // ZipInputStream 到达条目末尾时 read() 返回 -1, 因此 readBytes() 恰好读一个条目。
                    val entryBytes = zis.readBytes()
                    val parser = SAXParserFactory.newInstance()
                        .apply { isNamespaceAware = false }
                        .newSAXParser()
                    parser.parse(InputSource(ByteArrayInputStream(entryBytes)), handler)
                    return true
                }
                entry = zis.nextEntry
            }
        }
        return false
    }

    /** 解析共享字符串表：`<sst><si><t>..</t></si>...` */
    private class SharedStringsHandler : DefaultHandler() {
        val strings = mutableListOf<String>()
        private val buffer = StringBuilder()
        private var inText = false
        private var inPhonetic = false

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes?) {
            when (qName) {
                "si" -> buffer.setLength(0)
                "rPh" -> inPhonetic = true          // 注音, 不属于文本内容
                "t" -> if (!inPhonetic) inText = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText && !inPhonetic) buffer.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "t" -> inText = false
                "rPh" -> inPhonetic = false
                // 一个 <si> 可能由多个 <t> 段组成(富文本), 这里已拼接完成
                "si" -> strings += buffer.toString()
            }
        }
    }

    /** 解析工作表：`<sheetData><row r="19"><c r="A19" t="s"><v>26</v></c>...` */
    private class SheetHandler(private val shared: List<String>) : DefaultHandler() {
        val rows = mutableListOf<Row>()
        private var rowNumber = 0
        private var cells = LinkedHashMap<Int, String>()

        private var columnIndex = -1
        private var cellType: String? = null
        private var inValue = false
        private val valueBuffer = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes?) {
            when (qName) {
                "row" -> {
                    rowNumber = attrs?.getValue("r")?.toIntOrNull() ?: (rowNumber + 1)
                    cells = LinkedHashMap()
                }
                "c" -> {
                    columnIndex = columnIndexOf(attrs?.getValue("r"))
                    cellType = attrs?.getValue("t")
                    valueBuffer.setLength(0)
                }
                // <v> 是值; <t> 出现在内联字符串 <is><t> 里, 两者都当文本收集
                "v", "t" -> inValue = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inValue) valueBuffer.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "v", "t" -> inValue = false
                "c" -> {
                    if (columnIndex >= 0) {
                        cells[columnIndex] = resolveText()
                    }
                }
                "row" -> rows += Row(rowNumber, cells)
            }
        }

        /** `t="s"` 表示 <v> 是共享字符串下标; 其余(数字/内联字符串)直接取原文 */
        private fun resolveText(): String {
            val raw = valueBuffer.toString()
            if (cellType != "s") return raw
            val index = raw.trim().toIntOrNull() ?: return ""
            return shared.getOrNull(index) ?: ""
        }

        /** 单元格引用 "A19" / "AB12" → 0 基列索引; 无法识别时返回 -1 */
        private fun columnIndexOf(ref: String?): Int {
            if (ref.isNullOrEmpty()) return -1
            var index = 0
            var i = 0
            while (i < ref.length && ref[i].isLetter()) {
                index = index * 26 + (ref[i].uppercaseChar() - 'A' + 1)
                i++
            }
            return if (i == 0) -1 else index - 1
        }
    }
}