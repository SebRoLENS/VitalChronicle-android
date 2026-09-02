#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: str, start_marker: str, end_marker: str, replacement: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    p.write_text(text[:start] + replacement + text[end:], encoding="utf-8")


# 1) Keep the planner itself in the canonical shared core synced from desktop.
sync = Path("scripts/sync_shared_core.py")
text = sync.read_text(encoding="utf-8")
old_core = 'CORE = ["__init__.py", "analysis.py", "ai_insights.py", "ai_pipeline.py", "constants.py", "i18n.py", "utils.py"]'
new_core = 'CORE = ["__init__.py", "analysis.py", "ai_insights.py", "ai_pipeline.py", "ai_query_planner_core.py", "constants.py", "i18n.py", "utils.py"]'
if old_core not in text:
    raise SystemExit("Shared-core file list changed unexpectedly")
sync.write_text(text.replace(old_core, new_core, 1), encoding="utf-8")

# 2) Android-only SQLite/Chaquopy bridge around the platform-neutral planner core.
Path("app/src/main/python/ai_planner_bridge.py").write_text(r'''"""Thin Android bridge to VitalChronicle's shared AI-first query planner core."""
from __future__ import annotations

import json
import sqlite3
from typing import Any

from google_health_viewer.ai_pipeline import ensure_compact_evidence
from google_health_viewer.ai_query_planner_core import (
    PLANNER_OUTPUT_TOKENS,
    _parse_json_object,
    _planner_messages,
    build_data_catalog,
    build_planned_snapshot,
    fallback_data_plan,
    resolve_data_plan,
)
from mobile_bridge import SQLiteStore


class AndroidPlannerStore(SQLiteStore):
    """SQLiteStore plus the connection factory expected by the shared planner catalogue."""

    def __init__(self, database_path: str):
        self._database_path = database_path
        super().__init__(database_path)

    def _connect(self):
        connection = sqlite3.connect(self._database_path, timeout=10.0)
        connection.row_factory = sqlite3.Row
        return connection


def catalog_from_sqlite(database_path: str) -> str:
    store = AndroidPlannerStore(database_path)
    try:
        return json.dumps(build_data_catalog(store), ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()


def planner_request(catalog_json: str, question: str) -> str:
    catalog = json.loads(catalog_json)
    messages = _planner_messages(catalog, question, [])
    return json.dumps(
        {
            "system": messages[0]["content"],
            "prompt": messages[1]["content"],
            "max_output_tokens": PLANNER_OUTPUT_TOKENS,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )


def resolve_plan(catalog_json: str, raw_plan: str) -> str:
    catalog = json.loads(catalog_json)
    try:
        plan = resolve_data_plan(_parse_json_object(raw_plan), catalog)
    except Exception:
        plan = fallback_data_plan(catalog, reason="android_model_planner_fallback")
    return json.dumps(plan, ensure_ascii=False, separators=(",", ":"))


def evidence_from_sqlite(database_path: str, plan_json: str) -> str:
    plan = json.loads(plan_json)
    store = AndroidPlannerStore(database_path)
    try:
        snapshot, _period = build_planned_snapshot(store, plan)
        packet = ensure_compact_evidence(snapshot)
        packet["retrieval"] = {
            "mode": "ai_planned",
            "planner_version": plan.get("planner_version"),
            "selected_data_types": plan.get("data_types") or [],
            "days": plan.get("days"),
            "start_date": plan.get("start_date"),
            "end_date": plan.get("end_date"),
            "detail": plan.get("detail"),
            "reason": plan.get("reason"),
        }
        return json.dumps(packet, ensure_ascii=False, separators=(",", ":"))
    finally:
        store.close()
''', encoding="utf-8")

