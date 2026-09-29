package com.example.myapplication.ui.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.DatabaseHelper
import com.example.myapplication.importer.BillImportException
import com.example.myapplication.importer.BillImporter
import com.example.myapplication.importer.BillType
import com.example.myapplication.importer.ImportError
import com.example.myapplication.importer.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class ImportUiState(
    /** BottomSheet 是否可见 */
    val sheetVisible: Boolean = false,
    /**
     * 用户点选的账单来源。
     *
     * 非空表示「已请求打开文件选择器」而选择器还没返回 —— ProfileScreen 挂的
     * LaunchedEffect 观察它来 launch 选择器；回调返回后由 [ImportViewModel.onFilePicked]
     * / [ImportViewModel.cancelPick] 清空，保证下一次点选能重新触发。
     */
    val pendingType: BillType? = null,
    /** 正在读取 / 解析 / 入库 */
    val isWorking: Boolean = false,
    /** 导入成功的结果，非空时弹结果弹窗 */
    val result: ImportResult? = null,
    /** 导入失败的原因，非空时弹错误弹窗 */
    val error: ImportError? = null
)

/**
 * 账单导入流程编排：读取文件 → 解析 → 批量入库。
 *
 * 文件读取放在这里（而不是 importer 包）是因为它要用到 Android 的 [Uri] /
 * ContentResolver，而 importer 包必须保持纯 JVM 以便离线单元测试。
 */
class ImportViewModel(
    private val db: DatabaseHelper,
    /** 一律传 applicationContext：ViewModel 生命周期长于 Activity */
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    // ==================== BottomSheet ====================

    fun showSheet() = _uiState.update { it.copy(sheetVisible = true) }

    fun dismissSheet() = _uiState.update { it.copy(sheetVisible = false) }

    // ==================== 文件选择 ====================

    /**
     * 用户在 sheet 里选了一个来源 → 请求打开文件选择器。
     *
     * sheet 保持挂载：选择器关掉后回到 App 时，它会原地切成进度指示，
     * 用户不会看到「选项闪一下再消失」。
     */
    fun preparePick(type: BillType) = _uiState.update { it.copy(pendingType = type) }

    /** 用户取消了文件选择器：清掉待选来源，sheet 留在原地让用户可以改选另一家 */
    fun cancelPick() = _uiState.update { it.copy(pendingType = null) }

    /** 文件选择器返回（uri 为 null 表示用户取消） */
    fun onFilePicked(uri: Uri?) {
        val type = _uiState.value.pendingType
        if (uri == null || type == null) {
            cancelPick()
            return
        }
        _uiState.update { it.copy(pendingType = null, isWorking = true) }
        viewModelScope.launch {
            try {
                val result = readAndImport(type, uri)
                // 收起 sheet 再弹结果，避免两层浮层叠在一起
                _uiState.update {
                    it.copy(sheetVisible = false, isWorking = false, result = result)
                }
            } catch (e: CancellationException) {
                // ViewModel 被清理时不要把取消当成导入失败
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        sheetVisible = false,
                        isWorking = false,
                        error = (e as? BillImportException)?.error ?: ImportError.ReadFailed
                    )
                }
            }
        }
    }

    // ==================== 弹窗 ====================

    fun dismissResult() = _uiState.update { it.copy(result = null) }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    // ==================== 实际流程 ====================

    private suspend fun readAndImport(type: BillType, uri: Uri): ImportResult {
        val bytes = readBytes(uri)

        val outcome = try {
            BillImporter.parse(type, bytes)
        } catch (e: BillImportException) {
            throw e
        } catch (e: Exception) {
            // 解析器内部意外崩溃（损坏的 zip / XML 等）统一归为「读取失败」
            throw BillImportException(ImportError.ReadFailed)
        }

        if (outcome.records.isEmpty()) throw BillImportException(ImportError.NoRecords)

        // 单事务批量写入，重复行按 (source, order_no) 唯一索引跳过
        val inserted = db.insertAll(outcome.records)
        return ImportResult(
            inserted = inserted,
            duplicate = outcome.records.size - inserted,
            neutralSkipped = outcome.neutralSkipped,
            invalidSkipped = outcome.invalidSkipped
        )
    }

    /**
     * 读取文件内容。
     *
     * 分块读并在超过 [MAX_FILE_BYTES] 时立刻中断 —— 直接对未知的 ContentResolver
     * 流调用 readBytes() 有可能把超大文件整个读进内存。
     */
    private suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BillImportException(ImportError.ReadFailed)

        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_FILE_BYTES) {
                    throw BillImportException(ImportError.FileTooLarge)
                }
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) throw BillImportException(ImportError.EmptyFile)
            bytes
        }
    }

    private companion object {
        /** 账单文件通常只有几十 KB，32MB 足以覆盖任何正常的导出文件 */
        const val MAX_FILE_BYTES = 32 * 1024 * 1024
    }
}