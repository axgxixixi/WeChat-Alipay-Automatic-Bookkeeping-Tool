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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 增量迁移：保留用户数据, 每版用 when 子句独立处理
        when (oldVersion) {
            1 -> {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON $TABLE_NAME(timestamp)")
            }
        }
    }

    suspend fun insert(transaction: Transaction): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = android.content.ContentValues().apply {
            put("source", transaction.source)
            put("type", transaction.type)
            put("amount", transaction.amount)
            put("description", transaction.description)
            put("raw_text", transaction.rawText)
            put("timestamp", transaction.timestamp)
            put("notification_id", transaction.notificationId)
            put("note", transaction.note)
        }
        val id = db.insert(TABLE_NAME, null, values)
        if (id > 0) {
            changeSignal.tryEmit(Unit) // 通知 UI 刷新
        }
        id
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
        private const val DATABASE_VERSION = 2
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
                note TEXT NOT NULL DEFAULT ''
            )
        """

        /** timestamp 索引：加速 ORDER BY / 范围查询, 避免每次全表扫描 */
        private const val CREATE_INDEX_TIMESTAMP_SQL =
            "CREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON $TABLE_NAME(timestamp)"

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
                note = cursor.getString(cursor.getColumnIndexOrThrow("note"))
            )
        }
    }
}