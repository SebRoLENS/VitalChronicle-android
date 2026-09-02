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
    val percentage: Double?,
    val completion: Boolean,
    val valueDate: String,
    val latestAvailable: Boolean,
    val sparkline: List<Double>,
    val sparklineMean: Double?,
    val sparklineStd: Double?,
    val heartDaySmoothed: List<Double>,
    val heartDayMean: Double?,
    val heartDayMin: Double?,
    val heartDayMax: Double?,
    val heartDaySampleCount: Int,
    val heartSmoothingMinutes: Int,
)

data class AiResult(val answer: String, val engine: String, val model: String?)

enum class AiEngine(val title: String) {
    AUTOMATIC("Automatic (recommended)"),
    OLLAMA_LOCAL("Downloaded Ollama model · llama.cpp"),
    GEMINI_NANO("Android built-in AI · Gemini Nano"),
    DETERMINISTIC("Deterministic evidence only"),
}

data class HardwareProfile(
    val ramGb: Int,
    val cpuThreads: Int,
    val device: String,
    val abi: String,
    val freeStorageBytes: Long,
    val lowRamDevice: Boolean,
    val socManufacturer: String,
    val socModel: String,
    val vulkanCompute: Boolean,
    val vulkanVersion: Int,
    val packagedGgufBackends: Set<String>,
) {
    val socDescription: String
        get() = listOf(socManufacturer, socModel)
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .joinToString(" ")
            .ifBlank { "SoC not reported by Android" }

    val ggufHardwareAccelerated: Boolean
        get() = when {
            "HEXAGON" in packagedGgufBackends && socManufacturer.contains("qualcomm", ignoreCase = true) -> true
            "VULKAN" in packagedGgufBackends && vulkanCompute -> true
            "OPENCL" in packagedGgufBackends -> true
            else -> false
        }

    val ggufAccelerationBackend: String
        get() = when {
            "HEXAGON" in packagedGgufBackends && socManufacturer.contains("qualcomm", ignoreCase = true) -> "Snapdragon Hexagon NPU"
            "VULKAN" in packagedGgufBackends && vulkanCompute -> "Vulkan GPU"
            "OPENCL" in packagedGgufBackends -> "OpenCL GPU"
            else -> "ARM CPU · KleidiAI"
        }

    val accelerationSummary: String
        get() = buildString {
            append("GGUF: ").append(ggufAccelerationBackend)
            if (!ggufHardwareAccelerated && vulkanCompute) {
                append(" · Vulkan compute detected, but this APK has no Vulkan GGUF backend")
            }
        }
}

data class NanoCapability(
    val supported: Boolean = false,
    val ready: Boolean = false,
    val modelName: String? = null,
    val status: String = "Checking",
) {
    val runtimeLabel: String
        get() = if (supported) "Android AICore · optimized on-device runtime" else "Android AICore unavailable"
}

fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

private fun JSONObject.optFiniteDouble(key: String): Double? =
    optDouble(key, Double.NaN).takeIf { it.isFinite() }

private fun JSONObject.seriesValues(key: String): List<Double> {
    val series = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (i in 0 until series.length()) {
            val pair = series.optJSONArray(i)
            val value = when {
                pair != null && pair.length() >= 2 -> pair.optDouble(1, Double.NaN)
                else -> series.optDouble(i, Double.NaN)
            }
            if (value.isFinite()) add(value)
        }
    }
}

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
            add(
                MetricCard(
                    dataType = o.optString("data_type"),
                    label = o.optString("label"),
                    unit = o.optString("unit"),
                    current = o.optFiniteDouble("current"),
                    baseline = o.optFiniteDouble("baseline"),
                    deltaPercent = o.optFiniteDouble("delta_percent"),
                    percentage = o.optFiniteDouble("percentage"),
                    completion = o.optBoolean("completion"),
                    valueDate = o.optString("value_date"),
                    latestAvailable = o.optBoolean("latest_available"),
                    sparkline = o.seriesValues("sparkline"),
                    sparklineMean = o.optFiniteDouble("sparkline_mean"),
                    sparklineStd = o.optFiniteDouble("sparkline_std"),
                    heartDaySmoothed = o.seriesValues("heart_day_smoothed"),
                    heartDayMean = o.optFiniteDouble("heart_day_mean"),
                    heartDayMin = o.optFiniteDouble("heart_day_min"),
                    heartDayMax = o.optFiniteDouble("heart_day_max"),
                    heartDaySampleCount = o.optInt("heart_day_sample_count", 0),
                    heartSmoothingMinutes = o.optInt("heart_smoothing_minutes", 0),
                )
            )
        }
    }
}
