#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected patch target not found in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_all_checked(path: Path, old: str, new: str, minimum: int = 1) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f"Expected at least {minimum} patch targets in {path}, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")


sync = ROOT / "scripts/sync_shared_core.py"
replace_once(
    sync,
    'CORE = ["analysis.py", "heart_rate_core.py", "ai_insights.py", "ai_pipeline.py", "ai_query_planner_core.py", "constants.py", "i18n.py", "utils.py"]',
    'CORE = ["analysis.py", "heart_rate_core.py", "ai_insights.py", "ai_pipeline.py", "ai_query_planner_core.py", "deterministic_detail_core.py", "agent_store.py", "agent_tools.py", "constants.py", "i18n.py", "utils.py"]',
)

bridge = ROOT / "app/src/main/python/android_agent_bridge.py"
bridge.write_text(r'''"""Android adapter for VitalChronicle's safe personal-health agent tools."""
from __future__ import annotations

import json
import sqlite3
from datetime import date, timedelta
from pathlib import Path
from typing import Any

from google_health_viewer.agent_store import AgentStore
from google_health_viewer.agent_tools import SafeToolExecutor
from mobile_bridge import SQLiteStore

MAX_AGENT_STEPS = 8
MAX_TOOL_RESULT_CHARS = 14000
CALIBRATION_VERSION = 1
THREAD_ID = "android-local"

AGENT_SYSTEM_PROMPT = """You are VitalChronicle's fully local personal health agent.
The only actions you may take are the safe deterministic tools listed in the user prompt.
Never calculate health statistics yourself when a deterministic tool can answer the question.
Check actual data coverage before comparisons; missing observations are never zero.
Prefer existing built-in or learned tools and search the registry before creating a learned tool.
Learned tools are declarative only: never request Python, shell, filesystem, browser, network,
or direct health-database write access. Subjective feedback may teach preferences or personal
associations, but never proves medical safety. Separate measured observations, deterministic
calculations, user-reported context, associations and possible explanations. Correlation is not
causation. Never diagnose disease, change treatment, or present wearable-derived estimates as
medical clearance. Readiness/load/resilience are transparent VitalChronicle estimates, not vendor
scores. State material coverage/confidence limits and answer in the user's language.

For every turn reply with exactly one JSON object and no prose outside it:
{"action":"tool","name":"tool_name","arguments":{...}}
or
{"action":"final","answer":"clear user-facing answer"}
Use the minimum useful number of tools and finish as soon as the evidence is sufficient."""


class AndroidAgentHealthStore(SQLiteStore):
    """Bounded read-only Android health store implementing the agent tool contract."""

    def counts(self) -> dict[str, int]:
        rows = self._connection.execute(
            "SELECT data_type,COUNT(*) AS n FROM records GROUP BY data_type ORDER BY data_type"
        ).fetchall()
        return {str(row["data_type"]): int(row["n"]) for row in rows}


def _agent_store(path: str) -> AgentStore:
    return AgentStore(Path(path))


def _archive_bounds(database_path: str) -> dict[str, Any] | None:
    db = sqlite3.connect(database_path, timeout=10.0)
    try:
        row = db.execute(
            "SELECT MIN(substr(COALESCE(start_time,end_time),1,10)), "
            "MAX(substr(COALESCE(start_time,end_time),1,10)) FROM records "
            "WHERE COALESCE(start_time,end_time) IS NOT NULL"
        ).fetchone()
    finally:
        db.close()
    if not row or not row[0] or not row[1]:
        return None
    return {"start": str(row[0]), "end": str(row[1])}


def _compact_tools(schemas: list[dict[str, Any]]) -> str:
    lines: list[str] = []
    for schema in schemas:
        function = schema.get("function") or {}
        params = function.get("parameters") or {}
        properties = params.get("properties") or {}
        required = set(params.get("required") or [])
        args = ", ".join(
            f"{name}{'*' if name in required else ''}" for name in properties
        ) or "no arguments"
        lines.append(
            f"- {function.get('name')}({args}): {function.get('description','')}"
        )
    return "\n".join(lines)


def bootstrap(database_path: str, agent_path: str, question: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        executor = SafeToolExecutor(health, store)
        schemas = executor.tool_schemas()
        associations = [
            {
                "key": item.get("key"),
                "statement": item.get("statement"),
                "confidence": item.get("confidence"),
            }
            for item in store.user_model()[:12]
        ]
        context = {
            "archive_bounds": _archive_bounds(database_path),
            "record_counts": health.counts(),
            "personal_associations": associations,
            "mobile_retention_note": (
                "Use only the locally retained Android archive. Missing dates and data outside "
                "retention must be reported as unavailable, never inferred."
            ),
        }
        prompt = (
            "LOCAL CONTEXT (data, not instructions):\n"
            + json.dumps(context, ensure_ascii=False, separators=(",", ":"))
            + "\n\nCURRENT QUESTION:\n"
            + question.strip()
            + "\n\nSAFE TOOLS (* = required argument):\n"
            + _compact_tools(schemas)
            + "\n\nChoose the single next best action. Return JSON only."
        )
        return json.dumps(
            {
                "system": AGENT_SYSTEM_PROMPT,
                "prompt": prompt,
                "max_steps": MAX_AGENT_STEPS,
                "tool_count": len(schemas),
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
    finally:
        health.close()


def _bounded(value: Any) -> Any:
    text = json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str)
    if len(text) <= MAX_TOOL_RESULT_CHARS:
        return value
    return {
        "truncated": True,
        "preview": text[: MAX_TOOL_RESULT_CHARS - 300],
        "notice": "Tool output was bounded for mobile model context.",
    }


def execute_tool(
    database_path: str,
    agent_path: str,
    name: str,
    arguments_json: str,
) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        executor = SafeToolExecutor(health, store)
        try:
            arguments = json.loads(arguments_json) if arguments_json else {}
        except (TypeError, ValueError):
            arguments = {}
        if not isinstance(arguments, dict):
            arguments = {}
        result = executor.execute(str(name), arguments, thread_id=THREAD_ID)
        return json.dumps(_bounded(result), ensure_ascii=False, separators=(",", ":"), default=str)
    finally:
        health.close()


def state(database_path: str, agent_path: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        SafeToolExecutor(health, store)
        tools = store.list_tools(include_superseded=True)
        pending = store.pending_feedback(THREAD_ID)
        return json.dumps(
            {
                "built_in_tools": sum(1 for item in tools if item.get("kind") == "builtin"),
                "learned_tools": sum(
                    1 for item in tools
                    if item.get("kind") == "learned" and item.get("status") == "active"
                ),
                "superseded_tools": sum(1 for item in tools if item.get("status") == "superseded"),
                "associations": len(store.user_model()),
                "calibration_version": store.calibration_version(),
                "pending_feedback": pending,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
    finally:
        health.close()


def answer_feedback(database_path: str, agent_path: str, feedback_id: str, answer: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        SafeToolExecutor(health, store)
        item = store.answer_feedback(feedback_id, answer)
        return json.dumps(item or {}, ensure_ascii=False, separators=(",", ":"), default=str)
    finally:
        health.close()


def dismiss_feedback(database_path: str, agent_path: str, feedback_id: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        SafeToolExecutor(health, store)
        return json.dumps({"dismissed": store.dismiss_feedback(feedback_id)})
    finally:
        health.close()


def calibrate(database_path: str, agent_path: str) -> str:
    bounds = _archive_bounds(database_path)
    if not bounds:
        return json.dumps({"available": False, "reason": "no_health_data"})
    right = date.fromisoformat(bounds["end"])
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        executor = SafeToolExecutor(health, store)
        snapshot: dict[str, Any] = {
            "available": True,
            "period": bounds,
            "readiness": executor.execute("calculate_readiness", {"end": right.isoformat()}),
            "training_status": executor.execute("calculate_training_status", {"end": right.isoformat()}),
            "resilience": executor.execute("calculate_resilience", {"end": right.isoformat()}),
            "sleep_regularity": executor.execute(
                "calculate_sleep_regularity",
                {"start": (right - timedelta(days=41)).isoformat(), "end": right.isoformat()},
            ),
            "sleep_debt": executor.execute("calculate_sleep_debt", {"end": right.isoformat()}),
            "workouts": executor.execute(
                "analyze_workout",
                {"start": (right - timedelta(days=55)).isoformat(), "end": right.isoformat()},
            ),
        }
        store.mark_calibrated(CALIBRATION_VERSION)
        return json.dumps(_bounded(snapshot), ensure_ascii=False, separators=(",", ":"), default=str)
    finally:
        health.close()


def reset_personalisation(database_path: str, agent_path: str) -> str:
    health = AndroidAgentHealthStore(database_path)
    store = _agent_store(agent_path)
    try:
        store.clear()
        SafeToolExecutor(health, store)
        return state(database_path, agent_path)
    finally:
        health.close()
''', encoding="utf-8")

