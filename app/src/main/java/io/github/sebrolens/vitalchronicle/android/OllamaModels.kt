package io.github.sebrolens.vitalchronicle.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class OllamaModelSpec(
    val id: String,
    val family: String,
    val tag: String,
    val displayName: String,
    val parameterCount: String,
    val downloadBytes: Long,
    val sha256: String,
    val minimumRamGb: Int,
    val supportsThinking: Boolean,
    val catalogRank: Int = 0,
) {
    val registryUrl: String
        get() = "https://registry.ollama.ai/v2/library/$family/blobs/sha256:$sha256"

    val fileName: String
        get() = "${family}-${tag}-${sha256.take(12)}.gguf"
}

object OllamaModelCatalog {
    /**
     * Safe built-in fallback. The live catalog is downloaded from this repository
     * and merged into this SnapshotStateList, so Settings can show newly released
     * compatible models without requiring a new APK. Downloads themselves always
     * come from registry.ollama.ai and are SHA-256 verified before llama.cpp opens
     * the file.
     */
    val models = mutableStateListOf<OllamaModelSpec>().apply { addAll(fallbackModels()) }

    fun recommended(hardware: HardwareProfile): OllamaModelSpec {
        if (hardware.lowRamDevice) return models.minByOrNull { it.downloadBytes } ?: fallbackModels().first()
        val storageAllowance = hardware.freeStorageBytes - MODEL_STORAGE_RESERVE_BYTES
        val candidates = models.filter {
            hardware.ramGb >= it.minimumRamGb && storageAllowance >= it.downloadBytes
        }
        return candidates.maxWithOrNull(
            compareBy<OllamaModelSpec> { it.catalogRank }
                .thenBy { it.downloadBytes }
        ) ?: models.minByOrNull { it.downloadBytes } ?: fallbackModels().first()
    }

    fun replaceWithRemoteCatalog(rawJson: String): Int {
        val root = JSONObject(rawJson)
        val remote = root.getJSONArray("models")
        val parsed = buildList {
            for (index in 0 until remote.length()) {
                val item = remote.optJSONObject(index) ?: continue
                parseRemoteModel(item)?.let(::add)
            }
        }
        if (parsed.isEmpty()) return 0

        val merged = LinkedHashMap<String, OllamaModelSpec>()
        fallbackModels().forEach { merged[it.id] = it }
        parsed.forEach { merged[it.id] = it }
        val next = merged.values
            .sortedWith(
                compareByDescending<OllamaModelSpec> { it.catalogRank }
                    .thenBy { it.downloadBytes }
                    .thenBy { it.id }
            )

        models.clear()
        models.addAll(next)
        return parsed.size
    }

    private fun parseRemoteModel(item: JSONObject): OllamaModelSpec? {
        val id = item.optString("id").trim()
        val family = item.optString("family").trim()
        val tag = item.optString("tag").trim()
        val sha256 = item.optString("sha256").trim().lowercase()
        val downloadBytes = item.optLong("downloadBytes", -1L)
        if (!SAFE_COMPONENT.matches(family) || !SAFE_COMPONENT.matches(tag)) return null
        if (id != "$family:$tag") return null
        if (!SHA256.matches(sha256)) return null
        if (downloadBytes !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) return null

        val parameterCount = item.optString("parameterCount").trim().ifBlank { "Unknown" }
        val minimumRamGb = item.optInt("minimumRamGb", estimateMinimumRamGb(downloadBytes)).coerceIn(4, 64)
        return OllamaModelSpec(
            id = id,
            family = family,
            tag = tag,
            displayName = item.optString("displayName").trim().ifBlank { prettyName(family, tag) },
            parameterCount = parameterCount,
            downloadBytes = downloadBytes,
            sha256 = sha256,
            minimumRamGb = minimumRamGb,
            supportsThinking = item.optBoolean("supportsThinking", false),
            catalogRank = item.optInt("catalogRank", 0).coerceAtLeast(0),
        )
    }

    private fun fallbackModels() = listOf(
        OllamaModelSpec(
            id = "qwen3:0.6b",
            family = "qwen3",
            tag = "0.6b",
            displayName = "Qwen 3 · Compact",
            parameterCount = "0.6B",
            downloadBytes = 522_640_096,
            sha256 = "7f4030143c1c477224c5434f8272c662a8b042079a0a584f0a27a1684fe2e1fa",
            minimumRamGb = 4,
            supportsThinking = true,
            catalogRank = 10,
        ),
        OllamaModelSpec(
            id = "qwen3:1.7b",
            family = "qwen3",
            tag = "1.7b",
            displayName = "Qwen 3 · Balanced",
            parameterCount = "1.7B",
            downloadBytes = 1_359_279_776,
            sha256 = "3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6",
            minimumRamGb = 6,
            supportsThinking = true,
            catalogRank = 10,
        ),
        OllamaModelSpec(
            id = "qwen3:4b",
            family = "qwen3",
            tag = "4b",
            displayName = "Qwen 3 · Accurate",
            parameterCount = "4B",
            downloadBytes = 2_497_280_480,
            sha256 = "3e4cb14174460404e7a233e531675303b2fbf7749c02f91864fe311ab6344e4f",
            minimumRamGb = 10,
            supportsThinking = true,
            catalogRank = 10,
        ),
        OllamaModelSpec(
            id = "qwen3:8b",
            family = "qwen3",
            tag = "8b",
            displayName = "Qwen 3 · Maximum",
            parameterCount = "8B",
            downloadBytes = 5_225_374_496,
            sha256 = "a3de86cd1c132c822487ededd47a324c50491393e6565cd14bafa40d0b8e686f",
            minimumRamGb = 16,
            supportsThinking = true,
            catalogRank = 10,
        ),
    )

