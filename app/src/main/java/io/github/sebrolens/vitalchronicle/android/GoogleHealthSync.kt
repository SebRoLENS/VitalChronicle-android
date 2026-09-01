package io.github.sebrolens.vitalchronicle.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class GoogleHealthSync(
    private val database: HealthDatabase,
    private val core: PythonCore,
    private val oauth: OAuthManager,
    private val http: AndroidHttpClient,
) {
    suspend fun sync(historyDays: Int, progress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val specs = core.specs().filter { it.autoSync }
        val today = LocalDate.now()
        var total = 0
        specs.forEachIndexed { index, spec ->
            progress("${index + 1}/${specs.size} · ${spec.label}")
            database.setSyncStatus(spec.key, "running")
            try {
                val latest = database.latestStart(spec.key)?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val initial = today.minusDays((historyDays - 1).toLong())
                val start = if (latest != null && latest.isAfter(initial)) latest.minusDays(1) else initial
                val pages = if (spec.operation == "daily_rollup") rollup(spec, start, today) else list(spec, start, today)
                total += pages
                database.setSyncStatus(spec.key, "ok", "$pages records")
            } catch (e: Exception) {
                database.setSyncStatus(spec.key, "error", e.message.orEmpty())
            }
        }
        progress("Sync complete · $total records processed")
        total
    }

    private suspend fun list(spec: DataTypeSpec, start: LocalDate, end: LocalDate): Int {
        var token: String? = null
        val seen = mutableSetOf<String>()
        var count = 0
        do {
            val url = okhttp3.HttpUrl.Builder().scheme("https").host("health.googleapis.com")
                .addPathSegments("v4/users/me/dataTypes/${spec.key}/dataPoints")
                .addQueryParameter("pageSize", if (spec.key in setOf("exercise", "sleep")) "25" else "10000")
                .apply {
                    dateFilter(spec, start, end)?.let { addQueryParameter("filter", it) }
                    token?.let { addQueryParameter("pageToken", it) }
                }.build()
            val json = requestJson(Request.Builder().url(url).get().build())
            val points = json.optJSONArray("dataPoints") ?: org.json.JSONArray()
            if (points.length() > 0) count += database.upsertNormalized(core.normalize(spec.key, points.toString(), "data_point"))
            token = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            if (token != null && !seen.add(token!!)) error("Google Health repeated a page token for ${spec.label}.")
        } while (token != null)
        return count
    }

    private suspend fun rollup(spec: DataTypeSpec, start: LocalDate, end: LocalDate): Int {
        val maxDays = if (spec.key in setOf("calories-in-heart-rate-zone", "heart-rate", "active-minutes", "total-calories")) 14 else 90
        var cursor = start
        var count = 0
        val zone = ZoneId.systemDefault()
        while (!cursor.isAfter(end)) {
            val exclusive = minOf(end.plusDays(1), cursor.plusDays(maxDays.toLong()))
            var token: String? = null
            val seen = mutableSetOf<String>()
            do {
                val body = JSONObject().apply {
                    put("range", JSONObject().apply {
                        put("startTime", cursor.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        put("endTime", exclusive.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    })
                    put("windowSize", "86400s"); put("pageSize", 10000)
                    token?.let { put("pageToken", it) }
                }
                val url = "https://health.googleapis.com/v4/users/me/dataTypes/${spec.key}/dataPoints:rollUp"
                val req = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
                val json = requestJson(req)
                val points = json.optJSONArray("rollupDataPoints") ?: org.json.JSONArray()
                if (points.length() > 0) count += database.upsertNormalized(core.normalize(spec.key, points.toString(), "daily_rollup"))
                token = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                if (token != null && !seen.add(token!!)) error("Repeated roll-up page for ${spec.label}.")
            } while (token != null)
            cursor = exclusive
        }
        return count
    }

    private fun dateFilter(spec: DataTypeSpec, start: LocalDate, end: LocalDate): String? {
        val field = spec.filterField ?: return null
        val exclusive = end.plusDays(1)
        val lower: String
        val upper: String
        if (spec.recordType == "daily" || field.contains(".civil_")) {
            lower = start.toString(); upper = exclusive.toString()
        } else {
            val zone = ZoneId.systemDefault()
            lower = start.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            upper = exclusive.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
        return if (spec.key == "electrocardiogram") "$field >= \"$lower\"" else "$field >= \"$lower\" AND $field < \"$upper\""
    }

    private suspend fun requestJson(base: Request): JSONObject {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val access = oauth.validAccessToken()
                val req = base.newBuilder().header("Authorization", "Bearer $access").header("Accept", "application/json").build()
                http.execute(req).use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) return if (text.isBlank()) JSONObject() else JSONObject(text)
                    if (response.code in setOf(429, 500, 502, 503, 504) && attempt < 2) delay((1L shl attempt) * 1000L)
                    else error("Google Health ${response.code}: $text")
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 2) delay((1L shl attempt) * 1000L)
            }
        }
        throw last ?: IllegalStateException("Google Health request failed")
    }
}
