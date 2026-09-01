package io.github.sebrolens.vitalchronicle.android

import android.app.Application
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

class VitalViewModel(app: Application) : AndroidViewModel(app) {
    val database = HealthDatabase(app)
    private val core = PythonCore()
    val vault = CredentialVault(app)
    private val http = AndroidHttpClient(app)
    private val oauth = GoogleAuthorizationManager(app, vault)
    private val syncer = GoogleHealthSync(database, core, oauth, http)
    private val nano = GeminiNanoEngine()
    val specs: List<DataTypeSpec> by lazy { core.specs() }
    private val googleScopes: Set<String> by lazy { specs.map { it.scope }.filter { it.isNotBlank() }.toSet() }
    private val aiDataTypes: List<String> by lazy { specs.filter { it.autoSync }.map { it.key } }

    var metrics by mutableStateOf<List<MetricCard>>(emptyList()); private set
    var counts by mutableStateOf<Map<String, Int>>(emptyMap()); private set
    var status by mutableStateOf("Ready"); private set
    var busy by mutableStateOf(false); private set
    var aiAnswer by mutableStateOf(""); private set
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

    val googlePackageName: String = app.packageName
    val googleSigningSha1: String = runCatching { GoogleAuthorizationManager.signingSha1(app) }
        .getOrElse { "Unavailable: ${it.message ?: it.javaClass.simpleName}" }

    val hardware: HardwareProfile = run {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        HardwareProfile((info.totalMem / 1_073_741_824.0).toInt(), Runtime.getRuntime().availableProcessors(), "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    init {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { database.pruneRetention(compact = true) }
            if (removed > 0) status = "$removed expired local records removed"
            refresh()
        }
        probeAi()
    }

    /**
     * Rebuild only the small dashboard working set. A malformed record or Python
     * metric error must never terminate the Android process: the archive remains
     * available and the failure is surfaced in the UI instead.
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
                val dashboardRecords = withContext(Dispatchers.IO) {
                    database.dashboardRecordsJson(today)
                }
                metrics = withContext(Dispatchers.Default) {
                    parseMetricCards(core.dashboard(dashboardRecords, today.toString()))
                }
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

    fun sync() {
        launchBusy("Starting Google Health sync…") {
            syncer.sync(historyDays) { status = it }
            refresh()
        }
    }

    fun analyse(question: String) {
        if (question.isBlank()) return
        launchBusy("Preparing deterministic evidence…") {
            val today = LocalDate.now()
            val startDay = today.minusDays((analysisDays - 1).toLong())
            val start = startDay.toString()
            val end = today.plusDays(1).toString()
            val records = withContext(Dispatchers.IO) {
                database.analysisRecordsJson(aiDataTypes, start, end)
            }
            val evidence = withContext(Dispatchers.Default) {
                core.evidence(records, start, end)
            }
            when (aiEngine) {
                AiEngine.DETERMINISTIC -> {
                    aiAnswer = deterministicSummary(evidence)
                    status = "Deterministic analysis complete"
                }
                AiEngine.AUTOMATIC, AiEngine.GEMINI_NANO -> {
                    try {
                        val result = nano.answer(question, evidence) { status = it }
                        aiAnswer = result.answer
                        aiModelName = result.model
                        status = "Analysis complete · ${result.engine}"
                    } catch (e: Exception) {
                        if (aiEngine == AiEngine.GEMINI_NANO) throw e
                        aiAnswer = deterministicSummary(evidence) + "\n\nGemini Nano is not available on this device/runtime: ${e.message ?: "unknown error"}"
                        status = "Deterministic fallback used"
                    }
                }
            }
        }
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
        viewModelScope.launch { aiModelName = nano.modelName() }
    }

    private fun launchBusy(initial: String, block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true; status = initial; lastError = null
            try { block() } catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName; status = "Error" }
            finally { busy = false }
        }
    }

    override fun onCleared() { nano.close(); database.close(); super.onCleared() }
}
