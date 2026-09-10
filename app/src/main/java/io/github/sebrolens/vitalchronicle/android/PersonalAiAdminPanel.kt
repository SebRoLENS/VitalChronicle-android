package io.github.sebrolens.vitalchronicle.android

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class AgentToolRow(
    val name: String,
    val kind: String,
    val capability: String,
    val status: String,
    val uses: Int,
    val replacement: String,
    val description: String,
    val detail: String,
)

private data class AgentAssociationRow(
    val key: String,
    val statement: String,
    val confidence: Int,
    val evidenceCount: Int,
    val current: Boolean,
    val detail: String,
)

private data class AgentReportRow(
    val whenText: String,
    val statement: String,
    val category: String,
    val followUp: String,
)

private data class AgentEventRow(val whenText: String, val message: String)
private data class AgentConversationRow(val role: String, val content: String)

private data class AgentAdminState(
    val builtIns: Int = 0,
    val learned: Int = 0,
    val superseded: Int = 0,
    val associations: Int = 0,
    val calibrationVersion: Int = 0,
    val calibrationPending: Boolean = false,
    val calibrationRemaining: Int = 0,
    val tools: List<AgentToolRow> = emptyList(),
    val userModel: List<AgentAssociationRow> = emptyList(),
    val reports: List<AgentReportRow> = emptyList(),
    val events: List<AgentEventRow> = emptyList(),
    val conversation: List<AgentConversationRow> = emptyList(),
)

