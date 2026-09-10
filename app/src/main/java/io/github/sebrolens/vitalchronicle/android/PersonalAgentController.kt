package io.github.sebrolens.vitalchronicle.android

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
