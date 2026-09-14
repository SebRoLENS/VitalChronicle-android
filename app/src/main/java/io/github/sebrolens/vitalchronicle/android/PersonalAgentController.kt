package io.github.sebrolens.vitalchronicle.android

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PersonalAgentRunResult(
    val answer: String,
    val engineLabel: String,
    val toolCount: Int,
    val usedTools: List<String>,
    val recordConversation: Boolean = true,
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
        // Subjective statements such as "I'm not tired" are personal context,
        // not analysis requests. Capture them locally before starting any model,
        // which also avoids spending a Gemini Nano generation quota on a memory update.
        val selfReportRoute = runCatching {
            JSONObject(core.routePersonalAgentSelfReport(agentDatabasePath, question))
        }.getOrNull()
        if (selfReportRoute?.optBoolean("handled", false) == true) {
            val answer = selfReportRoute.optString("answer").trim()
            if (answer.isNotBlank()) {
                onStage("Personal AI · subjective report saved locally")
                val directResult = PersonalAgentRunResult(
                    answer = answer,
                    engineLabel = "Personal AI · local memory",
                    toolCount = 0,
                    usedTools = listOf("self-report saved locally"),
                )
                runCatching { core.recordPersonalAgentExchange(agentDatabasePath, question, answer) }
                return directResult
            }
        }

        val bootstrap = JSONObject(core.personalAgentBootstrap(databasePath, agentDatabasePath, question))
        val system = bootstrap.getString("system")
        val initialPrompt = bootstrap.getString("prompt")
        val maxSteps = bootstrap.optInt("max_steps", 15).coerceIn(1, 15)
        val toolCount = bootstrap.optInt("tool_count", 0)
        val factoryCandidate = bootstrap.optBoolean("factory_candidate", false)
        val factoryCapability = bootstrap.optString("factory_capability", "analysis.composed")
        val maxFactoryRepairs = bootstrap.optInt("max_factory_repairs", 3).coerceIn(1, 4)
        val maxRawSeriesProbes = bootstrap.optInt("max_raw_series_probes", 2).coerceIn(1, 4)
        val advertisedTools = jsonStrings(bootstrap.optJSONArray("tool_names"))
        val activeMonitors = jsonStrings(bootstrap.optJSONArray("monitor_names"))
        var nanoAvailabilityFailure: Throwable? = null

        suspend fun ollamaRun(): PersonalAgentRunResult? {
            val file = installedModel ?: return null
            return try {
                ollama.beginPersonalAgent(selectedModel, file, system, onStage)
                runAgentLoop(
                    databasePath = databasePath,
                    initialPrompt = initialPrompt,
                    maxSteps = maxSteps,
                    toolCount = toolCount,
                    engineLabel = "${selectedModel.id} · personal agent",
                    factoryCandidate = factoryCandidate,
                    factoryCapability = factoryCapability,
                    maxFactoryRepairs = maxFactoryRepairs,
                    maxRawSeriesProbes = maxRawSeriesProbes,
                    advertisedTools = advertisedTools,
                    activeMonitors = activeMonitors,
                    onStage = onStage,
                ) { prompt, tokens -> ollama.personalAgentTurn(prompt, tokens, onStage) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        }

        suspend fun nanoRun(): PersonalAgentRunResult? = try {
            runAgentLoop(
                databasePath = databasePath,
                initialPrompt = initialPrompt,
                maxSteps = maxSteps,
                toolCount = toolCount,
                engineLabel = "Gemini Nano · personal agent",
                factoryCandidate = factoryCandidate,
                factoryCapability = factoryCapability,
                maxFactoryRepairs = maxFactoryRepairs,
                maxRawSeriesProbes = maxRawSeriesProbes,
                advertisedTools = advertisedTools,
                activeMonitors = activeMonitors,
                onStage = onStage,
            ) { prompt, tokens -> nano.personalAgentTurn(system, prompt, tokens, onStage) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isNanoAvailabilityFailure(e)) nanoAvailabilityFailure = e
            null
        }

        val result = when (aiEngine) {
            AiEngine.DETERMINISTIC -> null
            AiEngine.OLLAMA_LOCAL -> ollamaRun()
            AiEngine.GEMINI_NANO -> nanoRun()
            AiEngine.AUTOMATIC -> if (preferDownloadedModel && installedModel != null) {
                ollamaRun() ?: nanoRun()
            } else {
                nanoRun() ?: ollamaRun()
            }
        }

        // AICore may reject a perfectly valid request because its on-device usage
        // quota is temporarily exhausted or because Android considers the request
        // background work. Do not call the same unavailable engine again through
        // the legacy planner and never expose the raw platform exception to users.
        val effectiveResult = result ?: nanoAvailabilityFailure?.let {
            onStage("Android local AI temporarily unavailable")
            PersonalAgentRunResult(
                answer = nanoUnavailableAnswer(question),
                engineLabel = "Gemini Nano · temporarily unavailable",
                toolCount = 0,
                usedTools = listOf("local AI temporarily unavailable"),
                recordConversation = false,
            )
        }
        if (effectiveResult != null && effectiveResult.recordConversation) {
            runCatching { core.recordPersonalAgentExchange(agentDatabasePath, question, effectiveResult.answer) }
        }
        return effectiveResult
    }

    private suspend fun runAgentLoop(
        databasePath: String,
        initialPrompt: String,
        maxSteps: Int,
        toolCount: Int,
        engineLabel: String,
        factoryCandidate: Boolean,
        factoryCapability: String,
        maxFactoryRepairs: Int,
        maxRawSeriesProbes: Int,
        advertisedTools: Set<String>,
        activeMonitors: Set<String>,
        onStage: (String) -> Unit,
        turn: suspend (String, Int) -> String,
    ): PersonalAgentRunResult? {
        var transcript = initialPrompt
        val evidenceLedger = mutableListOf<String>()
        val used = mutableListOf<String>()
        val knownTools = advertisedTools.toMutableSet()
        val cache = mutableMapOf<String, String>()
        var factoryRepairs = 0
        var rawSeriesProbes = 0
        var factoryResolved = false
        var factoryGate = false
        var factoryToolName: String? = null
        var factoryToolExecuted = false
        val knownMonitors = activeMonitors.toMutableSet()
        var monitoringOutcome: JSONObject? = null

        repeat(maxSteps) { index ->
            onStage("Personal agent · step ${index + 1}/$maxSteps")
            val raw = turn(transcript, ACTION_OUTPUT_TOKENS)
            val action = parseAction(raw) ?: return null
            when (action.optString("action")) {
                "final" -> {
                    if (factoryGate && !factoryResolved) {
                        transcript = buildStepPrompt(
                            initialPrompt,
                            evidenceLedger,
                            "Resolve the reusable capability gap with create_learned_tool. Capability: $factoryCapability",
                        )
                    } else if (factoryToolName != null && !factoryToolExecuted) {
                        transcript = buildStepPrompt(
                            initialPrompt,
                            evidenceLedger,
                            "Execute ${factoryToolName} with the current inputs before answering.",
                        )
                    } else {
                        val answer = action.optString("answer").trim()
                        if (answer.isBlank()) return null
                        val verified = verifiedPersistenceAnswer(
                            answer, monitoringOutcome, knownMonitors,
                            factoryResolved, factoryToolName, factoryToolExecuted,
                        )
                        return PersonalAgentRunResult(verified, engineLabel, toolCount, used)
                    }
                }

                "tool" -> {
                    val name = action.optString("name").trim()
                    if (name.isBlank()) return null
                    val arguments = action.optJSONObject("arguments") ?: JSONObject()

                    if (factoryGate && !factoryResolved && name != "create_learned_tool") {
                        transcript = buildStepPrompt(
                            initialPrompt,
                            evidenceLedger,
                            "Only create_learned_tool is allowed now; do not probe another raw metric.",
                        )
                        return@repeat
                    }

                    if (name !in knownTools) {
                        transcript = buildStepPrompt(
                            initialPrompt,
                            evidenceLedger,
                            "'$name' is unavailable. Use only advertised tools or a tool created in this session.",
                        )
                        return@repeat
                    }

                    onStage("Personal agent · $name")
                    var syntheticResult: String? = null
                    val metricArgument = arguments.optString("metric").trim()
                    if (name in METRIC_READER_TOOLS && metricArgument.isNotBlank() && metricArgument in knownTools) {
                        if (factoryCandidate && !factoryResolved) factoryGate = true
                        syntheticResult = JSONObject()
                            .put("status", "invalid_metric_identifier")
                            .put("tool_name", metricArgument)
                            .put("error", "A tool/function name is not a raw metric identifier. Call the semantic tool directly or compose it inside a learned tool. This does not mean the health data are missing.")
                            .toString()
                    }

                    if (syntheticResult == null && name == "get_metric_series" && factoryCandidate && !factoryResolved) {
                        rawSeriesProbes += 1
                        if (rawSeriesProbes > maxRawSeriesProbes) {
                            factoryGate = true
                            syntheticResult = JSONObject()
                                .put("status", "factory_decision_required")
                                .put("capability", factoryCapability)
                                .put("error", "Raw-series exploration budget reached. Create a safe reusable learned tool instead of guessing more raw metrics.")
                                .toString()
                        }
                    }

                    val cacheKey = "$name:${arguments}"
                    val cacheable = name !in MUTATING_AGENT_TOOLS
                    val result = syntheticResult
                        ?: if (cacheable && cache.containsKey(cacheKey)) {
                            cache.getValue(cacheKey)
                        } else {
                            core.executePersonalAgentTool(
                                databasePath, agentDatabasePath, name, arguments.toString()
                            ).also { if (cacheable) cache[cacheKey] = it }
                        }
                    used += name
                    if (name == factoryToolName) factoryToolExecuted = true

                    if (name == "create_learned_tool") {
                        val resultObject = runCatching { JSONObject(result) }.getOrNull()
                        when (resultObject?.optString("status")) {
                            "invalid_pipeline", "invalid_spec" -> {
                                factoryRepairs += 1
                                val error = resultObject.optString("error", "Learned-tool validation failed")
                                onStage("Tool Factory · repair $factoryRepairs/$maxFactoryRepairs · $error")
                                runCatching {
                                    core.logPersonalAgentFactoryEvent(
                                        agentDatabasePath,
                                        "tool_factory_repair",
                                        error,
                                        arguments.optString("name"),
                                        resultObject.toString(),
                                    )
                                }
                                factoryGate = factoryRepairs < maxFactoryRepairs
                                if (!factoryGate) {
                                    resultObject.put("repair_budget_exhausted", true)
                                    resultObject.put("instruction", "Do not call create_learned_tool again in this request. Answer from exact existing deterministic evidence and state any remaining limitation without substituting a proxy.")
                                }
                            }
                            "created", "reused" -> {
                                factoryResolved = true
                                factoryGate = false
                                factoryToolName = resultObject.optJSONObject("tool")?.optString("name")
                                    ?.takeIf { it.isNotBlank() }
                                factoryToolName?.let(knownTools::add)
                            }
                            else -> runCatching {
                                core.logPersonalAgentFactoryEvent(
                                    agentDatabasePath,
                                    "tool_factory_failure",
                                    resultObject?.optString("error", "Unexpected Tool Factory result")
                                        ?: "Invalid Tool Factory result",
                                    arguments.optString("name"),
                                    result,
                                )
                            }
                        }
                    }
                    if (name == "create_monitoring_rule") {
                        val resultObject = runCatching { JSONObject(result) }.getOrNull()
                        if (resultObject?.optString("status") in setOf("created", "updated")) {
                            monitoringOutcome = resultObject
                            resultObject?.optJSONObject("monitor")?.optString("name")
                                ?.takeIf { it.isNotBlank() }?.let(knownMonitors::add)
                            onStage("Personal AI · monitoring rule saved")
                        }
                    }

                    addEvidence(evidenceLedger, name, arguments, result)
                    if (factoryCandidate && !factoryResolved && factoryRepairs < maxFactoryRepairs && index + 1 >= FACTORY_GATE_AFTER_STEPS) {
                        factoryGate = true
                    }
                    val nextDirective = when {
                        factoryToolName != null && !factoryToolExecuted ->
                            "Execute ${factoryToolName} with the current inputs before answering."
                        factoryGate && !factoryResolved ->
                            "Call create_learned_tool now to resolve capability $factoryCapability."
                        else -> "Choose the next necessary action; do not repeat completed calls."
                    }
                    transcript = buildStepPrompt(initialPrompt, evidenceLedger, nextDirective)
                }

                else -> return null
            }
        }

        onStage("Personal agent · finalising from collected results…")
        val finalPrompt = buildStepPrompt(
            initialPrompt,
            evidenceLedger,
            "FINAL ANSWER NOW; no tools. Return {\"action\":\"final\",\"answer\":\"...\"}. " +
                "Answer result-first; mention only material values and limitations, without narrating tool use.",
        )
        val raw = turn(finalPrompt, FINAL_OUTPUT_TOKENS)
        val action = parseAction(raw)
        val answer = action?.takeIf { it.optString("action") == "final" }
            ?.optString("answer")?.trim().orEmpty()
        if (answer.isBlank()) return null
        val verified = verifiedPersistenceAnswer(
            answer, monitoringOutcome, knownMonitors,
            factoryResolved, factoryToolName, factoryToolExecuted,
        )
        return PersonalAgentRunResult(verified, engineLabel, toolCount, used)
    }

    private fun persistenceClaim(answer: String): String? {
        val text = answer.lowercase()
        if (Regex("(?:non|not|no)\\b[^.\\n]{0,40}(?:creat|salvat|attiv|registrat|impost)").containsMatchIn(text)) return null
        val created = Regex("creat|salvat|attiv|registrat|impost")
        val monitor = Regex("monitor\\w*|promemoria|check-in|reminder")
        val tool = Regex("strumento|tool")
        if (created.containsMatchIn(text) && monitor.containsMatchIn(text)) return "monitor"
        if (created.containsMatchIn(text) && tool.containsMatchIn(text)) return "tool"
        return null
    }

    private fun verifiedPersistenceAnswer(
        answer: String,
        monitoringOutcome: JSONObject?,
        knownMonitors: Set<String>,
        factoryResolved: Boolean,
        factoryToolName: String?,
        factoryToolExecuted: Boolean,
    ): String {
        val claim = persistenceClaim(answer) ?: return answer
        val folded = answer.lowercase()
        val italian = listOf(" il ", " lo ", " la ", " che ", " è ").any { " $folded ".contains(it) }
        if (claim == "monitor") {
            val status = monitoringOutcome?.optString("status")
            if (status == "created" || status == "updated") {
                val monitor = monitoringOutcome?.optJSONObject("monitor")
                val name = monitor?.optString("name").orEmpty().ifBlank { "monitoraggio" }
                val cadence = monitor?.optInt("cadence_days", 1) ?: 1
                return if (italian) {
                    "Monitoraggio `$name` salvato con cadenza di $cadence giorno/i. Registra le segnalazioni corrispondenti e propone domande nell'app quando VitalChronicle è aperto; non è un tool analitico né una notifica di sistema."
                } else {
                    "Monitoring rule `$name` was saved with a $cadence-day cadence. It records matching reports and queues questions while VitalChronicle is open; it is not an analysis tool or OS notification."
                }
            }
            val existing = knownMonitors.firstOrNull { folded.contains(it.lowercase()) }
            if (existing != null) {
                return if (italian) {
                    "Il monitoraggio `$existing` risulta già salvato e attivo. Propone domande nell'app quando VitalChronicle è aperto; non è un tool analitico né una notifica di sistema."
                } else {
                    "Monitoring rule `$existing` is already saved and active. It queues in-app questions while VitalChronicle is open; it is not an analysis tool or OS notification."
                }
            }
        }
        if (claim == "tool" && factoryResolved && !factoryToolName.isNullOrBlank()) {
            val state = if (factoryToolExecuted) "eseguito" else "non eseguito"
            return if (italian) {
                "Tool analitico `$factoryToolName` verificato e salvato; risulta $state in questa analisi."
            } else {
                "Analysis tool `$factoryToolName` was verified and saved; it was ${if (factoryToolExecuted) "executed" else "not executed"} in this analysis."
            }
        }
        return if (italian) {
            "Nessun nuovo tool o monitoraggio è stato salvato: il runtime non ha confermato la creazione. Le osservazioni personali restano separate come self-report."
        } else {
            "No new tool or monitoring rule was saved because creation was not confirmed by the runtime. Personal observations remain separate self-reports."
        }
    }

    private fun addEvidence(
        ledger: MutableList<String>,
        tool: String,
        arguments: JSONObject,
        result: String,
    ) {
        val resultValue = runCatching<Any> { JSONObject(result) }.getOrElse {
            runCatching<Any> { JSONArray(result) }.getOrElse { result }
        }
        var entry = JSONObject()
            .put("tool", tool)
            .put("arguments", arguments)
            .put("result", resultValue)
            .toString()
        if (entry.length > MAX_EVIDENCE_ENTRY_CHARS) {
            entry = JSONObject()
                .put("tool", tool)
                .put("arguments", arguments)
                .put("result_preview", result.take(MAX_EVIDENCE_ENTRY_CHARS - 700))
                .put("bounded", true)
                .toString()
        }
        ledger += entry
        while (ledger.size > MAX_EVIDENCE_ENTRIES) ledger.removeAt(0)
    }

    private fun buildStepPrompt(
        initialPrompt: String,
        ledger: List<String>,
        directive: String,
    ): String {
        val selected = mutableListOf<String>()
        var usedChars = 0
        for (entry in ledger.asReversed()) {
            if (selected.isNotEmpty() && usedChars + entry.length > MAX_EVIDENCE_LEDGER_CHARS) break
            selected.add(0, entry)
            usedChars += entry.length
        }
        val omitted = ledger.size - selected.size
        val evidence = if (selected.isEmpty()) "[]" else selected.joinToString(",", "[", "]")
        return buildString {
            append(initialPrompt)
            append("\n\nDETERMINISTIC EVIDENCE LEDGER (compact; omitted values are unknown):\n")
            append(evidence)
            if (omitted > 0) append("\nOlder evidence entries omitted: ").append(omitted)
            append("\n\nRUNTIME NEXT STEP:\n").append(directive)
            append("\nReturn exactly one JSON action.")
        }
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

    private fun jsonStrings(array: JSONArray?): Set<String> = buildSet {
        if (array == null) return@buildSet
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun isNanoAvailabilityFailure(error: Throwable): Boolean {
        val text = buildString {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 6) {
                append(' ').append(current.message.orEmpty()).append(' ')
                append(current.javaClass.simpleName)
                current = current.cause
                depth += 1
            }
        }.lowercase()
        return NANO_AVAILABILITY_MARKERS.any(text::contains)
    }

    private fun nanoUnavailableAnswer(question: String): String {
        val folded = question.lowercase()
        val italian = listOf(" mi ", " sono ", " sto ", " ho ", " oggi ", " perché", " come ", " sonno", " stanc", " allen").any {
            " $folded ".contains(it)
        }
        return if (italian) {
            "L'AI generativa locale di Android è temporaneamente indisponibile per un limite di utilizzo di AICore o perché Android non consente la generazione in background. I tuoi dati restano sul dispositivo. Riprova più tardi mantenendo VitalChronicle in primo piano, oppure seleziona un modello GGUF scaricato nelle Impostazioni."
        } else {
            "Android's local generative AI is temporarily unavailable because of an AICore usage limit or background-generation restriction. Your data stay on the device. Try again later with VitalChronicle in the foreground, or select a downloaded GGUF model in Settings."
        }
    }

    companion object {
        private const val ACTION_OUTPUT_TOKENS = 1400
        private const val FINAL_OUTPUT_TOKENS = 2400
        private const val MAX_EVIDENCE_ENTRIES = 8
        private const val MAX_EVIDENCE_ENTRY_CHARS = 5_000
        private const val MAX_EVIDENCE_LEDGER_CHARS = 18_000
        private const val FACTORY_GATE_AFTER_STEPS = 3

        private val NANO_AVAILABILITY_MARKERS = setOf(
            "usage quota",
            "out of usage quota",
            "quota exceeded",
            "resource_exhausted",
            "request cannot be processed",
            "disallowed background usage",
            "background usage",
        )
        private val METRIC_READER_TOOLS = setOf(
            "get_metric_series", "get_data_coverage", "get_daily_summary", "get_baseline",
            "get_missing_data", "detect_outliers", "detect_trends",
        )
        private val MUTATING_AGENT_TOOLS = setOf(
            "create_learned_tool", "create_monitoring_rule", "record_monitoring_observation",
            "ask_user_feedback", "record_user_feedback",
        )
    }
}
