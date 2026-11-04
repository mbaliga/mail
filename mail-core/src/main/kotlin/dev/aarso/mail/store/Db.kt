package dev.aarso.mail.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * A thin, explicit layer over the androidx.sqlite driver API.
 *
 * Two connections in WAL mode — one writer, one reader — each confined to its own thread, so a
 * long sync transaction never blocks the list the person is scrolling. Every write transaction
 * names the tables it touched and the change is broadcast on [changes]; [observe] turns any query
 * into a Flow that re-runs when one of its tables changes. That is the whole of the reactivity
 * Room would otherwise provide, without Room's FTS4-only ceiling.
 */
class Db(driver: SQLiteDriver, path: String) : AutoCloseable {

    private val writerThread = Executors.newSingleThreadExecutor { r -> Thread(r, "mail-db-writer").apply { isDaemon = true } }
    private val readerThread = Executors.newSingleThreadExecutor { r -> Thread(r, "mail-db-reader").apply { isDaemon = true } }
    private val writerDispatcher = writerThread.asCoroutineDispatcher()
    private val readerDispatcher = readerThread.asCoroutineDispatcher()
    private val writeMutex = Mutex()
    private val readMutex = Mutex()

    private val writer: SQLiteConnection
    private val reader: SQLiteConnection

    private val _changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Set<String>> = _changes.asSharedFlow()

    init {
        writer = driver.open(path)
        writer.execSQL("PRAGMA journal_mode=WAL")
        writer.execSQL("PRAGMA synchronous=NORMAL")
        writer.execSQL("PRAGMA foreign_keys=ON")
        writer.execSQL("PRAGMA busy_timeout=5000")
        Schema.migrate(Conn(writer))
        reader = driver.open(path)
        reader.execSQL("PRAGMA foreign_keys=ON")
        reader.execSQL("PRAGMA busy_timeout=5000")
        reader.execSQL("PRAGMA query_only=1")
    }

    suspend fun <T> read(block: Conn.() -> T): T = readMutex.withLock {
        withContext(readerDispatcher) { Conn(reader).block() }
    }

    /** A write transaction. [tables] are announced on [changes] after a successful commit. */
    suspend fun <T> write(tables: Set<String>, block: Conn.() -> T): T {
        val result = writeMutex.withLock {
            withContext(writerDispatcher) {
                val c = Conn(writer)
                writer.execSQL("BEGIN IMMEDIATE")
                try {
                    val r = c.block()
                    writer.execSQL("COMMIT")
                    r
                } catch (t: Throwable) {
                    runCatching { writer.execSQL("ROLLBACK") }
                    throw t
                }
            }
        }
        if (tables.isNotEmpty()) _changes.tryEmit(tables)
        return result
    }

    /** Re-run [query] now and whenever any of [tables] changes. Conflated: a burst of writes is one re-read. */
    fun <T> observe(tables: Set<String>, query: suspend () -> T): Flow<T> =
        changes.filter { changed -> changed.any { it in tables } }
            .map { }
            .onStart { emit(Unit) }
            .conflate()
            .map { query() }

    override fun close() {
        runCatching { reader.close() }
        runCatching { writer.close() }
        writerThread.shutdown()
        readerThread.shutdown()
    }
}

/** One connection's statements, with positional binding of plain Kotlin values. */
class Conn(private val c: SQLiteConnection) {

    fun exec(sql: String, vararg args: Any?) {
        c.prepare(sql).use { s ->
            bind(s, args)
            while (s.step()) { /* drain */ }
        }
    }

    fun execScript(sql: String) {
        for (stmt in splitStatements(sql)) c.execSQL(stmt)
    }

    /** Insert and return the new rowid. */
    fun insert(sql: String, vararg args: Any?): Long {
        exec(sql, *args)
        return lastId()
    }

    fun lastId(): Long = scalarLong("SELECT last_insert_rowid()") ?: 0

    fun changes(): Int = scalarLong("SELECT changes()")?.toInt() ?: 0

    fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> = c.prepare(sql).use { s ->
        bind(s, args)
        val row = Row(s)
        val out = ArrayList<T>()
        while (s.step()) out += map(row)
        out
    }

    fun <T> queryOne(sql: String, vararg args: Any?, map: (Row) -> T): T? = c.prepare(sql).use { s ->
        bind(s, args)
        if (s.step()) map(Row(s)) else null
    }

    /** Streaming iteration for large result sets: rows are handed out one at a time, never collected. */
    fun forEach(sql: String, vararg args: Any?, each: (Row) -> Boolean) {
        c.prepare(sql).use { s ->
            bind(s, args)
            val row = Row(s)
            while (s.step()) if (!each(row)) break
        }
    }

    fun scalarLong(sql: String, vararg args: Any?): Long? = queryOne(sql, *args) { if (it.isNull(0)) null else it.long(0) }
    fun scalarText(sql: String, vararg args: Any?): String? = queryOne(sql, *args) { it.textOrNull(0) }

    private fun bind(s: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> s.bindNull(idx)
                is Long -> s.bindLong(idx, a)
                is Int -> s.bindLong(idx, a.toLong())
                is Short -> s.bindLong(idx, a.toLong())
                is Boolean -> s.bindLong(idx, if (a) 1 else 0)
                is Double -> s.bindDouble(idx, a)
                is Float -> s.bindDouble(idx, a.toDouble())
                is String -> s.bindText(idx, a)
                is ByteArray -> s.bindBlob(idx, a)
                is Enum<*> -> s.bindText(idx, a.name)
                else -> s.bindText(idx, a.toString())
            }
        }
    }

    /**
     * Split a schema script on statement-ending semicolons. The schema deliberately has no triggers
     * (the FTS index is maintained in code, where what goes into it is a decision, not a side effect),
     * so a statement ends exactly where a line ends in `;`.
     */
    private fun splitStatements(sql: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (line in sql.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("--")) continue
            sb.append(line).append('\n')
            if (t.endsWith(";")) {
                out += sb.toString().trim().removeSuffix(";")
                sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) out += sb.toString().trim().removeSuffix(";")
        return out
    }
}

/** A cursor row. Column access is 0-based, by index or by name. */
class Row(private val s: SQLiteStatement) {
    private val names: Map<String, Int> by lazy { (0 until s.getColumnCount()).associateBy { s.getColumnName(it) } }

    fun idx(name: String): Int = names[name] ?: error("No column $name")
    fun isNull(i: Int) = s.isNull(i)
    fun long(i: Int): Long = s.getLong(i)
    fun int(i: Int): Int = s.getLong(i).toInt()
    fun double(i: Int): Double = s.getDouble(i)
    fun bool(i: Int): Boolean = s.getLong(i) != 0L
    fun text(i: Int): String = if (s.isNull(i)) "" else s.getText(i)
    fun textOrNull(i: Int): String? = if (s.isNull(i)) null else s.getText(i)
    fun blob(i: Int): ByteArray? = if (s.isNull(i)) null else s.getBlob(i)

    fun long(n: String) = long(idx(n))
    fun int(n: String) = int(idx(n))
    fun double(n: String) = double(idx(n))
    fun bool(n: String) = bool(idx(n))
    fun text(n: String) = text(idx(n))
    fun textOrNull(n: String) = textOrNull(idx(n))
    fun blob(n: String) = blob(idx(n))
}