# 3) Expose planner bridge through the existing PythonCore JVM boundary.
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/PythonCore.kt",
    '    private val nanoRouterModule by lazy { Python.getInstance().getModule("nano_router") }\n',
    '    private val nanoRouterModule by lazy { Python.getInstance().getModule("nano_router") }\n'
    '    private val plannerModule by lazy { Python.getInstance().getModule("ai_planner_bridge") }\n',
)
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/PythonCore.kt",
    '    fun compactEvidence(evidenceJson: String): String =\n        module.callAttr("compact_evidence", evidenceJson).toString()\n',
    '''    fun aiPlannerCatalogFromDatabase(databasePath: String): String =
        plannerModule.callAttr("catalog_from_sqlite", databasePath).toString()

    fun aiPlannerRequest(catalogJson: String, question: String): String =
        plannerModule.callAttr("planner_request", catalogJson, question).toString()

    fun resolveAiPlan(catalogJson: String, rawPlan: String): String =
        plannerModule.callAttr("resolve_plan", catalogJson, rawPlan).toString()

    fun plannedEvidenceFromDatabase(databasePath: String, planJson: String): String =
        plannerModule.callAttr("evidence_from_sqlite", databasePath, planJson).toString()

    fun compactEvidence(evidenceJson: String): String =
        module.callAttr("compact_evidence", evidenceJson).toString()
''',
)

# 4) Gemini Nano: add a cheap JSON-only planning pass using the exact shared planner prompt.
gemini = "app/src/main/java/io/github/sebrolens/vitalchronicle/android/GeminiNanoEngine.kt"
replace_once(
    gemini,
    '    suspend fun answer(question: String, evidenceJson: String, progress: (String) -> Unit): AiResult {\n',
    '''    suspend fun plan(plannerRequestJson: String, progress: (String) -> Unit): String {
        val selection = selectModel(allowDownload = true)
        return try {
            planWithModel(selection, plannerRequestJson, progress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!selection.accurate) throw e
            progress("FULL profile unavailable · retrying compatible Gemini Nano planner…")
            planWithModel(
                ModelSelection(compatibleModel, "Accurate local · compatible", accurate = false),
                plannerRequestJson,
                progress,
            )
        }
    }

    private suspend fun planWithModel(
        selection: ModelSelection,
        plannerRequestJson: String,
        progress: (String) -> Unit,
    ): String {
        val model = selection.model
        val name = prepare(selection, progress)
        val planner = JSONObject(plannerRequestJson)
        val system = planner.getString("system")
        val prompt = planner.getString("prompt")
        val maxOutput = planner.optInt("max_output_tokens", 560).coerceIn(128, 768)
        progress("$name · ${selection.profile} · choosing health data and time range…")
        val request = generateContentRequest(SystemInstruction(system), TextPart(prompt)) {
            temperature = 0.0f
            maxOutputTokens = maxOutput
            candidateCount = 1
            enableThinking = false
        }
        runCatching { model.warmup() }
        val response = model.generateContent(request)
        val text = response.candidates.firstOrNull()?.text?.trim().orEmpty()
        if (text.isBlank()) error("Gemini Nano planner returned an empty response.")
        return text
    }

    suspend fun answer(question: String, evidenceJson: String, progress: (String) -> Unit): AiResult {
''',
)

# 5) llama.cpp/Ollama path: same shared planner prompt, then normal final answer pass.
ollama = "app/src/main/java/io/github/sebrolens/vitalchronicle/android/OllamaOnDeviceEngine.kt"
replace_once(ollama, 'import java.io.File\n', 'import org.json.JSONObject\nimport java.io.File\n')
replace_once(
    ollama,
    '    suspend fun answer(\n',
    '''    suspend fun plan(
        model: OllamaModelSpec,
        modelFile: File,
        plannerRequestJson: String,
        onStage: (String) -> Unit,
    ): String {
        require(modelFile.isFile) { "The selected Ollama model is not installed." }
        val planner = JSONObject(plannerRequestJson)
        val system = planner.getString("system")
        val prompt = planner.getString("prompt")
        val maximumTokens = planner.optInt("max_output_tokens", 560).coerceIn(128, 768)
        prepareFreshModel(model, modelFile, onStage, systemPrompt = system)
        val raw = StringBuilder()
        onStage("${model.id} · choosing health data and time range…")
        engine.sendUserPrompt(prompt, maximumTokens).collect { raw.append(it) }
        val parsed = splitThinking(raw.toString())
        val text = parsed.answer.ifBlank { stripControlTags(raw.toString()).trim() }
        require(text.isNotBlank()) { "The local model planner returned an empty response." }
        return text
    }

    suspend fun answer(
''',
)
replace_once(
    ollama,
    '        onStage: (String) -> Unit,\n    ) {\n',
    '        onStage: (String) -> Unit,\n        systemPrompt: String = SYSTEM_PROMPT,\n    ) {\n',
)
replace_once(ollama, '        engine.setSystemPrompt(SYSTEM_PROMPT)\n', '        engine.setSystemPrompt(systemPrompt)\n')

