package io.github.sebrolens.vitalchronicle.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
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

    fun allRecordsJson(start: String? = null, end: String? = null, limit: Int = 150_000): String {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (start != null) { clauses += "(start_time IS NULL OR start_time >= ?)"; args += start }
        if (end != null) { clauses += "(start_time IS NULL OR start_time < ?)"; args += end }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val sql = "SELECT data_type,record_id,record_kind,start_time,end_time,source,payload FROM records $where ORDER BY COALESCE(start_time,end_time) ASC LIMIT $limit"
        val out = JSONArray()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.put(JSONObject().apply {
                    put("data_type", c.getString(0)); put("record_id", c.getString(1)); put("record_kind", c.getString(2))
                    put("start_time", if (c.isNull(3)) JSONObject.NULL else c.getString(3))
                    put("end_time", if (c.isNull(4)) JSONObject.NULL else c.getString(4))
                    put("source", c.getString(5)); put("payload", JSONObject(c.getString(6)))
                })
            }
        }
        return out.toString()
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
}
