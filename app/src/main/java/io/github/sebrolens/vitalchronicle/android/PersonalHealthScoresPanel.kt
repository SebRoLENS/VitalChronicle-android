package io.github.sebrolens.vitalchronicle.android

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

private data class PersonalHealthScores(
    val readinessScore: Int? = null,
    val readinessLabel: String = "",
    val readinessConfidence: Int? = null,
    val resilienceScore: Int? = null,
    val resilienceLabel: String = "",
    val resilienceConfidence: Int? = null,
    val trainingStatus: String = "",
    val acuteChronicRatio: Double? = null,
    val currentLoad: Double? = null,
    val targetLow: Double? = null,
    val targetHigh: Double? = null,
    val targetConfidence: Int? = null,
)

@Composable
fun PersonalHealthScoresPanel(vm: VitalViewModel) {
    if (!vm.personalAgentEnabled || vm.counts.isEmpty()) return
    val context = LocalContext.current
    val repository = remember { PersonalHealthScoresRepository(context.applicationContext) }
    var scores by remember { mutableStateOf<PersonalHealthScores?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm.counts, vm.personalAgentEnabled) {
        if (vm.personalAgentEnabled && vm.counts.isNotEmpty()) {
            runCatching { repository.load() }
                .onSuccess { scores = it; error = null }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }
    DisposableEffect(Unit) { onDispose { repository.close() } }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Insights, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Column {
                    Text("Personal health scores", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Deterministic estimates from your personal baselines · same agent tools as desktop",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                scores != null -> PersonalScoresGrid(scores!!)
                error != null -> Text(
                    "Personal scores are not available yet: ${error.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PersonalScoresGrid(scores: PersonalHealthScores) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonalScoreTile(
                "Readiness",
                scores.readinessScore?.let { "$it/100" } ?: "—",
                scoreCaption(scores.readinessLabel, scores.readinessConfidence),
                Modifier.weight(1f),
            )
            PersonalScoreTile(
                "Resilience",
                scores.resilienceScore?.let { "$it/100" } ?: "—",
                scoreCaption(scores.resilienceLabel, scores.resilienceConfidence),
                Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonalScoreTile(
                "Training status",
                scores.trainingStatus.humanise().ifBlank { "—" },
                scores.acuteChronicRatio?.let { "Acute/chronic ratio ${"%.2f".format(it)}" } ?: "Not enough history yet",
                Modifier.weight(1f),
            )
            val target = if (scores.targetLow != null && scores.targetHigh != null) {
                "Target ${"%.0f".format(scores.targetLow)}–${"%.0f".format(scores.targetHigh)}"
            } else "Not enough history yet"
            PersonalScoreTile(
                "Training load",
                scores.currentLoad?.let { "%.0f".format(it) } ?: "—",
                buildString {
                    append(target)
                    scores.targetConfidence?.let { append(" · confidence $it%") }
                },
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PersonalScoreTile(title: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(caption.ifBlank { "Not enough data yet" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun scoreCaption(label: String, confidence: Int?): String = buildString {
    if (label.isNotBlank()) append(label.humanise())
    confidence?.let {
        if (isNotEmpty()) append(" · ")
        append("confidence $it%")
    }
    if (isEmpty()) append("Not enough data yet")
}

private fun String.humanise(): String = replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

private class PersonalHealthScoresRepository(context: Context) {
    private val database = HealthDatabase(context)
    private val core = PythonCore()
    private val agentPath = context.getDatabasePath("vitalchronicle_agent.sqlite3").absolutePath

    suspend fun load(): PersonalHealthScores = withContext(Dispatchers.Default) {
        val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }
        val end = LocalDate.now().toString()
        val readiness = execute(databasePath, "calculate_readiness", JSONObject().put("end", end))
        val resilience = execute(databasePath, "calculate_resilience", JSONObject().put("end", end))
        val training = execute(databasePath, "calculate_training_status", JSONObject().put("end", end))
        val target = execute(databasePath, "calculate_target_load", JSONObject().put("end", end))
        val load = training.optJSONObject("load")
        val targetRange = target.optJSONObject("target_weekly_load")
        PersonalHealthScores(
            readinessScore = readiness.numberOrNull("score")?.toInt(),
            readinessLabel = readiness.optString("label"),
            readinessConfidence = readiness.numberOrNull("confidence")?.let { (it * 100).toInt() },
            resilienceScore = resilience.numberOrNull("score")?.toInt(),
            resilienceLabel = resilience.optString("label"),
            resilienceConfidence = resilience.numberOrNull("confidence")?.let { (it * 100).toInt() },
            trainingStatus = training.optString("status"),
            acuteChronicRatio = load?.numberOrNull("acute_chronic_ratio"),
            currentLoad = target.numberOrNull("current_acute_load"),
            targetLow = targetRange?.numberOrNull("lower"),
            targetHigh = targetRange?.numberOrNull("upper"),
            targetConfidence = target.numberOrNull("confidence")?.let { (it * 100).toInt() },
        )
    }

    private fun execute(databasePath: String, name: String, args: JSONObject): JSONObject =
        JSONObject(core.executePersonalAgentTool(databasePath, agentPath, name, args.toString()))

    fun close() = database.close()
}

private fun JSONObject.numberOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getDouble(key) }.getOrNull()?.takeIf { it.isFinite() }
}