# 6) ViewModel orchestration: planner -> validated Python extraction -> final model answer.
vm = "app/src/main/java/io/github/sebrolens/vitalchronicle/android/VitalViewModel.kt"
replace_once(
    vm,
    '    var analysisDays by mutableStateOf(28)\n    var advancedOpen by mutableStateOf(false)\n',
    '    var analysisPlanSummary by mutableStateOf<String?>(null); private set\n'
    '    var analysisPlanReason by mutableStateOf<String?>(null); private set\n'
    '    var advancedOpen by mutableStateOf(false)\n',
)

new_analyse = r'''    fun analyse(question: String) {
        if (question.isBlank()) return
        resetStreamingAnswer()
        analysisJob = launchBusy("Letting the local AI choose the evidence it needs…") {
            val databasePath = withContext(Dispatchers.IO) { database.readableDatabase.path }
            val catalog = withContext(Dispatchers.Default) {
                core.aiPlannerCatalogFromDatabase(databasePath)
            }
            val plannerRequest = withContext(Dispatchers.Default) {
                core.aiPlannerRequest(catalog, question)
            }
            val selectedModel = ollamaCatalog.first { it.id == selectedOllamaModelId }
            val installedModel = ollamaModels.installedFile(selectedModel)
            val preferDownloadedModel = aiEngine == AiEngine.OLLAMA_LOCAL ||
                (
                    aiEngine == AiEngine.AUTOMATIC &&
                        installedModel != null &&
                        (hardware.ggufHardwareAccelerated || !nanoCapability.supported)
                )

            val rawPlan = when (aiEngine) {
                AiEngine.DETERMINISTIC -> ""
                AiEngine.OLLAMA_LOCAL -> {
                    if (installedModel == null) {
                        error("Download and select an Ollama model from Settings before using this engine.")
                    }
                    try {
                        ollama.plan(selectedModel, installedModel, plannerRequest) { status = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (e is VirtualMachineError || e is ThreadDeath) throw e
                        status = "Planner unavailable · using the safe shared-core fallback…"
                        ""
                    }
                }
                AiEngine.GEMINI_NANO -> {
                    try {
                        nano.plan(plannerRequest) { status = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        status = "Planner unavailable · using the safe shared-core fallback…"
                        ""
                    }
                }
                AiEngine.AUTOMATIC -> {
                    if (preferDownloadedModel && installedModel != null) {
                        try {
                            ollama.plan(selectedModel, installedModel, plannerRequest) { status = it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            if (e is VirtualMachineError || e is ThreadDeath) throw e
                            status = "Downloaded-model planner unavailable · trying Android AI planner…"
                            try {
                                nano.plan(plannerRequest) { status = it }
                            } catch (_: Throwable) {
                                ""
                            }
                        }
                    } else {
                        try {
                            nano.plan(plannerRequest) { status = it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            if (installedModel != null) {
                                try {
                                    ollama.plan(selectedModel, installedModel, plannerRequest) { status = it }
                                } catch (_: Throwable) {
                                    ""
                                }
                            } else ""
                        }
                    }
                }
            }

            status = "Python is validating the AI request and extracting only those data…"
            val planJson = withContext(Dispatchers.Default) {
                core.resolveAiPlan(catalog, rawPlan)
            }
            updateAnalysisPlan(planJson)
            val modelEvidence = withContext(Dispatchers.Default) {
                core.plannedEvidenceFromDatabase(databasePath, planJson)
            }

            if (aiEngine == AiEngine.DETERMINISTIC) {
                aiAnswer = deterministicSummary(modelEvidence)
                status = "Deterministic planned analysis complete"
                return@launchBusy
            }

            if (preferDownloadedModel) {
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
                    analyseWithNanoOrFallback(e, question, modelEvidence)
                }
            } else {
                analyseWithNanoOrFallback(null, question, modelEvidence)
            }
        }
    }

'''
replace_between(vm, '    fun analyse(question: String) {\n', '    fun cancelAnalysis() {\n', new_analyse)

