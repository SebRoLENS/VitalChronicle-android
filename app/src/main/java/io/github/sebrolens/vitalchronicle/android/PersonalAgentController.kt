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
        val maxSteps = bootstrap.optInt("max_steps", 15).coerceIn(1, 15)
        val toolCount = bootstrap.optInt("tool_count", 0)
        val factoryCandidate = bootstrap.optBoolean("factory_candidate", false)
        val factoryCapability = bootstrap.optString("factory_capability", "analysis.composed")
        val maxFactoryRepairs = bootstrap.optInt("max_factory_repairs", 3).coerceIn(1, 4)
        val maxRawSeriesProbes = bootstrap.optInt("max_raw_series_probes", 2).coerceIn(1, 4)
        val advertisedTools = jsonStrings(bootstrap.optJSONArray("tool_names"))

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
                onStage = onStage,
            ) { prompt, tokens -> nano.personalAgentTurn(system, prompt, tokens, onStage) }
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
        onStage: (String) -> Unit,
        turn: suspend (String, Int) -> String,
    ): PersonalAgentRunResult? {
        var transcript = initialPrompt
        val used = mutableListOf<String>()
        val knownTools = advertisedTools.toMutableSet()
        val cache = mutableMapOf<String, String>()
        var factoryRepairs = 0
        var rawSeriesProbes = 0
        var factoryResolved = false
        var factoryGate = false

        repeat(maxSteps) { index ->
            onStage("Personal agent · step ${index + 1}/$maxSteps")
            val raw = turn(transcript, ACTION_OUTPUT_TOKENS)
            val action = parseAction(raw) ?: return null
            when (action.optString("action")) {
                "final" -> {
                    if (factoryGate && !factoryResolved) {
                        transcript = appendTurn(
                            transcript,
                            action,
                            "RUNTIME TOOL FACTORY GATE: the direct answer was rejected. Resolve the reusable capability gap first by calling create_learned_tool, or repair that same tool if validation failed. Capability: $factoryCapability",
                            initialPrompt,
                        )
                    } else {
                        val answer = action.optString("answer").trim()
                        if (answer.isBlank()) return null
                        return PersonalAgentRunResult(answer, engineLabel, toolCount, used)
                    }
                }

                "tool" -> {
                    val name = action.optString("name").trim()
                    if (name.isBlank()) return null
                    val arguments = action.optJSONObject("arguments") ?: JSONObject()

                    if (factoryGate && !factoryResolved && name != "create_learned_tool") {
                        transcript = appendTurn(
                            transcript,
                            action,
                            "RUNTIME TOOL FACTORY GATE: only create_learned_tool is allowed until the reusable capability gap is resolved. Do not probe another raw metric.",
                            initialPrompt,
                        )
                        return@repeat
                    }

                    if (name !in knownTools) {
                        transcript = appendTurn(
                            transcript,
                            action,
                            "RUNTIME TOOL ALLOW-LIST: '$name' is not an available safe tool. Use only advertised tools or a learned tool created in this session.",
                            initialPrompt,
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

                    if (name == "create_learned_tool") {
                        val resultObject = runCatching { JSONObject(result) }.getOrNull()
                        when (resultObject?.optString("status")) {
                            "invalid_pipeline", "invalid_spec" -> {
                                factoryRepairs += 1
                                factoryGate = factoryRepairs < maxFactoryRepairs
                                if (!factoryGate) {
                                    resultObject.put("repair_budget_exhausted", true)
                                    resultObject.put("instruction", "Do not call create_learned_tool again in this request. Answer from exact existing deterministic evidence and state any remaining limitation without substituting a proxy.")
                                }
                            }
                            "created", "reused" -> {
                                factoryResolved = true
                                factoryGate = false
                                resultObject.optJSONObject("tool")?.optString("name")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(knownTools::add)
                            }
                        }
                    }

                    transcript = appendTurn(transcript, action, "TOOL RESULT for $name:\n$result", initialPrompt)
                    if (factoryCandidate && !factoryResolved && factoryRepairs < maxFactoryRepairs && index + 1 >= FACTORY_GATE_AFTER_STEPS) {
                        factoryGate = true
                    }
                }

                else -> return null
            }
        }

        onStage("Personal agent · finalising from collected results…")
        val finalPrompt = compactTranscript(
            transcript + "\n\nFINAL ANSWER REQUIRED NOW. Do not call tools. Return exactly " +
                "{\"action\":\"final\",\"answer\":\"...\"}. Answer the user's question first and concisely. " +
                "Do not add Evidence/Reliability/Methods sections or narrate tool use. Mention only material values or limitations.",
            initialPrompt,
        )
        val raw = turn(finalPrompt, FINAL_OUTPUT_TOKENS)
        val action = parseAction(raw)
        val answer = action?.takeIf { it.optString("action") == "final" }
            ?.optString("answer")?.trim().orEmpty()
        if (answer.isBlank()) return null
        return PersonalAgentRunResult(answer, engineLabel, toolCount, used)
    }

    private fun appendTurn(
        transcript: String,
        action: JSONObject,
        result: String,
        initialPrompt: String,
    ): String = compactTranscript(
        transcript + "\n\nASSISTANT ACTION:\n$action\n\n$result\n\nChoose exactly one next action. JSON only.",
        initialPrompt,
    )

    private fun compactTranscript(transcript: String, initialPrompt: String): String {
        if (transcript.length <= MAX_TRANSCRIPT_CHARS) return transcript
        return initialPrompt.take(INITIAL_CONTEXT_CHARS) +
            "\n\n[older agent turns compacted; latest deterministic results follow]\n\n" +
            transcript.takeLast(RECENT_CONTEXT_CHARS)
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

    companion object {
        private const val ACTION_OUTPUT_TOKENS = 1536
        private const val FINAL_OUTPUT_TOKENS = 2048
        private const val MAX_TRANSCRIPT_CHARS = 42_000
        private const val INITIAL_CONTEXT_CHARS = 16_000
        private const val RECENT_CONTEXT_CHARS = 24_000
        private const val FACTORY_GATE_AFTER_STEPS = 3

        private val METRIC_READER_TOOLS = setOf(
            "get_metric_series", "get_data_coverage", "get_daily_summary", "get_baseline",
            "get_missing_data", "detect_outliers", "detect_trends",
        )
        private val MUTATING_AGENT_TOOLS = setOf(
            "create_learned_tool", "ask_user_feedback", "record_user_feedback",
        )
    }
}
