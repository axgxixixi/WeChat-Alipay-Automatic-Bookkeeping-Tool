package com.example.myapplication.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.myapplication.model.MonthlySummary
import com.example.myapplication.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseHelper(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    /** 数据变更信号：每次数据库写入成功后发射，UI 层收到后自动刷新。 */
    private val changeSignal = MutableSharedFlow<Unit>(replay = 1)

    init {
        // 发射初始信号，确保首次查询立即执行
        changeSignal.tryEmit(Unit)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_SQL)
        db.execSQL(CREATE_INDEX_TIMESTAMP_SQL)
        db.execSQL(CREATE_INDEX_ORDER_NO_SQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 用 oldVersion 区间判断而不是 when(oldVersion): 跨版本升级(如 1 -> 3)时
        // when 只命中一个分支, 会漏掉中间版本的迁移, 导致 order_no 列不存在。
        if (oldVersion < 2) {
            db.execSQL(CREATE_INDEX_TIMESTAMP_SQL)   // 幂等: IF NOT EXISTS
        }
        if (oldVersion < 3) {
            // SQLite 的 ALTER TABLE 不支持加 UNIQUE 列, 唯一性用独立索引实现
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN order_no TEXT")
            db.execSQL(CREATE_INDEX_ORDER_NO_SQL)
        }
    }

    suspend fun insert(transaction: Transaction): Long = withContext(Dispatchers.IO) {
        val id = writableDatabase.insert(TABLE_NAME, null, transaction.toContentValues())
        if (id > 0) {
            changeSignal.tryEmit(Unit) // 通知 UI 刷新
        }
        id
    }

    /**
     * 批量写入: 单事务 + 只发一次变更信号。
     *
     * 导入账单时逐条调用 [insert] 会触发 N 次 UI 刷新(导入 166 行 = 166 次重组),
     * 因此批量场景统一走这里。
     *
     * (source, order_no) 冲突的行由 [SQLiteDatabase.CONFLICT_IGNORE] 跳过, 实现去重。
     *
     * @return 实际新增的行数; 调用方用 list.size - 返回值 得到跳过(重复)的数量。
     */
    suspend fun insertAll(transactions: List<Transaction>): Int = withContext(Dispatchers.IO) {
        if (transactions.isEmpty()) return@withContext 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            transactions.forEach { transaction ->
                val id = db.insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    transaction.toContentValues(),
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                if (id > 0) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changeSignal.tryEmit(Unit) // 只发一次
        inserted
    }

    fun getAllTransactions(): Flow<List<Transaction>> = changeSignal
        .onStart { emit(Unit) }
        .flatMapLatest {
            flow {
                val db = readableDatabase
                val cursor = db.query(
                    TABLE_NAME, null, null, null, null, null,
                    "timestamp DESC"
                )
                val transactions = cursor.use { c ->
                    val list = mutableListOf<Transaction>()
                    while (c.moveToNext()) {
                        list.add(cursorToTransaction(c))
                    }
                    list
                }
                emit(transactions)
            }.flowOn(Dispatchers.IO)
        }

    fun getRecentTransactions(limit: Int): Flow<List<Transaction>> = changeSignal
        .onStart { emit(Unit) }
        .flatMapLatest {
            flow {
                val db = readableDatabase
                val cursor = db.query(
                    TABLE_NAME, null, null, null, null, null,
                    "timestamp DESC", limit.toString()
                )
                val transactions = cursor.use { c ->
                    val list = mutableListOf<Transaction>()
                    while (c.moveToNext()) {
                        list.add(cursorToTransaction(c))
                    }
                    list
                }
                emit(transactions)
            }.flowOn(Dispatchers.IO)
        }

    fun getMonthlySummary(startTime: Long, endTime: Long): Flow<MonthlySummary> = changeSignal
        .onStart { emit(Unit) }
        .flatMapLatest {
            flow {
                val db = readableDatabase
                val cursor = db.rawQuery(
                    """SELECT
                        COALESCE(SUM(CASE WHEN type = '${Transaction.TYPE_INCOME}' THEN amount ELSE 0 END), 0.0),
                        COALESCE(SUM(CASE WHEN type = '${Transaction.TYPE_EXPENSE}' THEN amount ELSE 0 END), 0.0)
                    FROM $TABLE_NAME
                    WHERE timestamp BETWEEN ? AND ?""",
                    arrayOf(startTime.toString(), endTime.toString())
                )
                val summary = cursor.use { c ->
                    if (c.moveToFirst()) {
                        MonthlySummary(
                            totalIncome = c.getDouble(0),
                            totalExpense = c.getDouble(1)
                        )
                    } else {
                        MonthlySummary()
                    }
                }
                emit(summary)
            }.flowOn(Dispatchers.IO)
        }

    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_NAME, "id = ?", arrayOf(id.toString()))
        changeSignal.tryEmit(Unit) // 删除后也通知刷新
    }

    companion object {
        private const val DATABASE_NAME = "accounting.db"
        private const val DATABASE_VERSION = 3
        private const val TABLE_NAME = "transactions"

        private const val CREATE_TABLE_SQL = """
            CREATE TABLE $TABLE_NAME (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source TEXT NOT NULL,
                type TEXT NOT NULL,
                amount REAL NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                raw_text TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL,
                notification_id INTEGER,
                note TEXT NOT NULL DEFAULT '',
                order_no TEXT
            )
        """

        /** timestamp 索引：加速 ORDER BY / 范围查询, 避免每次全表扫描 */
        private const val CREATE_INDEX_TIMESTAMP_SQL =
            "CREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON $TABLE_NAME(timestamp)"

        /**
         * 账单去重键 (source, order_no)。
         *
         * 用复合键而非 order_no 单列: 隔离微信与支付宝的单号空间, 避免跨平台碰撞误删。
         * SQLite 的 UNIQUE 索引允许多个 NULL, 因此通知监听写入的行(order_no = NULL)
         * 和全部历史数据都不受影响, 无需回填。
         */
        private const val CREATE_INDEX_ORDER_NO_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_source_order ON $TABLE_NAME(source, order_no)"

        @Volatile
        private var INSTANCE: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun cursorToTransaction(cursor: android.database.Cursor): Transaction {
            return Transaction(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
                type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                amount = cursor.getDouble(cursor.getColumnIndexOrThrow("amount")),
                description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                rawText = cursor.getString(cursor.getColumnIndexOrThrow("raw_text")),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                notificationId = if (cursor.isNull(cursor.getColumnIndexOrThrow("notification_id"))) {
                    null
                } else {
                    cursor.getInt(cursor.getColumnIndexOrThrow("notification_id"))
                },
                note = cursor.getString(cursor.getColumnIndexOrThrow("note")),
                orderNo = cursor.getString(cursor.getColumnIndexOrThrow("order_no"))
            )
        }

        /** 三处写入路径(insert / insertAll)共用的字段映射。 */
        private fun Transaction.toContentValues() = android.content.ContentValues().apply {
            put("source", source)
            put("type", type)
            put("amount", amount)
            put("description", description)
            put("raw_text", rawText)
            put("timestamp", timestamp)
            put("notification_id", notificationId)
            put("note", note)
            put("order_no", orderNo)   // null -> SQL NULL
        }
    }
}