    fun estimateMinimumRamGb(downloadBytes: Long): Int {
        val gib = downloadBytes / 1_073_741_824.0
        return when {
            gib <= 0.8 -> 4
            gib <= 1.8 -> 6
            gib <= 3.5 -> 8
            gib <= 5.8 -> 10
            gib <= 8.5 -> 14
            gib <= 12.5 -> 18
            gib <= 19.5 -> 24
            else -> 32
        }
    }

    fun prettyName(family: String, tag: String): String {
        val name = family
            .replace(Regex("^qwen", RegexOption.IGNORE_CASE), "Qwen ")
            .replace(Regex("^gemma", RegexOption.IGNORE_CASE), "Gemma ")
            .replace(Regex("^llama", RegexOption.IGNORE_CASE), "Llama ")
            .replace(Regex("^phi", RegexOption.IGNORE_CASE), "Phi ")
            .replace(Regex("^deepseek", RegexOption.IGNORE_CASE), "DeepSeek ")
            .replace(Regex("^mistral", RegexOption.IGNORE_CASE), "Mistral ")
            .replace(Regex("^granite", RegexOption.IGNORE_CASE), "Granite ")
            .replace(Regex("^smollm", RegexOption.IGNORE_CASE), "SmolLM ")
            .trim()
        return "$name · $tag"
    }

    private val SAFE_COMPONENT = Regex("^[A-Za-z0-9._-]+$")
    private val SHA256 = Regex("^[a-f0-9]{64}$")
    private const val MIN_MODEL_BYTES = 50L * 1024L * 1024L
    private const val MAX_MODEL_BYTES = 24L * 1024L * 1024L * 1024L
    private const val MODEL_STORAGE_RESERVE_BYTES = 768L * 1024L * 1024L
}

sealed interface OllamaInstallState {
    data object NotInstalled : OllamaInstallState
    data class Paused(val downloadedBytes: Long, val totalBytes: Long) : OllamaInstallState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : OllamaInstallState
    data class Verifying(val totalBytes: Long) : OllamaInstallState
    data class Installed(val path: String, val bytes: Long) : OllamaInstallState
    data class Failed(val message: String, val downloadedBytes: Long, val totalBytes: Long) : OllamaInstallState
}