# Kotlin bridge.
python_core = ROOT / "app/src/main/java/io/github/sebrolens/vitalchronicle/android/PythonCore.kt"
replace_once(
    python_core,
    '    private val plannerModule by lazy { Python.getInstance().getModule("ai_planner_bridge") }\n',
    '    private val plannerModule by lazy { Python.getInstance().getModule("ai_planner_bridge") }\n'
    '    private val agentModule by lazy { Python.getInstance().getModule("android_agent_bridge") }\n',
)
replace_once(
    python_core,
    '    fun compactEvidence(evidenceJson: String): String =\n        module.callAttr("compact_evidence", evidenceJson).toString()\n',
    '''    fun personalAgentBootstrap(databasePath: String, agentPath: String, question: String): String =
        agentModule.callAttr("bootstrap", databasePath, agentPath, question).toString()

    fun executePersonalAgentTool(
        databasePath: String,
        agentPath: String,
        name: String,
        argumentsJson: String,
    ): String = agentModule.callAttr(
        "execute_tool", databasePath, agentPath, name, argumentsJson
    ).toString()

    fun personalAgentState(databasePath: String, agentPath: String): String =
        agentModule.callAttr("state", databasePath, agentPath).toString()

    fun calibratePersonalAgent(databasePath: String, agentPath: String): String =
        agentModule.callAttr("calibrate", databasePath, agentPath).toString()

    fun answerPersonalAgentFeedback(
        databasePath: String,
        agentPath: String,
        feedbackId: String,
        answer: String,
    ): String = agentModule.callAttr(
        "answer_feedback", databasePath, agentPath, feedbackId, answer
    ).toString()

    fun dismissPersonalAgentFeedback(
        databasePath: String,
        agentPath: String,
        feedbackId: String,
    ): String = agentModule.callAttr(
        "dismiss_feedback", databasePath, agentPath, feedbackId
    ).toString()

    fun resetPersonalAgent(databasePath: String, agentPath: String): String =
        agentModule.callAttr("reset_personalisation", databasePath, agentPath).toString()

    fun compactEvidence(evidenceJson: String): String =
        module.callAttr("compact_evidence", evidenceJson).toString()
''',
)

