package app.phonemcp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Decision { AUTO, APPROVED, DENIED, TIMEOUT, BLOCKED }

data class AuditEntry(
    val id: Long,
    val ts: Long,
    val tool: String,
    val summary: String,
    val decision: Decision,
    val status: String,
    val args: String = ""
) {
    val ok get() = status == "ok"
}

class AuditLog(context: Context) : SQLiteOpenHelper(context.applicationContext, "audit.db", null, 1) {
    private val _changes = MutableStateFlow(0L)
    /** Ticks whenever rows change so the UI can reload. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE audit (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, tool TEXT NOT NULL, " +
                "summary TEXT NOT NULL, args TEXT NOT NULL, decision TEXT NOT NULL, status TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX audit_ts ON audit(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun add(tool: String, summary: String, args: String, decision: Decision, status: String) {
        try {
            val v = ContentValues().apply {
                put("ts", System.currentTimeMillis())
                put("tool", tool)
                put("summary", summary.take(300))
                put("args", args.take(2000))
                put("decision", decision.name)
                put("status", status.take(60))
            }
            writableDatabase.insert("audit", null, v)
            _changes.value = _changes.value + 1
        } catch (_: Exception) {
            // Logging must never break a tool call.
        }
    }

    @Synchronized
    fun recent(limit: Int): List<AuditEntry> = try {
        readableDatabase.rawQuery(
            "SELECT id, ts, tool, summary, decision, status, args FROM audit ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        AuditEntry(
                            c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                            runCatching { Decision.valueOf(c.getString(4)) }.getOrDefault(Decision.AUTO), c.getString(5), c.getString(6)
                        )
                    )
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun purgeOlderThan(days: Int) {
        try {
            val cutoff = System.currentTimeMillis() - days * 86_400_000L
            writableDatabase.delete("audit", "ts < ?", arrayOf(cutoff.toString()))
            _changes.value = _changes.value + 1
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear() {
        try {
            writableDatabase.delete("audit", null, null)
            _changes.value = _changes.value + 1
        } catch (_: Exception) {
        }
    }

    fun exportCsv(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("time,tool,summary,decision,status\n")
        for (e in recent(5000).reversed()) {
            sb.append(fmt.format(Date(e.ts))).append(',')
                .append(csv(e.tool)).append(',').append(csv(e.summary)).append(',')
                .append(e.decision.name).append(',').append(csv(e.status)).append('\n')
        }
        return sb.toString()
    }

    /** Quoted CSV cell; a leading = + - @ tab or CR gets a single quote so spreadsheets never evaluate it. */
    private fun csv(s: String): String {
        val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return "\"" + safe.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\""
    }
}
