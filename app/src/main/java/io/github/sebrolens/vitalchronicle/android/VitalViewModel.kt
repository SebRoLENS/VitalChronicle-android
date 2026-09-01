package io.github.sebrolens.vitalchronicle.android

import android.app.Activity
import android.app.Application
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

class VitalViewModel(app: Application) : AndroidViewModel(app) {
    val database = HealthDatabase(app)
    private val core = PythonCore()
    val vault = CredentialVault(app)
    private val http = AndroidHttpClient(app)
    private val updater = GitHubAppUpdater(app, http)
    private val oauth = GoogleAuthorizationManager(app, vault)
    private val syncer = GoogleHealthSync(database, core, oauth, http)
    private val nano = GeminiNanoEngine()
    private val ollamaModels = OllamaModelManager(app, http)
    private val ollama = OllamaOnDeviceEngine(app)
    val specs: List<DataTypeSpec> by lazy { core.specs() }
    private val googleScopes: Set<String> by lazy { specs.map { it.scope }.filter { it.isNotBlank() }.toSet() }

    var metrics by mutableStateOf<List<MetricCard>>(emptyList()); private set
    var counts by mutableStateOf<Map<String, Int>>(emptyMap()); private set
    var status by mutableStateOf("Ready"); private set
    var busy by mutableStateOf(false); private set
    var aiAnswer by mutableStateOf(""); private set
    var aiThinking by mutableStateOf(""); private set
    var aiThinkingActive by mutableStateOf(false); private set
    var aiGeneratedTokens by mutableStateOf(0); private set
    var aiMaximumTokens by mutableStateOf(0); private set
    var aiTokensPerSecond by mutableStateOf(0.0); private set
    var aiEngine by mutableStateOf(AiEngine.AUTOMATIC)
    var aiModelName by mutableStateOf<String?>(null); private set
    var googleConnected by mutableStateOf(vault.nativeGoogleConnected()); private set
    private var requestedHistoryDays by mutableStateOf(DataRetention.GENERAL_DAYS)
    var historyDays: Int
        get() = requestedHistoryDays
        set(value) { requestedHistoryDays = value.coerceIn(1, DataRetention.GENERAL_DAYS) }
    var analysisDays by mutableStateOf(28)
    var advancedOpen by mutableStateOf(false)
    var lastError by mutableStateOf<String?>(null); private set
    var updateState by mutableStateOf<AppUpdateState>(AppUpdateState.Idle); private set
    var updatePromptDismissed by mutableStateOf(false); private set
    private var awaitingUpdateInstallPermission = false
    private var modelDownloadJob: Job? = null
    private var analysisJob: Job? = null

    val googlePackageName: String = app.packageName
    val googleSigningSha1: String = runCatching { GoogleAuthorizationManager.signingSha1(app) }
        .getOrElse { "Unavailable: ${it.message ?: it.javaClass.simpleName}" }

    val hardware: HardwareProfile = run {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        HardwareProfile(
            ramGb = ((info.totalMem + 536_870_912L) / 1_073_741_824L).toInt(),
            cpuThreads = Runtime.getRuntime().availableProcessors(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
            freeStorageBytes = ollamaModels.freeStorageBytes(),
            lowRamDevice = am.isLowRamDevice,
        )
    }
    val ollamaCatalog: List<OllamaModelSpec> = OllamaModelCatalog.models
    val recommendedOllamaModel: OllamaModelSpec = OllamaModelCatalog.recommended(hardware)
    var selectedOllamaModelId by mutableStateOf(run {
        val persisted = ollamaModels.selectedModelId()
        val persistedModel = ollamaCatalog.firstOrNull { it.id == persisted }
        when {
            persistedModel != null && ollamaModels.installedFile(persistedModel) != null -> persistedModel.id
            else -> ollamaCatalog.lastOrNull {
                hardware.ramGb >= it.minimumRamGb && ollamaModels.installedFile(it) != null
            }?.id ?: recommendedOllamaModel.id
        }
    }); private set
    var ollamaModelStates by mutableStateOf(ollamaModels.states()); private set
    var modelManagerMessage by mutableStateOf<String?>(null); private set

    init {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { database.pruneRetention(compact = true) }
            if (removed > 0) status = "$removed expired local records removed"
            refresh()
        }
        probeAi()
        checkForAppUpdate()
    }