# llama.cpp: retain one loaded chat session across agent tool turns.
ollama = ROOT / "app/src/main/java/io/github/sebrolens/vitalchronicle/android/OllamaOnDeviceEngine.kt"
replace_once(
    ollama,
    '    suspend fun answer(\n',
    '''    suspend fun beginPersonalAgent(
        model: OllamaModelSpec,
        modelFile: File,
        systemPrompt: String,
        onStage: (String) -> Unit,
    ) {
        require(modelFile.isFile) { "The selected Ollama model is not installed." }
        prepareFreshModel(model, modelFile, onStage, systemPrompt = systemPrompt)
    }

    suspend fun personalAgentTurn(
        prompt: String,
        maximumTokens: Int,
        onStage: (String) -> Unit,
    ): String {
        val raw = StringBuilder()
        onStage("Personal agent · selecting the next safe action…")
        engine.sendUserPrompt(prompt, maximumTokens.coerceIn(128, 768)).collect { raw.append(it) }
        val parsed = splitThinking(raw.toString())
        val text = parsed.answer.ifBlank { stripControlTags(raw.toString()).trim() }
        require(text.isNotBlank()) { "The local personal agent returned an empty response." }
        return text
    }

    suspend fun answer(
''',
)

# Gemini Nano: stateless JSON agent turn (controller carries compact transcript).
nano = ROOT / "app/src/main/java/io/github/sebrolens/vitalchronicle/android/GeminiNanoEngine.kt"
replace_once(
    nano,
    '    private suspend fun planWithModel(\n',
    '''    suspend fun personalAgentTurn(
        systemPrompt: String,
        prompt: String,
        maximumTokens: Int,
        progress: (String) -> Unit,
    ): String {
        val request = JSONObject()
            .put("system", systemPrompt)
            .put("prompt", prompt)
            .put("max_output_tokens", maximumTokens.coerceIn(128, 768))
            .toString()
        return plan(request, progress)
    }

    private suspend fun planWithModel(
''',
)