class OllamaModelManager(
    context: Context,
    private val http: AndroidHttpClient,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    val modelDirectory: File =
        (appContext.getExternalFilesDir(MODEL_DIRECTORY) ?: File(appContext.filesDir, MODEL_DIRECTORY))
            .also { it.mkdirs() }

    init {
        loadCachedCatalog()
        refreshCatalogInBackground()
    }

    fun freeStorageBytes(): Long = StatFs(modelDirectory.absolutePath).availableBytes

    fun selectedModelId(): String? = preferences.getString(KEY_SELECTED_MODEL, null)

    fun select(model: OllamaModelSpec) {
        require(installedFile(model) != null) { "Download ${model.id} before selecting it." }
        preferences.edit().putString(KEY_SELECTED_MODEL, model.id).apply()
    }

    fun installedFile(model: OllamaModelSpec): File? {
        val file = File(modelDirectory, model.fileName)
        return file.takeIf { it.isFile && it.length() == model.downloadBytes }
    }

    fun state(model: OllamaModelSpec): OllamaInstallState {
        installedFile(model)?.let { return OllamaInstallState.Installed(it.absolutePath, it.length()) }
        val partial = partialFile(model)
        return if (partial.isFile && partial.length() > 0L) {
            OllamaInstallState.Paused(partial.length().coerceAtMost(model.downloadBytes), model.downloadBytes)
        } else {
            OllamaInstallState.NotInstalled
        }
    }

    fun states(): Map<String, OllamaInstallState> =
        OllamaModelCatalog.models.associate { it.id to state(it) }

    suspend fun download(
        model: OllamaModelSpec,
        onState: (OllamaInstallState) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        installedFile(model)?.let {
            onState(OllamaInstallState.Installed(it.absolutePath, it.length()))
            return@withContext it
        }

        val partial = partialFile(model)
        var existingBytes = partial.length().coerceAtMost(model.downloadBytes)
        val requiredBytes = (model.downloadBytes - existingBytes) + DOWNLOAD_STORAGE_RESERVE_BYTES
        require(freeStorageBytes() >= requiredBytes) {
            "Not enough free storage. ${formatBytes(requiredBytes)} is required including safety headroom."
        }

        try {
            if (partial.length() != model.downloadBytes) {
                val request = Request.Builder()
                    .url(model.registryUrl)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "VitalChronicle-Android/${BuildConfig.VERSION_NAME}")
                    .apply { if (existingBytes > 0L) header("Range", "bytes=$existingBytes-") }
                    .build()

                http.execute(request).use { response ->
                    if (!response.isSuccessful) error("Ollama registry returned HTTP ${response.code}.")
                    val append = existingBytes > 0L && response.code == 206
                    if (!append) {
                        require(freeStorageBytes() + existingBytes >= model.downloadBytes + DOWNLOAD_STORAGE_RESERVE_BYTES) {
                            "Not enough free storage to restart this model download."
                        }
                        existingBytes = 0L
                    }

                    val body = response.body ?: error("Ollama registry returned an empty model body.")
                    FileOutputStream(partial, append).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            var downloaded = existingBytes
                            var lastPublishedAt = 0L
                            onState(OllamaInstallState.Downloading(downloaded, model.downloadBytes))
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                val now = System.nanoTime()
                                if (now - lastPublishedAt >= PROGRESS_INTERVAL_NANOS || downloaded >= model.downloadBytes) {
                                    onState(
                                        OllamaInstallState.Downloading(
                                            downloaded.coerceAtMost(model.downloadBytes),
                                            model.downloadBytes,
                                        )
                                    )
                                    lastPublishedAt = now
                                }
                            }
                            output.fd.sync()
                        }
                    }
                }
            }

            require(partial.length() == model.downloadBytes) {
                "Incomplete model download: received ${formatBytes(partial.length())} of ${formatBytes(model.downloadBytes)}."
            }
            onState(OllamaInstallState.Verifying(model.downloadBytes))
            val actualDigest = sha256(partial)
            require(actualDigest.equals(model.sha256, ignoreCase = true)) {
                partial.delete()
                "Model integrity verification failed. The incomplete file was removed."
            }

            val completed = File(modelDirectory, model.fileName)
            if (completed.exists() && !completed.delete()) error("Unable to replace the existing model file.")
            require(partial.renameTo(completed)) { "Unable to finalize the verified model download." }
            onState(OllamaInstallState.Installed(completed.absolutePath, completed.length()))
            completed
        } catch (e: CancellationException) {
            onState(OllamaInstallState.Paused(partial.length(), model.downloadBytes))
            throw e
        } catch (e: Exception) {
            onState(
                OllamaInstallState.Failed(
                    e.message ?: e.javaClass.simpleName,
                    partial.length().coerceAtMost(model.downloadBytes),
                    model.downloadBytes,
                )
            )
            throw e
        }
    }

    fun delete(model: OllamaModelSpec) {
        val installed = File(modelDirectory, model.fileName)
        val partial = partialFile(model)
        if (installed.exists() && !installed.delete()) error("Unable to delete ${model.id}.")
        if (partial.exists() && !partial.delete()) error("Unable to delete the partial ${model.id} download.")
        if (selectedModelId() == model.id) preferences.edit().remove(KEY_SELECTED_MODEL).apply()
    }

    private fun loadCachedCatalog() {
        val cached = preferences.getString(KEY_CATALOG_JSON, null) ?: return
        runCatching { OllamaModelCatalog.replaceWithRemoteCatalog(cached) }
    }

    private fun refreshCatalogInBackground() {
        Thread({
            runCatching {
                val request = Request.Builder()
                    .url(REMOTE_CATALOG_URL)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "VitalChronicle-Android/${BuildConfig.VERSION_NAME}")
                    .build()
                val json = http.execute(request).use { response ->
                    if (!response.isSuccessful) error("Model catalog returned HTTP ${response.code}.")
                    response.body?.string() ?: error("Model catalog response was empty.")
                }
                // Validate completely before caching or exposing the new entries.
                JSONObject(json).getJSONArray("models")
                preferences.edit().putString(KEY_CATALOG_JSON, json).apply()
                mainHandler.post { runCatching { OllamaModelCatalog.replaceWithRemoteCatalog(json) } }
            }
        }, "VitalChronicle-model-catalog").apply { isDaemon = true }.start()
    }

    private fun partialFile(model: OllamaModelSpec) = File(modelDirectory, "${model.fileName}.part")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFERENCES_NAME = "ollama_models"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_CATALOG_JSON = "remote_catalog_json"
        private const val MODEL_DIRECTORY = "ollama-models"
        private const val REMOTE_CATALOG_URL =
            "https://raw.githubusercontent.com/SebRoLENS/VitalChronicle-android/main/app/src/main/assets/ollama_catalog.json"
        private const val DOWNLOAD_BUFFER_BYTES = 1024 * 1024
        private const val DIGEST_BUFFER_BYTES = 4 * 1024 * 1024
        private const val DOWNLOAD_STORAGE_RESERVE_BYTES = 512L * 1024L * 1024L
        private const val PROGRESS_INTERVAL_NANOS = 100_000_000L
    }
}

fun formatBytes(bytes: Long): String {
    val gib = bytes / 1_073_741_824.0
    return if (gib >= 1.0) "%.2f GB".format(gib) else "%.0f MB".format(bytes / 1_048_576.0)
}