    /**
     * The shared Python core now reads Android SQLite lazily, exactly as the desktop
     * analysis layer reads its store. No complete archive JSON is materialised in RAM.
     */
    fun refresh() {
        viewModelScope.launch {
            try {
                val refreshedCounts = withContext(Dispatchers.IO) { database.counts() }
                counts = refreshedCounts
                if (refreshedCounts.isEmpty()) {
                    metrics = emptyList()
                    return@launch
                }

                val today = LocalDate.now()
                val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }
                val dashboardJson = withContext(Dispatchers.Default) {
                    core.dashboardFromDatabase(databasePath, today.toString())
                }
                metrics = parseMetricCards(dashboardJson)
                if (metrics.isEmpty()) {
                    status = "Data loaded · no dashboard metric could be derived yet"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                metrics = emptyList()
                lastError = "Unable to build the local dashboard: ${e.message ?: e.javaClass.simpleName}"
                status = "Dashboard unavailable · local data are still stored safely"
            }
        }
    }

    fun connectGoogle(launchResolution: (PendingIntent) -> Unit) {
        launchBusy("Connecting with Google Identity Services…") {
            val pendingIntent = oauth.beginAuthorization(googleScopes)
            if (pendingIntent != null) {
                status = "Complete Google authorization"
                launchResolution(pendingIntent)
            } else {
                vault.clearLegacyOAuth()
                googleConnected = true
                status = "Google account connected"
            }
        }
    }

    fun completeGoogleAuthorization(data: Intent?) {
        launchBusy("Completing Google authorization…") {
            oauth.completeAuthorization(data)
            vault.clearLegacyOAuth()
            googleConnected = true
            status = "Google account connected"
        }
    }

    fun googleAuthorizationCancelled() {
        lastError = "Google authorization was cancelled or could not be completed."
        status = "Google authorization cancelled"
    }

    fun disconnectGoogle() {
        launchBusy("Disconnecting Google account…") {
            oauth.disconnect(googleScopes)
            googleConnected = false
            status = "Google account disconnected"
        }
    }