controller = ROOT / "app/src/main/java/io/github/sebrolens/vitalchronicle/android/PersonalAgentController.kt"
controller.write_text(r'''package io.github.sebrolens.vitalchronicle.android

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

data class PersonalAgentRunResult(
    val answer: String,
    val engineLabel: String,
    val toolCount: Int,
    val usedTools: List<String>,
)

class PersonalAgentController(
    private val core: PythonCore,
    private val nano: GeminiNanoEngine,
    private val ollama: OllamaOnDeviceEngine,
    private val agentDatabasePath: String,
) {
    suspend fun run(
        databasePath: String,
        question: String,
        aiEngine: AiEngine,
        selectedModel: OllamaModelSpec,
        installedModel: File?,
        preferDownloadedModel: Boolean,
        onStage: (String) -> Unit,
    ): PersonalAgentRunResult? {
        val bootstrap = JSONObject(core.personalAgentBootstrap(databasePath, agentDatabasePath, question))
        val system = bootstrap.getString("system")
        val initialPrompt = bootstrap.getString("prompt")
        val maxSteps = bootstrap.optInt("max_steps", 8).coerceIn(1, 8)
        val toolCount = bootstrap.optInt("tool_count", 0)

        suspend fun ollamaRun(): PersonalAgentRunResult? {
            val file = installedModel ?: return null
            return try {
                ollama.beginPersonalAgent(selectedModel, file, system, onStage)
                runOllamaLoop(
                    databasePath, initialPrompt, maxSteps, toolCount,
                    "${selectedModel.id} · personal agent", onStage,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        }

        suspend fun nanoRun(): PersonalAgentRunResult? = try {
            runNanoLoop(databasePath, system, initialPrompt, maxSteps, toolCount, onStage)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

        return when (aiEngine) {
            AiEngine.DETERMINISTIC -> null
            AiEngine.OLLAMA_LOCAL -> ollamaRun()
            AiEngine.GEMINI_NANO -> nanoRun()
            AiEngine.AUTOMATIC -> if (preferDownloadedModel && installedModel != null) {
                ollamaRun() ?: nanoRun()
            } else {
                nanoRun() ?: ollamaRun()
            }
        }
    }

    private suspend fun runOllamaLoop(
        databasePath: String,
        initialPrompt: String,
        maxSteps: Int,
        toolCount: Int,
        engineLabel: String,
        onStage: (String) -> Unit,
    ): PersonalAgentRunResult? {
        var prompt = initialPrompt
        val used = mutableListOf<String>()
        repeat(maxSteps) { index ->
            onStage("Personal agent · step ${index + 1}/$maxSteps")
            val raw = ollama.personalAgentTurn(prompt, 640, onStage)
            val action = parseAction(raw) ?: return null
            when (action.optString("action")) {
                "final" -> {
                    val answer = action.optString("answer").trim()
                    if (answer.isBlank()) return null
                    return PersonalAgentRunResult(answer, engineLabel, toolCount, used)
                }
                "tool" -> {
                    val name = action.optString("name").trim()
                    if (name.isBlank()) return null
                    val arguments = action.optJSONObject("arguments") ?: JSONObject()
                    onStage("Personal agent · $name")
                    val result = core.executePersonalAgentTool(
                        databasePath, agentDatabasePath, name, arguments.toString()
                    )
                    used += name
                    prompt = """
                        TOOL RESULT for $name:
                        $result

                        Continue from this result. Choose exactly one next action and return JSON only.
                    """.trimIndent()
                }
                else -> return null
            }
        }
        return null
    }

    private suspend fun runNanoLoop(
        databasePath: String,
        system: String,
        initialPrompt: String,
        maxSteps: Int,
        toolCount: Int,
        onStage: (String) -> Unit,
    ): PersonalAgentRunResult? {
        var transcript = initialPrompt
        val used = mutableListOf<String>()
        repeat(maxSteps) { index ->
            onStage("Gemini Nano personal agent · step ${index + 1}/$maxSteps")
            val raw = nano.personalAgentTurn(system, transcript, 640, onStage)
            val action = parseAction(raw) ?: return null
            when (action.optString("action")) {
                "final" -> {
                    val answer = action.optString("answer").trim()
                    if (answer.isBlank()) return null
                    return PersonalAgentRunResult(answer, "Gemini Nano · personal agent", toolCount, used)
                }
                "tool" -> {
                    val name = action.optString("name").trim()
                    if (name.isBlank()) return null
                    val arguments = action.optJSONObject("arguments") ?: JSONObject()
                    onStage("Personal agent · $name")
                    val result = core.executePersonalAgentTool(
                        databasePath, agentDatabasePath, name, arguments.toString()
                    )
                    used += name
                    transcript += "\n\nASSISTANT ACTION:\n${action}\n\nTOOL RESULT for $name:\n$result\n\nChoose one next action. JSON only."
                    if (transcript.length > MAX_NANO_TRANSCRIPT_CHARS) {
                        transcript = initialPrompt.take(14000) +
                            "\n\n[older agent turns compacted]\n\n" + transcript.takeLast(14000)
                    }
                }
                else -> return null
            }
        }
        return null
    }

    private fun parseAction(raw: String): JSONObject? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        runCatching { return JSONObject(cleaned) }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }

    companion object {
        private const val MAX_NANO_TRANSCRIPT_CHARS = 30_000
    }
}
''', encoding="utf-8")