helpers_start = '    private suspend fun analyseWithNanoOrFallback(\n'
helpers_end = '    private fun resetStreamingAnswer() {\n'
new_helpers = r'''    private suspend fun analyseWithNanoOrFallback(
        ollamaFailure: Throwable?,
        question: String,
        evidence: String,
    ) {
        try {
            val result = nano.answer(question, evidence) { status = it }
            aiAnswer = result.answer
            aiModelName = result.model
            status = "Analysis complete · ${result.engine}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            fallbackAfterNano(e, ollamaFailure, question, evidence)
        } catch (e: Exception) {
            fallbackAfterNano(e, ollamaFailure, question, evidence)
        }
    }

    private suspend fun fallbackAfterNano(
        nanoFailure: Throwable,
        earlierOllamaFailure: Throwable?,
        question: String,
        evidence: String,
    ) {
        if (aiEngine == AiEngine.GEMINI_NANO) throw nanoFailure
        if (aiEngine == AiEngine.AUTOMATIC && earlierOllamaFailure == null) {
            val selected = ollamaCatalog.firstOrNull { it.id == selectedOllamaModelId }
            val installed = selected?.let(ollamaModels::installedFile)
            if (selected != null && installed != null) {
                status = "Android AI unavailable · trying ${selected.id} on ${hardware.ggufAccelerationBackend}…"
                try {
                    analyseWithOllama(selected, installed, question, evidence)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (e is VirtualMachineError || e is ThreadDeath) throw e
                    useDeterministicFallback(e, nanoFailure, evidence)
                    return
                }
            }
        }
        useDeterministicFallback(nanoFailure, earlierOllamaFailure, evidence)
    }

    private fun useDeterministicFallback(
        failure: Throwable,
        earlierFailure: Throwable?,
        evidence: String,
    ) {
        if (aiEngine == AiEngine.GEMINI_NANO) throw failure
        aiAnswer = deterministicSummary(evidence) +
            "\n\nLocal generative AI is not available: " +
            listOfNotNull(earlierFailure, failure)
                .joinToString("; ") { it.message ?: it.javaClass.simpleName }
        status = "Deterministic fallback used"
    }

    private fun updateAnalysisPlan(planJson: String) {
        val plan = JSONObject(planJson)
        val labels = plan.optJSONArray("data_labels")
        val selected = buildList {
            if (labels != null) {
                for (i in 0 until labels.length()) {
                    labels.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        val days = plan.optInt("days", 0)
        analysisPlanSummary = buildString {
            if (days > 0) append(days).append(" days")
            if (selected.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(selected.joinToString(", "))
            }
        }.ifBlank { "AI-selected local evidence" }
        analysisPlanReason = plan.optString("reason").takeIf { it.isNotBlank() }
    }

'''
replace_between(vm, helpers_start, helpers_end, new_helpers)
replace_once(
    vm,
    '        aiTokensPerSecond = 0.0\n',
    '        aiTokensPerSecond = 0.0\n        analysisPlanSummary = null\n        analysisPlanReason = null\n',
)
replace_once(
    vm,
    '        val coverage = root.optJSONObject("requested_interval_coverage")\n        val insights = root.optJSONArray("candidate_insights")\n',
    '        val coverage = root.optJSONObject("requested_interval_coverage") ?: root.optJSONObject("coverage")\n'
    '        val insights = root.optJSONArray("candidate_insights") ?: root.optJSONArray("strongest_evidence")\n',
)

# 7) Remove the manual 7/28/90 AI selector; show what the AI actually requested instead.
main = "app/src/main/java/io/github/sebrolens/vitalchronicle/android/MainActivity.kt"
replace_once(
    main,
    '        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(7,28,90).forEach { d -> FilterChip(selected=vm.analysisDays==d,onClick={vm.analysisDays=d},label={Text("${d}d")}) } } }\n',
    '',
)
replace_once(
    main,
    '        if(vm.aiGeneratedTokens > 0) item {\n',
    '''        vm.analysisPlanSummary?.let { summary -> item {
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("AI-selected evidence", fontWeight=FontWeight.SemiBold)
                    Text(summary, style=MaterialTheme.typography.bodySmall)
                    vm.analysisPlanReason?.let { reason ->
                        Spacer(Modifier.height(3.dp))
                        Text(reason, style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } }
        if(vm.aiGeneratedTokens > 0) item {
''',
)

# 8) Feature release per project versioning rule: x.y.x for features.
replace_once(
    "app/build.gradle.kts",
    '        // 0.3.5 restores shared-core compatibility for daily five-minute heart-rate rollups.\n        versionCode = 22\n        versionName = "0.3.5"\n',
    '        // 0.4.0 adds shared AI-first query planning and dynamic evidence windows.\n        versionCode = 23\n        versionName = "0.4.0"\n',
)