    fun checkForAppUpdate() {
        if (updateState is AppUpdateState.Checking || updateState is AppUpdateState.Downloading) return
        viewModelScope.launch {
            updateState = AppUpdateState.Checking
            try {
                withContext(Dispatchers.IO) { updater.cleanupDownloadedApks() }
                val update = updater.findUpdate(BuildConfig.VERSION_NAME)
                if (update == null) {
                    updateState = AppUpdateState.UpToDate
                } else {
                    updateState = AppUpdateState.Downloading(update.version)
                    val apk = updater.downloadAndVerify(update)
                    updateState = AppUpdateState.Ready(update, apk.absolutePath)
                    updatePromptDismissed = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState = AppUpdateState.Failed(
                    e.message ?: "Unable to check for application updates."
                )
            }
        }
    }

    fun dismissUpdatePrompt() {
        updatePromptDismissed = true
    }

    fun installUpdate(activity: Activity) {
        val ready = updateState as? AppUpdateState.Ready ?: return
        try {
            if (!updater.canInstallPackages()) {
                awaitingUpdateInstallPermission = true
                activity.startActivity(updater.unknownSourcesIntent())
            } else {
                launchUpdateInstaller(activity, ready)
            }
        } catch (e: Exception) {
            updateState = AppUpdateState.Failed(
                e.message ?: "Unable to open the Android package installer."
            )
        }
    }

    fun resumePendingUpdateInstallation(activity: Activity) {
        if (!awaitingUpdateInstallPermission || !updater.canInstallPackages()) return
        val ready = updateState as? AppUpdateState.Ready ?: run {
            awaitingUpdateInstallPermission = false
            return
        }
        try {
            launchUpdateInstaller(activity, ready)
        } catch (e: Exception) {
            updateState = AppUpdateState.Failed(
                e.message ?: "Unable to open the Android package installer."
            )
        }
    }

    private fun launchUpdateInstaller(activity: Activity, ready: AppUpdateState.Ready) {
        activity.startActivity(updater.installationIntent(File(ready.apkPath)))
        awaitingUpdateInstallPermission = false
        updatePromptDismissed = true
    }

    fun downloadOllamaModel(modelId: String) {
        val model = ollamaCatalog.firstOrNull { it.id == modelId } ?: return
        if (modelDownloadJob?.isActive == true) return
        modelManagerMessage = null
        modelDownloadJob = viewModelScope.launch {
            try {
                val file = ollamaModels.download(model) { downloadState ->
                    viewModelScope.launch {
                        ollamaModelStates = ollamaModelStates + (model.id to downloadState)
                    }
                }
                ollamaModels.select(model)
                selectedOllamaModelId = model.id
                ollamaModelStates = ollamaModels.states()
                aiModelName = "${model.id} · on-device llama.cpp"
                modelManagerMessage = "${model.id} downloaded, verified and selected."
                require(file.length() == model.downloadBytes)
            } catch (e: CancellationException) {
                ollamaModelStates = ollamaModels.states()
                modelManagerMessage = "${model.id} download paused. Resume it whenever you are ready."
            } catch (e: Exception) {
                ollamaModelStates = ollamaModelStates + (
                    model.id to OllamaInstallState.Failed(
                        e.message ?: e.javaClass.simpleName,
                        (ollamaModels.state(model) as? OllamaInstallState.Paused)?.downloadedBytes ?: 0L,
                        model.downloadBytes,
                    )
                )
                modelManagerMessage = e.message ?: "Unable to download ${model.id}."
            }
        }
    }

    fun cancelOllamaDownload(modelId: String) {
        val state = ollamaModelStates[modelId]
        if (state is OllamaInstallState.Downloading || state is OllamaInstallState.Verifying) {
            modelDownloadJob?.cancel()
        }
    }

    fun selectOllamaModel(modelId: String) {
        val model = ollamaCatalog.firstOrNull { it.id == modelId } ?: return
        try {
            ollamaModels.select(model)
            selectedOllamaModelId = model.id
            aiModelName = "${model.id} · on-device llama.cpp"
            modelManagerMessage = "${model.id} selected for local analysis."
        } catch (e: Exception) {
            modelManagerMessage = e.message
        }
    }

    fun deleteOllamaModel(modelId: String) {
        val model = ollamaCatalog.firstOrNull { it.id == modelId } ?: return
        if (busy || modelDownloadJob?.isActive == true) {
            modelManagerMessage = "Stop the active analysis or download before deleting a model."
            return
        }
        viewModelScope.launch {
            try {
                ollama.unload()
                withContext(Dispatchers.IO) { ollamaModels.delete(model) }
                ollamaModelStates = ollamaModels.states()
                if (selectedOllamaModelId == model.id) {
                    val replacement = ollamaCatalog.lastOrNull {
                        hardware.ramGb >= it.minimumRamGb && ollamaModels.installedFile(it) != null
                    }
                    if (replacement != null) {
                        ollamaModels.select(replacement)
                        selectedOllamaModelId = replacement.id
                    } else {
                        selectedOllamaModelId = recommendedOllamaModel.id
                    }
                }
                modelManagerMessage = "${model.id} removed from this device."
                probeAi()
            } catch (e: Exception) {
                modelManagerMessage = e.message ?: "Unable to remove ${model.id}."
            }
        }
    }

    fun sync() {
        launchBusy("Starting Google Health sync…") {
            syncer.sync(historyDays) { status = it }
            refresh()
        }
    }

    fun analyse(question: String) {
        if (question.isBlank()) return
        resetStreamingAnswer()
        analysisJob = launchBusy("Preparing deterministic evidence…") {
            val today = LocalDate.now()
            val start = today.minusDays((analysisDays - 1).toLong()).toString()
            val end = today.plusDays(1).toString()
            val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }

            when (aiEngine) {
                AiEngine.DETERMINISTIC -> {
                    status = "Computing deterministic metrics directly from the local archive…"
                    val evidence = withContext(Dispatchers.Default) {
                        core.evidenceFromDatabase(databasePath, start, end)
                    }
                    aiAnswer = deterministicSummary(evidence)
                    status = "Deterministic analysis complete"
                }

                AiEngine.OLLAMA_LOCAL, AiEngine.AUTOMATIC, AiEngine.GEMINI_NANO -> {
                    status = "Selecting deterministic evidence relevant to your question…"
                    val modelEvidence = withContext(Dispatchers.Default) {
                        core.nanoEvidenceFromDatabase(databasePath, start, end, question)
                    }

                    val selectedModel = ollamaCatalog.first { it.id == selectedOllamaModelId }
                    val installedModel = ollamaModels.installedFile(selectedModel)
                    val useDownloadedModel = aiEngine == AiEngine.OLLAMA_LOCAL ||
                        (aiEngine == AiEngine.AUTOMATIC && installedModel != null)

                    if (useDownloadedModel) {
                        if (installedModel == null) {
                            error("Download and select an Ollama model from Settings before using this engine.")
                        }
                        try {
                            analyseWithOllama(selectedModel, installedModel, question, modelEvidence)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            if (e is VirtualMachineError || e is ThreadDeath) throw e
                            if (aiEngine == AiEngine.OLLAMA_LOCAL) throw e
                            status = "Downloaded model unavailable · trying Gemini Nano…"
                            analyseWithNanoOrFallback(e, question, modelEvidence, databasePath, start, end)
                        }
                    } else {
                        analyseWithNanoOrFallback(null, question, modelEvidence, databasePath, start, end)
                    }
                }
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
    }

    private suspend fun analyseWithOllama(
        model: OllamaModelSpec,
        modelFile: File,
        question: String,
        evidence: String,
    ) {
        val maximumTokens = if (model.parameterCount == "0.6B") 768 else 1024
        aiMaximumTokens = maximumTokens
        aiModelName = "${model.id} · on-device llama.cpp"
        ollama.answer(
            model = model,
            modelFile = modelFile,
            question = question,
            evidenceJson = evidence,
            maximumTokens = maximumTokens,
            onStage = { status = it },
            onSnapshot = { snapshot ->
                aiThinking = snapshot.thinking
                aiAnswer = snapshot.answer
                aiThinkingActive = snapshot.thinkingActive
                aiGeneratedTokens = snapshot.generatedTokens
                aiMaximumTokens = snapshot.maximumTokens
                aiTokensPerSecond = snapshot.tokensPerSecond
            },
        )
        aiThinkingActive = false
        status = "Analysis complete · ${model.id} · ${aiGeneratedTokens} tokens"
    }

    private suspend fun analyseWithNanoOrFallback(
        ollamaFailure: Throwable?,
        question: String,
        evidence: String,
        databasePath: String,
        start: String,
        end: String,
    ) {
        try {
            val result = nano.answer(question, evidence) { status = it }
            aiAnswer = result.answer
            aiModelName = result.model
            status = "Analysis complete · ${result.engine}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            useDeterministicFallback(e, ollamaFailure, databasePath, start, end)
        } catch (e: Exception) {
            useDeterministicFallback(e, ollamaFailure, databasePath, start, end)
        }
    }

    private suspend fun useDeterministicFallback(
        failure: Throwable,
        earlierFailure: Throwable?,
        databasePath: String,
        start: String,
        end: String,
    ) {
        if (aiEngine == AiEngine.GEMINI_NANO) throw failure

        // Automatic mode remains robust even for binary-linkage failures in a
        // third-party AI runtime. Fatal VM errors are deliberately not swallowed.
        status = "Nano unavailable · preparing deterministic fallback…"
        val evidence = withContext(Dispatchers.Default) {
            core.evidenceFromDatabase(databasePath, start, end)
        }
        aiAnswer = deterministicSummary(evidence) +
            "\n\nLocal generative AI is not available: " +
            listOfNotNull(earlierFailure, failure)
                .joinToString("; ") { it.message ?: it.javaClass.simpleName }
        status = "Deterministic fallback used"
    }

    private fun resetStreamingAnswer() {
        aiAnswer = ""
        aiThinking = ""
        aiThinkingActive = false
        aiGeneratedTokens = 0
        aiMaximumTokens = 0
        aiTokensPerSecond = 0.0
    }

    private fun deterministicSummary(evidence: String): String {
        val root = JSONObject(evidence)
        val coverage = root.optJSONObject("requested_interval_coverage")
        val insights = root.optJSONArray("candidate_insights")
        return buildString {
            append("Deterministic VitalChronicle evidence\n\n")
            coverage?.optString("coverage_notice")?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
            if (insights == null || insights.length() == 0) append("No strong candidate insight was detected in the selected interval.")
            else {
                val limit = minOf(8, insights.length())
                for (i in 0 until limit) {
                    val o = insights.getJSONObject(i)
                    append("• ").append(o.optString("headline")).append(" [").append(o.optString("confidence", "unknown")).append("]\n")
                }
            }
        }
    }

    private fun probeAi() {
        val selected = ollamaCatalog.firstOrNull { it.id == selectedOllamaModelId }
        if (selected != null && ollamaModels.installedFile(selected) != null) {
            aiModelName = "${selected.id} · on-device llama.cpp"
            return
        }
        viewModelScope.launch { aiModelName = nano.modelName() }
    }

    private fun launchBusy(initial: String, block: suspend () -> Unit): Job? {
        if (busy) return null
        return viewModelScope.launch {
            busy = true; status = initial; lastError = null
            try {
                block()
            } catch (e: CancellationException) {
                status = "Operation stopped"
                throw e
            } catch (e: LinkageError) {
                lastError = "Incompatible Android AI runtime: ${e.message ?: e.javaClass.simpleName}"
                status = "Error"
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                status = "Error"
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        nano.close()
        ollama.close()
        database.close()
        super.onCleared()
    }
}