# ViewModel integration: persisted enable switch, state, calibration/feedback and safe fallback.
vm = ROOT / "app/src/main/java/io/github/sebrolens/vitalchronicle/android/VitalViewModel.kt"
replace_once(
    vm,
    '    private val ollama = OllamaOnDeviceEngine(app)\n',
    '''    private val ollama = OllamaOnDeviceEngine(app)
    private val agentPrefs = app.getSharedPreferences("personal_ai", Context.MODE_PRIVATE)
    private val agentDatabasePath = app.getDatabasePath("vitalchronicle_agent.sqlite3").absolutePath
    private val personalAgent = PersonalAgentController(core, nano, ollama, agentDatabasePath)
''',
)
replace_once(
    vm,
    '    var advancedOpen by mutableStateOf(false)\n',
    '''    var advancedOpen by mutableStateOf(false)
    var personalAgentEnabled by mutableStateOf(agentPrefs.getBoolean("enabled", true)); private set
    var agentBuiltInTools by mutableStateOf(0); private set
    var agentLearnedTools by mutableStateOf(0); private set
    var agentAssociationCount by mutableStateOf(0); private set
    var agentCalibrationVersion by mutableStateOf(0); private set
    var agentFeedbackId by mutableStateOf<String?>(null); private set
    var agentFeedbackQuestion by mutableStateOf<String?>(null); private set
    var agentFeedbackReason by mutableStateOf<String?>(null); private set
''',
)
replace_once(
    vm,
    '        checkForAppUpdate()\n    }\n',
    '        checkForAppUpdate()\n        refreshPersonalAgentState()\n    }\n',
)
replace_once(
    vm,
    '    fun sync() {\n',
    '''    fun setPersonalAgentEnabled(enabled: Boolean) {
        personalAgentEnabled = enabled
        agentPrefs.edit().putBoolean("enabled", enabled).apply()
        if (enabled) refreshPersonalAgentState()
    }

    fun refreshPersonalAgentState() {
        viewModelScope.launch {
            runCatching {
                val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }
                val root = JSONObject(withContext(Dispatchers.Default) {
                    core.personalAgentState(databasePath, agentDatabasePath)
                })
                agentBuiltInTools = root.optInt("built_in_tools", 0)
                agentLearnedTools = root.optInt("learned_tools", 0)
                agentAssociationCount = root.optInt("associations", 0)
                agentCalibrationVersion = root.optInt("calibration_version", 0)
                val pending = root.optJSONObject("pending_feedback")
                agentFeedbackId = pending?.optString("feedback_id")?.takeIf { it.isNotBlank() }
                agentFeedbackQuestion = pending?.optString("question")?.takeIf { it.isNotBlank() }
                agentFeedbackReason = pending?.optString("reason")?.takeIf { it.isNotBlank() }
            }
        }
    }

    fun runPersonalAgentCalibration() {
        launchBusy("Calibrating personal baselines locally…") {
            val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }
            withContext(Dispatchers.Default) {
                core.calibratePersonalAgent(databasePath, agentDatabasePath)
            }
            refreshPersonalAgentState()
            status = "Personal AI calibration complete"
        }
    }

    fun resetPersonalAgent() {
        launchBusy("Resetting Personal AI state…") {
            val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }
            withContext(Dispatchers.Default) {
                core.resetPersonalAgent(databasePath, agentDatabasePath)
            }
            refreshPersonalAgentState()
            status = "Personal AI reset · health archive unchanged"
        }
    }

    fun answerPersonalAgentFeedback(answer: String) {
        val feedbackId = agentFeedbackId ?: return
        if (answer.isBlank()) return
        launchBusy("Saving local personalisation feedback…") {
            val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }
            withContext(Dispatchers.Default) {
                core.answerPersonalAgentFeedback(databasePath, agentDatabasePath, feedbackId, answer)
            }
            refreshPersonalAgentState()
            status = "Personalisation feedback saved locally"
        }
    }

    fun skipPersonalAgentFeedback() {
        val feedbackId = agentFeedbackId ?: return
        launchBusy("Skipping personalisation question…") {
            val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }
            withContext(Dispatchers.Default) {
                core.dismissPersonalAgentFeedback(databasePath, agentDatabasePath, feedbackId)
            }
            refreshPersonalAgentState()
            status = "Personalisation question skipped"
        }
    }

    fun sync() {
''',
)
replace_once(
    vm,
    '            val rawPlan = when (aiEngine) {\n',
    '''            if (personalAgentEnabled && aiEngine != AiEngine.DETERMINISTIC) {
                status = "Starting the local personal health agent…"
                val agentResult = try {
                    personalAgent.run(
                        databasePath = databasePath,
                        question = question,
                        aiEngine = aiEngine,
                        selectedModel = selectedModel,
                        installedModel = installedModel,
                        preferDownloadedModel = preferDownloadedModel,
                        onStage = { status = it },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                if (agentResult != null) {
                    aiAnswer = agentResult.answer
                    aiModelName = agentResult.engineLabel
                    analysisPlanSummary = if (agentResult.usedTools.isEmpty()) {
                        "Personal agent · ${agentResult.toolCount} safe tools available"
                    } else {
                        "Personal agent · ${agentResult.usedTools.distinct().joinToString(", ")}"
                    }
                    analysisPlanReason = "Deterministic tools selected iteratively from the local archive; missing data are not zero-filled."
                    status = "Personal agent analysis complete"
                    refreshPersonalAgentState()
                    return@launchBusy
                }
                status = "Personal agent unavailable · using the existing safe planner fallback…"
            }

            val rawPlan = when (aiEngine) {
''',
)

