package io.github.sebrolens.vitalchronicle.android

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class AcceleratorKind { CPU, GPU, NPU }
enum class DriverScope { GENERIC, SPECIFIC, SYSTEM }
enum class AcceleratorRuntime { GGUF, GEMINI_NANO, LITERT }

data class AccelerationDriverSpec(
    val id: String,
    val title: String,
    val kind: AcceleratorKind,
    val scope: DriverScope,
    val runtime: AcceleratorRuntime,
    val backend: String,
    val rank: Int,
    val minApi: Int,
    val minAppVersion: String,
    val socHints: List<String>,
    val libraryHints: List<String>,
    val requiredSystemFeature: String?,
    val requiresPackagedBackend: Boolean,
    val description: String,
)

data class AccelerationDriverStatus(
    val spec: AccelerationDriverSpec,
    val available: Boolean,
    val reason: String,
    val requiresAppUpdate: Boolean = false,
) {
    val availabilityLabel: String
        get() = if (available) "AVAILABLE" else if (requiresAppUpdate) "APP UPDATE NEEDED" else "UNAVAILABLE"

    val scopeLabel: String
        get() = when (spec.scope) {
            DriverScope.GENERIC -> "generic driver"
            DriverScope.SPECIFIC -> "device-specific driver"
            DriverScope.SYSTEM -> "system runtime"
        }
}

/**
 * Data-driven accelerator registry.
 *
 * The remote JSON can advertise newly supported drivers without a new APK. It
 * never downloads executable code: a GGUF backend is marked available only when
 * its native library is already packaged in the APK and the phone exposes the
 * required Android capability. Drivers which require new native code are shown
 * as requiring an app update; VitalChronicle's normal signed APK updater then
 * delivers that backend safely.
 */
object AccelerationDriverCatalog {
    val drivers = mutableStateListOf<AccelerationDriverSpec>().apply { addAll(fallbackDrivers()) }

    private val initialized = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null
    @Volatile private var packagedNativeLibraries: Set<String> = emptySet()

    fun ensureInitialized(context: Context) {
        val context = context.applicationContext
        appContext = context
        packagedNativeLibraries = File(context.applicationInfo.nativeLibraryDir)
            .listFiles()
            ?.map { it.name.lowercase() }
            ?.toSet()
            .orEmpty()
        if (initialized.compareAndSet(false, true)) {
            loadCachedCatalog(context)
            refreshInBackground(context)
        }
    }