@Composable
fun PersonalAiAdminPanel(vm: VitalViewModel) {
    if (!vm.personalAgentEnabled) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { PersonalAiAdminRepository(context.applicationContext) }
    var state by remember { mutableStateOf<AgentAdminState?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var toolsOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var reportsOpen by remember { mutableStateOf(false) }
    var activityOpen by remember { mutableStateOf(false) }
    var conversationOpen by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            runCatching { repository.load() }
                .onSuccess { state = it; error = null }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }

    fun mutate(action: suspend PersonalAiAdminRepository.() -> Unit) {
        if (working) return
        scope.launch {
            working = true
            try {
                repository.action()
                state = repository.load()
                vm.refreshPersonalAgentState()
                error = null
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(vm.personalAgentEnabled, vm.busy) {
        if (vm.personalAgentEnabled && !vm.busy) refresh()
    }
    DisposableEffect(Unit) { onDispose { repository.close() } }

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ManageAccounts, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("Personal AI details", fontWeight = FontWeight.SemiBold)
                    Text(
                        state?.let { "${it.builtIns} built-in · ${it.learned} learned · ${it.associations} personal associations" }
                            ?: "Reading local agent state…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = ::refresh, enabled = !working && !vm.busy) {
                    Icon(Icons.Default.Refresh, "Refresh Personal AI state")
                }
            }

            state?.let { current ->
                if (current.calibrationPending) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Calibration in progress · ${current.calibrationRemaining} question(s) left") },
                        leadingIcon = { Icon(Icons.Default.QuestionAnswer, null, Modifier.size(18.dp)) },
                    )
                    Text(
                        "Answer the queued calibration questions in the AI screen. Calibration is marked complete only after every question is answered or skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AdminSectionHeader(
                    title = "Tool registry",
                    subtitle = "${current.tools.size} tools · ${current.superseded} superseded",
                    open = toolsOpen,
                    onClick = { toolsOpen = !toolsOpen },
                )
                AnimatedVisibility(toolsOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        current.tools.forEach { tool ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(tool.name, fontWeight = FontWeight.SemiBold)
                                            Text("${tool.kind} · ${tool.status} · used ${tool.uses}×", style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (tool.kind == "learned") {
                                            TextButton(
                                                onClick = { mutate { deleteTool(tool.name) } },
                                                enabled = !working && !vm.busy,
                                            ) { Text("Delete") }
                                        }
                                    }
                                    Text(tool.capability, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    if (tool.description.isNotBlank()) Text(tool.description, style = MaterialTheme.typography.bodySmall)
                                    if (tool.replacement.isNotBlank()) Text("Replacement: ${tool.replacement}", style = MaterialTheme.typography.labelSmall)
                                    ToolDetailExpander(tool.detail)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                AdminSectionHeader(
                    title = "Learned about you",
                    subtitle = "${current.userModel.size} saved associations",
                    open = modelOpen,
                    onClick = { modelOpen = !modelOpen },
                )
                AnimatedVisibility(modelOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current.userModel.isEmpty()) {
                            Text("No personal associations have been learned yet.", style = MaterialTheme.typography.bodySmall)
                        }
                        current.userModel.forEach { item ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(item.statement, fontWeight = FontWeight.Medium)
                                    Text(
                                        "Confidence ${item.confidence}% · ${item.evidenceCount} evidence item(s) · ${if (item.current) "current" else "expired"}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(
                                            onClick = { mutate { forgetAssociation(item.key) } },
                                            enabled = !working && !vm.busy,
                                        ) { Text("Forget") }
                                    }
                                    ToolDetailExpander(item.detail, label = "Association details")
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                AdminSectionHeader(
                    title = "Recent self-reports",
                    subtitle = "${current.reports.size} local subjective observations",
                    open = reportsOpen,
                    onClick = { reportsOpen = !reportsOpen },
                )
                AnimatedVisibility(reportsOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        current.reports.take(40).forEach { report ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(report.statement, style = MaterialTheme.typography.bodySmall)
                                Text("${report.whenText} · ${report.category}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (report.followUp.isNotBlank()) Text("Follow-up: ${report.followUp}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                HorizontalDivider()
                AdminSectionHeader(
                    title = "Agent activity",
                    subtitle = "${current.events.size} recent registry events",
                    open = activityOpen,
                    onClick = { activityOpen = !activityOpen },
                )
                AnimatedVisibility(activityOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        current.events.takeLast(40).reversed().forEach { event ->
                            Text("${event.whenText} · ${event.message}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                HorizontalDivider()
                AdminSectionHeader(
                    title = "Conversation context",
                    subtitle = "${current.conversation.size} recent messages kept separately from personal traits",
                    open = conversationOpen,
                    onClick = { conversationOpen = !conversationOpen },
                )
                AnimatedVisibility(conversationOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        current.conversation.forEach { message ->
                            Text(
                                "${if (message.role == "user") "You" else "VitalChronicle"}: ${message.content}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(
                            onClick = { mutate { clearConversation() } },
                            enabled = current.conversation.isNotEmpty() && !working && !vm.busy,
                        ) {
                            Icon(Icons.Default.DeleteSweep, null)
                            Spacer(Modifier.width(7.dp))
                            Text("Clear conversation context")
                        }
                    }
                }
            }

            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun AdminSectionHeader(title: String, subtitle: String, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
}

@Composable
private fun ToolDetailExpander(detail: String, label: String = "Technical details") {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) "Hide $label" else "Show $label")
    }
    AnimatedVisibility(open) {
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class PersonalAiAdminRepository(context: Context) {
    private val database = HealthDatabase(context)
    private val core = PythonCore()
    private val agentPath = context.getDatabasePath("vitalchronicle_agent.sqlite3").absolutePath

    suspend fun load(): AgentAdminState = withContext(Dispatchers.Default) {
        val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }
        parseState(JSONObject(core.personalAgentState(databasePath, agentPath)))
    }

    suspend fun deleteTool(name: String) = withContext(Dispatchers.Default) {
        val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }
        core.deletePersonalAgentLearnedTool(databasePath, agentPath, name)
        Unit
    }

    suspend fun forgetAssociation(key: String) = withContext(Dispatchers.Default) {
        val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }
        core.forgetPersonalAgentAssociation(databasePath, agentPath, key)
        Unit
    }

    suspend fun clearConversation() = withContext(Dispatchers.Default) {
        core.clearPersonalAgentConversation(agentPath)
        Unit
    }

    fun close() = database.close()

    private fun parseState(root: JSONObject): AgentAdminState = AgentAdminState(
        builtIns = root.optInt("built_in_tools"),
        learned = root.optInt("learned_tools"),
        superseded = root.optInt("superseded_tools"),
        associations = root.optInt("associations"),
        calibrationVersion = root.optInt("calibration_version"),
        calibrationPending = root.optBoolean("calibration_pending"),
        calibrationRemaining = root.optInt("calibration_remaining"),
        tools = root.optJSONArray("tools").objects().map { item ->
            AgentToolRow(
                name = item.optString("name"),
                kind = item.optString("kind"),
                capability = item.optString("capability"),
                status = item.optString("status"),
                uses = item.optInt("use_count"),
                replacement = item.optString("replacement"),
                description = item.optString("description"),
                detail = buildString {
                    append("Version: ${item.optInt("version", 1)}\n")
                    append("Confidence: ${"%.0f".format(item.optDouble("confidence", 0.0) * 100)}%\n")
                    append("Parameters: ${item.opt("parameters")}\n")
                    append("Outputs: ${item.opt("outputs")}\n")
                    append("Dependencies: ${item.opt("dependencies")}\n")
                    append("Pipeline: ${item.opt("pipeline")}\n")
                    append("Updated: ${item.optString("updated_at")}")
                },
            )
        },
        userModel = root.optJSONArray("user_model").objects().map { item ->
            AgentAssociationRow(
                key = item.optString("key"),
                statement = item.optString("statement"),
                confidence = (item.optDouble("confidence", 0.0) * 100).toInt(),
                evidenceCount = item.optInt("evidence_count"),
                current = item.optBoolean("is_current", true),
                detail = buildString {
                    append("Key: ${item.optString("key")}\n")
                    append("Source: ${item.optString("source")}\n")
                    append("Temporal scope: ${item.optString("temporal_scope")}\n")
                    append("Valid until: ${item.optString("valid_until").ifBlank { "—" }}\n")
                    append("Freshness: ${"%.0f".format(item.optDouble("freshness", 0.0) * 100)}%\n")
                    append("Evidence: ${item.opt("evidence")}")
                },
            )
        },
        reports = root.optJSONArray("self_reports").objects().map { item ->
            AgentReportRow(
                whenText = item.optString("observed_at").replace("T", " ").take(16),
                statement = item.optString("statement"),
                category = item.optString("category"),
                followUp = item.optJSONObject("context")?.optString("follow_up_answer").orEmpty(),
            )
        },
        events = root.optJSONArray("recent_tool_events").objects().map { item ->
            AgentEventRow(
                whenText = item.optString("created_at").replace("T", " ").take(16),
                message = item.optString("message"),
            )
        },
        conversation = root.optJSONArray("recent_conversation").objects().map { item ->
            AgentConversationRow(item.optString("role"), item.optString("content"))
        },
    )
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }
}