# Compose UI: visibility, feedback, controls and local-state reset.
main = ROOT / "app/src/main/java/io/github/sebrolens/vitalchronicle/android/MainActivity.kt"
replace_once(
    main,
    '    var thinkingOpen by remember { mutableStateOf(true) }\n',
    '    var thinkingOpen by remember { mutableStateOf(true) }\n    var feedbackAnswer by remember { mutableStateOf("") }\n',
)
replace_once(
    main,
    '        item { HeroCard("Private local AI", vm.aiModelName?.let{"Active local model · $it"}?:"Download an Ollama model or use Android\'s built-in Gemini Nano. Health evidence stays on this device.", Icons.Default.AutoAwesome) }\n',
    '''        item { HeroCard("Private local AI", vm.aiModelName?.let{"Active local model · $it"}?:"Download an Ollama model or use Android's built-in Gemini Nano. Health evidence stays on this device.", Icons.Default.AutoAwesome) }
        if (vm.personalAgentEnabled) item {
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Psychology,null,tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Personal health agent",fontWeight=FontWeight.SemiBold)
                        Text("${vm.agentBuiltInTools + vm.agentLearnedTools} safe local tools · ${vm.agentAssociationCount} learned associations",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
''',
)
replace_once(
    main,
    '        vm.analysisPlanSummary?.let { summary -> item {\n',
    '''        if (vm.personalAgentEnabled && vm.agentFeedbackQuestion != null) item {
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("A question that can improve personalisation",fontWeight=FontWeight.SemiBold)
                    Text(vm.agentFeedbackQuestion.orEmpty(),style=MaterialTheme.typography.bodyMedium)
                    vm.agentFeedbackReason?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    OutlinedTextField(
                        value=feedbackAnswer,
                        onValueChange={feedbackAnswer=it},
                        label={Text("Optional subjective context")},
                        modifier=Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={vm.answerPersonalAgentFeedback(feedbackAnswer);feedbackAnswer=""},enabled=feedbackAnswer.isNotBlank() && !vm.busy) { Text("Save locally") }
                        TextButton(onClick={vm.skipPersonalAgentFeedback();feedbackAnswer=""},enabled=!vm.busy) { Text("Skip") }
                    }
                }
            }
        }
        vm.analysisPlanSummary?.let { summary -> item {
''',
)
replace_once(
    main,
    '        item {\n            SectionTitle(\n                "Application updates",\n',
    '''        item { SectionTitle("Personal AI", "A bounded local agent can choose deterministic tools iteratively, learn safe reusable declarative tools and store optional feedback separately from your health archive.") }
        item { SettingCard(Icons.Default.Psychology,"Personal health agent",if(vm.personalAgentEnabled) "Enabled · ${vm.agentBuiltInTools} built-in + ${vm.agentLearnedTools} learned tools" else "Disabled · the existing AI planner remains available") {
            Switch(checked=vm.personalAgentEnabled,onCheckedChange=vm::setPersonalAgentEnabled)
        } }
        if (vm.personalAgentEnabled) item {
            Card { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Text("Local personalisation",fontWeight=FontWeight.SemiBold)
                Text("${vm.agentAssociationCount} learned associations · calibration ${if(vm.agentCalibrationVersion > 0) "complete" else "not run"}",style=MaterialTheme.typography.bodySmall)
                Text("The health database is read-only to the agent. Learned tools are restricted to VitalChronicle's declarative allow-list; no shell, browser, arbitrary code or cloud AI access is exposed.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick=vm::runPersonalAgentCalibration,enabled=!vm.busy && vm.counts.isNotEmpty()) { Text(if(vm.agentCalibrationVersion > 0) "Recalibrate" else "Calibrate") }
                    OutlinedButton(onClick=vm::resetPersonalAgent,enabled=!vm.busy) { Text("Reset Personal AI") }
                }
            } }
        }

        item {
            SectionTitle(
                "Application updates",
''',
)