    fun refreshInBackground(context: Context) {
        if (!refreshing.compareAndSet(false, true)) return
        val context = context.applicationContext
        Thread({
            try {
                val request = Request.Builder()
                    .url(REMOTE_CATALOG_URL)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "VitalChronicle-Android/${BuildConfig.VERSION_NAME}")
                    .build()
                val json = AndroidHttpClient(context).execute(request).use { response ->
                    if (!response.isSuccessful) error("Accelerator catalog returned HTTP ${response.code}.")
                    response.body?.string() ?: error("Accelerator catalog response was empty.")
                }
                val parsed = parseRemoteCatalog(json)
                if (parsed.isNotEmpty()) {
                    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_CATALOG_JSON, json).apply()
                    mainHandler.post { replaceCatalog(parsed) }
                }
            } catch (_: Exception) {
                // The bundled catalog remains a complete offline fallback.
            } finally {
                refreshing.set(false)
            }
        }, "vital-accelerator-catalog").apply { isDaemon = true }.start()
    }

    fun ggufStatuses(hardware: HardwareProfile): List<AccelerationDriverStatus> =
        drivers
            .filter { it.runtime == AcceleratorRuntime.GGUF }
            .map { status(it, hardware, NanoCapability()) }
            .sortedWith(compareByDescending<AccelerationDriverStatus> { it.available }.thenByDescending { it.spec.rank })

    fun allStatuses(hardware: HardwareProfile, nano: NanoCapability): List<AccelerationDriverStatus> =
        drivers
            .map { status(it, hardware, nano) }
            .sortedWith(compareByDescending<AccelerationDriverStatus> { it.available }.thenByDescending { it.spec.rank })

    fun recommendedGguf(hardware: HardwareProfile): AccelerationDriverStatus =
        ggufStatuses(hardware).firstOrNull { it.available }
            ?: AccelerationDriverStatus(fallbackDrivers().last(), true, "Portable ARM CPU fallback")

    private fun status(
        spec: AccelerationDriverSpec,
        hardware: HardwareProfile,
        nano: NanoCapability,
    ): AccelerationDriverStatus {
        if (Build.VERSION.SDK_INT < spec.minApi) {
            return AccelerationDriverStatus(spec, false, "Requires Android API ${spec.minApi} or newer")
        }
        if (compareVersions(BuildConfig.VERSION_NAME, spec.minAppVersion) < 0) {
            return AccelerationDriverStatus(
                spec,
                false,
                "Supported by catalog, but requires VitalChronicle ${spec.minAppVersion}+",
                requiresAppUpdate = true,
            )
        }

        if (spec.runtime == AcceleratorRuntime.GEMINI_NANO) {
            return if (nano.supported) {
                AccelerationDriverStatus(
                    spec,
                    true,
                    if (nano.ready) "Android AICore is ready" else "Android AICore is supported and preparing the model",
                )
            } else {
                AccelerationDriverStatus(spec, false, nano.status.ifBlank { "Android AICore is unavailable" })
            }
        }

        if (spec.runtime == AcceleratorRuntime.LITERT) {
            return AccelerationDriverStatus(
                spec,
                false,
                "Reserved for compatible LiteRT models; downloaded GGUF models cannot use this runtime",
            )
        }

        val haystack = listOf(
            hardware.socManufacturer,
            hardware.socModel,
            hardware.device,
        ).joinToString(" ").lowercase()
        if (spec.socHints.isNotEmpty() && spec.socHints.none { it.lowercase() in haystack }) {
            return AccelerationDriverStatus(spec, false, "This driver does not match ${hardware.socDescription}")
        }

        val feature = spec.requiredSystemFeature
        if (!feature.isNullOrBlank()) {
            val pm = appContext?.packageManager
            if (pm == null || !pm.hasSystemFeature(feature)) {
                return AccelerationDriverStatus(spec, false, "Required Android hardware feature is not exposed by this phone")
            }
        }

        if (spec.backend.equals("CPU", ignoreCase = true)) {
            return AccelerationDriverStatus(spec, true, "Portable ARM backend packaged with VitalChronicle")
        }

        val backendKnownByLegacyProbe = spec.backend.uppercase() in hardware.packagedGgufBackends
        val backendLibraryPresent = spec.libraryHints.any { hint ->
            packagedNativeLibraries.any { library -> hint.lowercase() in library }
        }
        if (spec.requiresPackagedBackend && !backendKnownByLegacyProbe && !backendLibraryPresent) {
            val hardwareLooksUsable =
                spec.kind != AcceleratorKind.GPU ||
                    !spec.requiredSystemFeature.orEmpty().contains("vulkan") ||
                    hardware.vulkanCompute
            return AccelerationDriverStatus(
                spec,
                false,
                if (hardwareLooksUsable) {
                    "Phone capability detected, but this APK does not contain the ${spec.backend} backend"
                } else {
                    "Required accelerator capability is not available"
                },
                requiresAppUpdate = hardwareLooksUsable,
            )
        }

        return AccelerationDriverStatus(spec, true, "${spec.backend} backend and matching device capability detected")
    }

    private fun loadCachedCatalog(context: Context) {
        val cached = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CATALOG_JSON, null) ?: return
        runCatching { parseRemoteCatalog(cached) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::replaceCatalog)
    }

    private fun replaceCatalog(remote: List<AccelerationDriverSpec>) {
        val merged = LinkedHashMap<String, AccelerationDriverSpec>()
        fallbackDrivers().forEach { merged[it.id] = it }
        remote.forEach { merged[it.id] = it }
        drivers.clear()
        drivers.addAll(merged.values.sortedWith(compareByDescending<AccelerationDriverSpec> { it.rank }.thenBy { it.id }))
    }

    private fun parseRemoteCatalog(rawJson: String): List<AccelerationDriverSpec> {
        val root = JSONObject(rawJson)
        if (root.optInt("schemaVersion", 0) !in 1..MAX_SCHEMA_VERSION) return emptyList()
        val array = root.optJSONArray("drivers") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                parseDriver(item)?.let(::add)
            }
        }
    }

    private fun parseDriver(item: JSONObject): AccelerationDriverSpec? {
        val id = item.optString("id").trim()
        val title = item.optString("title").trim()
        val backend = item.optString("backend").trim().uppercase()
        if (!SAFE_ID.matches(id) || title.isBlank() || !SAFE_BACKEND.matches(backend)) return null
        val kind = enumValueOrNull<AcceleratorKind>(item.optString("kind")) ?: return null
        val scope = enumValueOrNull<DriverScope>(item.optString("scope")) ?: return null
        val runtime = enumValueOrNull<AcceleratorRuntime>(item.optString("runtime")) ?: return null
        val minAppVersion = item.optString("minAppVersion", "0.0.0").trim()
        if (!SEMVER.matches(minAppVersion)) return null
        return AccelerationDriverSpec(
            id = id,
            title = title,
            kind = kind,
            scope = scope,
            runtime = runtime,
            backend = backend,
            rank = item.optInt("rank", 0).coerceIn(0, 1000),
            minApi = item.optInt("minApi", 26).coerceIn(26, 99),
            minAppVersion = minAppVersion,
            socHints = item.optJSONArray("socHints").strings(),
            libraryHints = item.optJSONArray("libraryHints").strings(),
            requiredSystemFeature = item.optString("requiredSystemFeature").trim().ifBlank { null },
            requiresPackagedBackend = item.optBoolean("requiresPackagedBackend", runtime == AcceleratorRuntime.GGUF && backend != "CPU"),
            description = item.optString("description").trim(),
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }

    private fun JSONArray?.strings(): List<String> = buildList {
        val array = this@strings ?: return@buildList
        for (i in 0 until array.length()) {
            array.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        fun parse(value: String) = value.split('.').map { it.toIntOrNull() ?: 0 }.let {
            listOf(it.getOrElse(0) { 0 }, it.getOrElse(1) { 0 }, it.getOrElse(2) { 0 })
        }
        val a = parse(left)
        val b = parse(right)
        for (i in 0..2) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    private fun fallbackDrivers() = listOf(
        AccelerationDriverSpec(
            id = "hexagon-gguf",
            title = "Snapdragon Hexagon / HTP NPU",
            kind = AcceleratorKind.NPU,
            scope = DriverScope.SPECIFIC,
            runtime = AcceleratorRuntime.GGUF,
            backend = "HEXAGON",
            rank = 100,
            minApi = 26,
            minAppVersion = "0.5.0",
            socHints = listOf("qualcomm", "snapdragon"),
            libraryHints = listOf("hexagon", "ggml-htp"),
            requiredSystemFeature = null,
            requiresPackagedBackend = true,
            description = "Preferred GGUF NPU path when a stable llama.cpp Hexagon backend is packaged.",
        ),
        AccelerationDriverSpec(
            id = "vulkan-generic",
            title = "Vulkan compute GPU",
            kind = AcceleratorKind.GPU,
            scope = DriverScope.GENERIC,
            runtime = AcceleratorRuntime.GGUF,
            backend = "VULKAN",
            rank = 80,
            minApi = 26,
            minAppVersion = "0.5.0",
            socHints = emptyList(),
            libraryHints = listOf("ggml-vulkan", "vulkan"),
            requiredSystemFeature = "android.hardware.vulkan.compute",
            requiresPackagedBackend = true,
            description = "Vendor-neutral Android GPU fallback using the phone's Vulkan driver.",
        ),
        AccelerationDriverSpec(
            id = "opencl-adreno",
            title = "Adreno OpenCL GPU",
            kind = AcceleratorKind.GPU,
            scope = DriverScope.SPECIFIC,
            runtime = AcceleratorRuntime.GGUF,
            backend = "OPENCL",
            rank = 70,
            minApi = 26,
            minAppVersion = "0.5.0",
            socHints = listOf("qualcomm", "snapdragon", "adreno"),
            libraryHints = listOf("ggml-opencl", "opencl"),
            requiredSystemFeature = null,
            requiresPackagedBackend = true,
            description = "Optional Adreno-specific GPU backend when packaged and supported by the device driver.",
        ),
        AccelerationDriverSpec(
            id = "aicore-gemini-nano",
            title = "Android AICore",
            kind = AcceleratorKind.NPU,
            scope = DriverScope.SYSTEM,
            runtime = AcceleratorRuntime.GEMINI_NANO,
            backend = "AICORE",
            rank = 95,
            minApi = 26,
            minAppVersion = "0.5.0",
            socHints = emptyList(),
            libraryHints = emptyList(),
            requiredSystemFeature = null,
            requiresPackagedBackend = false,
            description = "System-managed accelerator path used by Gemini Nano when Android exposes AICore.",
        ),
        AccelerationDriverSpec(
            id = "litert-compiled-model",
            title = "LiteRT CompiledModel CPU/GPU/NPU",
            kind = AcceleratorKind.NPU,
            scope = DriverScope.GENERIC,
            runtime = AcceleratorRuntime.LITERT,
            backend = "LITERT",
            rank = 60,
            minApi = 26,
            minAppVersion = "0.5.0",
            socHints = emptyList(),
            libraryHints = emptyList(),
            requiredSystemFeature = null,
            requiresPackagedBackend = false,
            description = "Future generic accelerator path for compatible LiteRT models; it cannot execute GGUF files.",
        ),
        AccelerationDriverSpec(
            id = "cpu-kleidiai",
            title = "ARM CPU · KleidiAI",
            kind = AcceleratorKind.CPU,
            scope = DriverScope.GENERIC,
            runtime = AcceleratorRuntime.GGUF,
            backend = "CPU",
            rank = 10,
            minApi = 26,
            minAppVersion = "0.5.0",
            socHints = emptyList(),
            libraryHints = listOf("ggml-cpu"),
            requiredSystemFeature = null,
            requiresPackagedBackend = false,
            description = "Portable fallback used whenever no safe GPU/NPU backend is available.",
        ),
    )

    private const val REMOTE_CATALOG_URL =
        "https://raw.githubusercontent.com/SebRoLENS/VitalChronicle-android/main/app/src/main/assets/accelerator_catalog.json"
    private const val PREFERENCES_NAME = "accelerator_catalog"
    private const val KEY_CATALOG_JSON = "catalog_json"
    private const val MAX_SCHEMA_VERSION = 1
    private val SAFE_ID = Regex("^[a-z0-9._-]{2,64}$")
    private val SAFE_BACKEND = Regex("^[A-Z0-9._-]{2,32}$")
    private val SEMVER = Regex("^\\d+\\.\\d+\\.\\d+$")
}
