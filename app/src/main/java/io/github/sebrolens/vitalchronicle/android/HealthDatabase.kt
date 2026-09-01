package io.github.sebrolens.vitalchronicle.android

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

class HealthDatabase(context: Context) : SQLiteOpenHelper(context, "health_data.sqlite3", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE records (
              data_type TEXT NOT NULL,
              record_id TEXT NOT NULL,
              record_kind TEXT NOT NULL DEFAULT 'data_point',
              start_time TEXT,
              end_time TEXT,
              source TEXT NOT NULL DEFAULT '',
              payload TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              PRIMARY KEY (data_type, record_id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_records_type_time ON records(data_type, start_time)")
        db.execSQL("CREATE TABLE resources(resource_type TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE sync_log(data_type TEXT PRIMARY KEY, last_sync TEXT NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE sync_ranges(data_type TEXT NOT NULL, start_date TEXT NOT NULL, end_date TEXT NOT NULL, PRIMARY KEY(data_type,start_date,end_date))")
        db.execSQL("CREATE TABLE app_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun upsertNormalized(normalizedJson: String): Int {
        val a = JSONArray(normalizedJson)
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val values = ContentValues().apply {
                    put("data_type", o.getString("data_type"))
                    put("record_id", o.getString("record_id"))
                    put("record_kind", o.getString("record_kind"))
                    o.optNullableString("start_time")?.let { put("start_time", it) } ?: putNull("start_time")
                    o.optNullableString("end_time")?.let { put("end_time", it) } ?: putNull("end_time")
                    put("source", o.optString("source", ""))
                    put("payload", o.getJSONObject("payload").toString())
                    put("updated_at", OffsetDateTime.now().toString())
                }
                db.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /**
     * Enforce the bounded local archive. Returns the number of deleted records.
     * When compact=true, SQLite is vacuumed only if deletion freed a meaningful
     * amount of space, so an existing oversized database can actually shrink.
     */
    @Synchronized
    fun pruneRetention(today: LocalDate = LocalDate.now(), compact: Boolean = false): Int {
        val db = writableDatabase
        var removed = 0
        db.beginTransaction()
        try {
            DataRetention.HIGH_VOLUME_CARDIAC_TYPES.forEach { dataType ->
                removed += db.delete(
                    "records",
                    "data_type=? AND substr(COALESCE(start_time,end_time,updated_at),1,10) < ?",
                    arrayOf(dataType, DataRetention.cutoffDate(dataType, today).toString()),
                )
            }

            val highVolume = DataRetention.HIGH_VOLUME_CARDIAC_TYPES.toList()
            val placeholders = highVolume.joinToString(",") { "?" }
            val generalCutoff = today.minusDays((DataRetention.GENERAL_DAYS - 1).toLong()).toString()
            removed += db.delete(
                "records",
                "data_type NOT IN ($placeholders) AND substr(COALESCE(start_time,end_time,updated_at),1,10) < ?",
                (highVolume + generalCutoff).toTypedArray(),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (compact && removed > 0 && shouldVacuum(db)) {
            runCatching { db.execSQL("VACUUM") }
        }
        return removed
    }

    private fun pragmaLong(db: SQLiteDatabase, pragma: String): Long =
        db.rawQuery("PRAGMA $pragma", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun shouldVacuum(db: SQLiteDatabase): Boolean {
        val pageCount = pragmaLong(db, "page_count")
        val freePages = pragmaLong(db, "freelist_count")
        val pageSize = pragmaLong(db, "page_size")
        if (pageCount <= 0 || pageSize <= 0) return false
        val freeBytes = freePages * pageSize
        return freeBytes >= 16L * 1024L * 1024L && freePages * 5L >= pageCount
    }

    fun counts(): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT data_type, COUNT(*) FROM records GROUP BY data_type ORDER BY data_type", null).use { c ->
            while (c.moveToNext()) result[c.getString(0)] = c.getInt(1)
        }
        return result
    }

    fun latestStart(dataType: String): String? = readableDatabase.rawQuery(
        "SELECT MAX(COALESCE(start_time,end_time)) FROM records WHERE data_type=?", arrayOf(dataType)
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

    fun setSyncStatus(dataType: String, status: String, message: String = "") {
        val v = ContentValues().apply {
            put("data_type", dataType); put("last_sync", OffsetDateTime.now().toString()); put("status", status); put("message", message)
        }
        writableDatabase.insertWithOnConflict("sync_log", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Return only the records required by build_daily_progress_snapshot.
     *
     * The previous implementation sent up to 150,000 complete records to Python
     * every time the dashboard refreshed. Dense cardiac payloads could therefore
     * occupy the Android heap several times over (SQLite -> JSONObject -> String ->
     * Python json.loads) even though most of those records were irrelevant to the
     * overview. This query keeps the desktop/core semantics while bounding memory.
     */
    fun dashboardRecordsJson(referenceDay: LocalDate): String {
        val regularTypes = listOf(
            "steps",
            "daily-resting-heart-rate",
            "sleep",
            "daily-heart-rate-variability",
            "daily-oxygen-saturation",
            "daily-respiratory-rate",
            "daily-sleep-temperature-derivations",
            "active-zone-minutes",
        )
        val from = referenceDay.minusDays(8).toString()
        val until = referenceDay.plusDays(1).toString()
        val out = StringBuilder(64 * 1024).append('[')
        var first = true

        regularTypes.forEach { dataType ->
            first = appendQueryRecords(
                out,
                "$RECORD_SELECT WHERE data_type=? AND (COALESCE(start_time,end_time) IS NULL OR (COALESCE(start_time,end_time)>=? AND COALESCE(start_time,end_time)<?)) ORDER BY COALESCE(start_time,end_time,updated_at) DESC LIMIT 30000",
                arrayOf(dataType, from, until),
                first,
            )
        }

        // The shared desktop dashboard displays only today's dense heart-rate curve.
        // Never materialise the remaining 15-day raw cardiac archive for the home page.
        first = appendQueryRecords(
            out,
            "$RECORD_SELECT WHERE data_type=? AND COALESCE(start_time,end_time)>=? AND COALESCE(start_time,end_time)<? ORDER BY COALESCE(start_time,end_time,updated_at) DESC LIMIT 30000",
            arrayOf("heart-rate", referenceDay.toString(), until),
            first,
        )

        // Weight intentionally shows the latest available measurement even when it
        // is older than the dashboard's normal nine-day input window. Include the
        // preceding eight days around that measurement so its baseline still works.
        val latestWeightDay = latestStart("weight")?.take(10)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        if (latestWeightDay != null) {
            first = appendQueryRecords(
                out,
                "$RECORD_SELECT WHERE data_type=? AND (COALESCE(start_time,end_time) IS NULL OR (COALESCE(start_time,end_time)>=? AND COALESCE(start_time,end_time)<?)) ORDER BY COALESCE(start_time,end_time,updated_at) DESC LIMIT 30000",
                arrayOf("weight", latestWeightDay.minusDays(8).toString(), latestWeightDay.plusDays(1).toString()),
                first,
            )
        }

        out.append(']')
        return out.toString()
    }

    /**
     * Per-type bounded input for deterministic AI. The shared core itself uses a
     * 30k record limit per category, so preparing the Android input the same way
     * avoids one dense stream crowding every other health category out of a global
     * SQL LIMIT.
     */
    fun analysisRecordsJson(
        dataTypes: List<String>,
        start: String,
        end: String,
        perTypeLimit: Int = 30_000,
    ): String {
        val safeLimit = perTypeLimit.coerceIn(1, 30_000)
        val out = StringBuilder(128 * 1024).append('[')
        var first = true
        dataTypes.distinct().forEach { dataType ->
            first = appendQueryRecords(
                out,
                "$RECORD_SELECT WHERE data_type=? AND (COALESCE(start_time,end_time) IS NULL OR (COALESCE(start_time,end_time)>=? AND COALESCE(start_time,end_time)<?)) ORDER BY COALESCE(start_time,end_time,updated_at) DESC LIMIT $safeLimit",
                arrayOf(dataType, start, end),
                first,
            )
        }
        out.append(']')
        return out.toString()
    }

    fun allRecordsJson(start: String? = null, end: String? = null, limit: Int = 150_000): String {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (start != null) { clauses += "(start_time IS NULL OR start_time >= ?)"; args += start }
        if (end != null) { clauses += "(start_time IS NULL OR start_time < ?)"; args += end }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val safeLimit = limit.coerceIn(1, 150_000)
        val out = StringBuilder(128 * 1024).append('[')
        appendQueryRecords(
            out,
            "$RECORD_SELECT $where ORDER BY COALESCE(start_time,end_time) ASC LIMIT $safeLimit",
            args.toTypedArray(),
            true,
        )
        out.append(']')
        return out.toString()
    }

    /** Append DB rows directly as JSON without reparsing every payload in Kotlin. */
    private fun appendQueryRecords(
        out: StringBuilder,
        sql: String,
        args: Array<String>,
        initiallyFirst: Boolean,
    ): Boolean {
        var first = initiallyFirst
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                if (!first) out.append(',')
                appendRecord(out, c)
                first = false
            }
        }
        return first
    }

    private fun appendRecord(out: StringBuilder, c: Cursor) {
        out.append('{')
        out.append("\"data_type\":").append(JSONObject.quote(c.getString(0))).append(',')
        out.append("\"record_id\":").append(JSONObject.quote(c.getString(1))).append(',')
        out.append("\"record_kind\":").append(JSONObject.quote(c.getString(2))).append(',')
        out.append("\"start_time\":")
        if (c.isNull(3)) out.append("null") else out.append(JSONObject.quote(c.getString(3)))
        out.append(',').append("\"end_time\":")
        if (c.isNull(4)) out.append("null") else out.append(JSONObject.quote(c.getString(4)))
        out.append(',').append("\"source\":").append(JSONObject.quote(c.getString(5))).append(',')
        out.append("\"payload\":").append(c.getString(6))
        out.append('}')
    }

    fun recentRecords(dataType: String, limit: Int = 50): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT payload FROM records WHERE data_type=? ORDER BY COALESCE(start_time,end_time,updated_at) DESC LIMIT ?",
            arrayOf(dataType, limit.toString())
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun clearAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("records", null, null)
            writableDatabase.delete("resources", null, null)
            writableDatabase.delete("sync_log", null, null)
            writableDatabase.delete("sync_ranges", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    companion object {
        private const val RECORD_SELECT =
            "SELECT data_type,record_id,record_kind,start_time,end_time,source,payload FROM records"
    }
}