# 9) Regression test exercises metadata-only catalogue, shared plan validation and selective evidence.
Path("scripts/test_ai_query_planner.py").write_text(r'''#!/usr/bin/env python3
from __future__ import annotations

import json
import sqlite3
import tempfile
from pathlib import Path

from ai_planner_bridge import catalog_from_sqlite, evidence_from_sqlite, planner_request, resolve_plan


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        db_path = str(Path(tmp) / "health.sqlite3")
        db = sqlite3.connect(db_path)
        db.executescript("""
            CREATE TABLE records (
              data_type TEXT NOT NULL,
              record_id TEXT NOT NULL,
              record_kind TEXT NOT NULL DEFAULT 'data_point',
              start_time TEXT,
              end_time TEXT,
              source TEXT NOT NULL DEFAULT '',
              payload TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              PRIMARY KEY (data_type, record_id)
            );
        """)
        rows = [
            ("sleep", "s1", "data_point", "2026-08-31T22:00:00+00:00", "2026-09-01T06:00:00+00:00", "", '{"sleep":{"sleepSummary":{"minutesAsleep":"450","secret":12345}}}', "2026-09-02T00:00:00+00:00"),
            ("daily-heart-rate-variability", "h1", "daily_rollup", "2026-09-01T00:00:00+00:00", "2026-09-01T00:00:00+00:00", "", '{"dailyHeartRateVariability":{"averageHeartRateVariabilityMilliseconds":48.0}}', "2026-09-02T00:00:00+00:00"),
            ("steps", "p1", "data_point", "2026-09-01T09:00:00+00:00", "2026-09-01T09:05:00+00:00", "", '{"steps":{"count":800}}', "2026-09-02T00:00:00+00:00"),
        ]
        db.executemany("INSERT INTO records VALUES (?,?,?,?,?,?,?,?)", rows)
        db.commit(); db.close()

        catalog_json = catalog_from_sqlite(db_path)
        assert "12345" not in catalog_json and "secret" not in catalog_json
        catalog = json.loads(catalog_json)
        assert {row["key"] for row in catalog["datasets"]} == {"sleep", "daily-heart-rate-variability", "steps"}

        request = json.loads(planner_request(catalog_json, "Is my sleep related to HRV over the last month?"))
        assert "available_local_data" in request["prompt"]
        assert "12345" not in request["prompt"]

        raw = json.dumps({
            "data_types": ["sleep", "daily-heart-rate-variability"],
            "window": "last_n_days",
            "days": 30,
            "detail": "daily",
            "reason": "Need matched sleep and HRV data",
        })
        plan_json = resolve_plan(catalog_json, raw)
        plan = json.loads(plan_json)
        assert plan["data_types"] == ["sleep", "daily-heart-rate-variability"]
        assert "steps" not in plan["data_types"]

        evidence = json.loads(evidence_from_sqlite(db_path, plan_json))
        assert evidence["retrieval"]["mode"] == "ai_planned"
        assert evidence["retrieval"]["selected_data_types"] == ["sleep", "daily-heart-rate-variability"]
        assert "activity" not in evidence.get("domains", {})

    print("AI-first shared query planner regression test passed")


if __name__ == "__main__":
    main()
''', encoding="utf-8")

# Run the planner regression immediately after syncing the canonical core in CI.
workflow = Path(".github/workflows/android.yml")
workflow_text = workflow.read_text(encoding="utf-8")
needle = '      - name: Test multilingual Nano router\n        run: PYTHONPATH=app/src/main/python python scripts/test_nano_router.py\n'
insert = needle + '\n      - name: Test shared AI-first query planner\n        run: PYTHONPATH=app/src/main/python python scripts/test_ai_query_planner.py\n'
if workflow_text.count(needle) != 2:
    raise SystemExit("Expected Nano-router test block twice in Android workflow")
workflow.write_text(workflow_text.replace(needle, insert), encoding="utf-8")

# The one-shot patch helper removes itself from main.
for path in (
    Path("scripts/apply-ai-first-query-planner.py"),
    Path(".github/workflows/apply-ai-first-query-planner.yml"),
):
    if path.exists():
        path.unlink()

print("Android AI-first shared query planner integration applied.")