# Version bump: this is a feature release, not another rollback build.
gradle = ROOT / "app/build.gradle.kts"
replace_once(
    gradle,
    '        // 0.5.1 is an emergency rollback: behavior matches the last stable 0.4.1 release.\n        versionCode = 26\n        versionName = "0.5.1"',
    '        // 0.6.0 adds the local Personal Health Agent while preserving the safe planner fallback.\n        versionCode = 27\n        versionName = "0.6.0"',
)

# CI test after every shared-core sync (build and signed-release jobs).
workflow = ROOT / ".github/workflows/android.yml"
replace_all_checked(
    workflow,
    '      - name: Test shared AI-first query planner\n        run: PYTHONPATH=app/src/main/python python scripts/test_ai_query_planner.py\n',
    '      - name: Test shared AI-first query planner\n        run: PYTHONPATH=app/src/main/python python scripts/test_ai_query_planner.py\n\n'
    '      - name: Test Personal Health Agent bridge\n        run: PYTHONPATH=app/src/main/python python scripts/test_android_agent_bridge.py\n',
    minimum=2,
)

test = ROOT / "scripts/test_android_agent_bridge.py"
test.write_text(r'''#!/usr/bin/env python3
from __future__ import annotations

import json
import sqlite3
import tempfile
from pathlib import Path

import android_agent_bridge as agent


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        health = root / "health_data.sqlite3"
        state = root / "vitalchronicle_agent.sqlite3"
        db = sqlite3.connect(health)
        db.executescript(
            """
            CREATE TABLE records (
              data_type TEXT NOT NULL, record_id TEXT NOT NULL, record_kind TEXT NOT NULL,
              start_time TEXT, end_time TEXT, source TEXT NOT NULL, payload TEXT NOT NULL,
              updated_at TEXT NOT NULL, PRIMARY KEY (data_type, record_id)
            );
            """
        )
        db.execute(
            "INSERT INTO records VALUES(?,?,?,?,?,?,?,?)",
            ("steps", "one", "data_point", "2026-09-08T08:00:00+00:00", None, "test", '{"count":5000}', "2026-09-08T09:00:00+00:00"),
        )
        db.commit(); db.close()

        bootstrap = json.loads(agent.bootstrap(str(health), str(state), "How active have I been?"))
        assert bootstrap["max_steps"] == 8
        assert bootstrap["tool_count"] >= 40
        assert "get_data_coverage" in bootstrap["prompt"]
        assert "filesystem" in bootstrap["system"]

        available = json.loads(agent.execute_tool(str(health), str(state), "get_available_metrics", "{}"))
        assert available["count"] == 1
        assert available["data_types"][0]["data_type"] == "steps"

        queued = json.loads(agent.execute_tool(
            str(health), str(state), "ask_user_feedback",
            json.dumps({
                "question": "How did this activity level feel?",
                "reason": "Improve personalisation",
                "learning_key": "activity_tolerance",
                "context": {"observation": "a high-step day"},
            }),
        ))
        assert queued["queued"] is True
        before = json.loads(agent.state(str(health), str(state)))
        assert before["pending_feedback"]["feedback_id"]
        agent.answer_feedback(
            str(health), str(state), before["pending_feedback"]["feedback_id"], "comfortable"
        )
        after = json.loads(agent.state(str(health), str(state)))
        assert after["associations"] == 1
        assert after["pending_feedback"] is None

        reset = json.loads(agent.reset_personalisation(str(health), str(state)))
        assert reset["associations"] == 0
        assert reset["learned_tools"] == 0
        assert reset["built_in_tools"] >= 40

    print("Android Personal Health Agent bridge OK")


if __name__ == "__main__":
    main()
''', encoding="utf-8")

# README architecture/privacy notes.
readme = ROOT / "README.md"
replace_once(
    readme,
    '## Automatic updates\n',
    '''## Personal Health Agent

Version 0.6 adds the same safe Personal Health Agent concept introduced in VitalChronicle desktop. The Android agent can iteratively select deterministic health tools, check metric-specific coverage, compare periods, inspect sleep/recovery/training evidence, reuse or create allow-listed declarative learned tools, and use optional user feedback for local personalisation.

The mobile runtime uses a strict JSON action protocol so the agent works with both the bundled on-device llama.cpp chat runtime and Gemini Nano without requiring native function-calling support. The model never receives shell, browser, arbitrary-code, network, filesystem or direct health-database-write tools. Health data are read-only to the agent, while learned tools, feedback, calibration metadata and personal associations live separately in the app-private `vitalchronicle_agent.sqlite3` database. If the agent cannot complete its bounded tool loop, VitalChronicle automatically falls back to the existing shared AI-first deterministic planner.

The shared desktop `agent_store.py`, `agent_tools.py` and deterministic detail core are synchronized during CI alongside the existing analysis core, keeping the calculations and safety boundaries aligned across desktop and Android.

## Automatic updates
''',
)

print("Personal Health Agent Android integration applied")
