package io.github.sebrolens.vitalchronicle.android

import org.json.JSONArray
import org.json.JSONObject

data class DataTypeSpec(
    val key: String,
    val label: String,
    val category: String,
    val scope: String,
    val recordType: String,
    val operation: String,
    val filterField: String?,
    val autoSync: Boolean,
)

data class NormalizedRecord(
    val dataType: String,
    val recordId: String,
    val recordKind: String,
    val startTime: String?,
    val endTime: String?,
    val source: String,
    val payload: String,
)

data class MetricCard(
    val dataType: String,
    val label: String,
    val unit: String,
    val current: Double?,
    val baseline: Double?,
    val deltaPercent: Double?,
    val completion: Boolean,
    val valueDate: String,
    val sparkline: List<Double>,
)

data class AiResult(val answer: String, val engine: String, val model: String?)

enum class AiEngine(val title: String) {
    AUTOMATIC("Automatic (recommended)"),
    GEMINI_NANO("Android built-in AI · Gemini Nano"),
    DETERMINISTIC("Deterministic evidence only"),
}

data class HardwareProfile(val ramGb: Int, val cpuThreads: Int, val device: String)

fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

fun parseSpecs(json: String): List<DataTypeSpec> {
    val a = JSONArray(json)
    return buildList {
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            add(
                DataTypeSpec(
                    key = o.getString("key"),
                    label = o.getString("label"),
                    category = o.getString("category"),
                    scope = o.getString("scope"),
                    recordType = o.getString("record_type"),
                    operation = o.optString("operation", "list"),
                    filterField = o.optNullableString("filter_field"),
                    autoSync = o.optBoolean("auto_sync", true),
                )
            )
        }
    }
}

fun parseMetricCards(json: String): List<MetricCard> {
    val root = JSONObject(json)
    val metrics = root.optJSONArray("metrics") ?: JSONArray()
    return buildList {
        for (i in 0 until metrics.length()) {
            val o = metrics.getJSONObject(i)
            val spark = o.optJSONArray("sparkline")
            val values = buildList {
                if (spark != null) {
                    for (j in 0 until spark.length()) {
                        val pair = spark.optJSONArray(j)
                        if (pair != null && pair.length() >= 2) {
                            val value = pair.optDouble(1, Double.NaN)
                            if (value.isFinite()) add(value)
                        }
                    }
                }
            }
            add(
                MetricCard(
                    dataType = o.optString("data_type"),
                    label = o.optString("label"),
                    unit = o.optString("unit"),
                    current = o.optDouble("current", Double.NaN).takeIf { it.isFinite() },
                    baseline = o.optDouble("baseline", Double.NaN).takeIf { it.isFinite() },
                    deltaPercent = o.optDouble("delta_percent", Double.NaN).takeIf { it.isFinite() },
                    completion = o.optBoolean("completion"),
                    valueDate = o.optString("value_date"),
                    sparkline = values,
                )
            )
        }
    }
}
