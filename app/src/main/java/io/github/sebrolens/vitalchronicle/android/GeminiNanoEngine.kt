package io.github.sebrolens.vitalchronicle.android

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

class GeminiNanoEngine {
    // Use the default Prompt API client. This is the same runtime path which worked
    // reliably before the latency experiments. Question-aware deterministic retrieval
    // already keeps Nano's task small, so a model preference must not make the feature
    // unavailable on devices which expose only the default built-in model variant.
    private val model by lazy { Generation.getClient(generationConfig {}) }

    suspend fun modelName(): String? = runCatching {
        if (model.checkStatus() == FeatureStatus.AVAILABLE) model.getBaseModelName() else null
    }.getOrNull()

    suspend fun prepare(progress: (String) -> Unit): String {
        progress("Checking Android built-in AI…")
        when (val featureStatus = model.checkStatus()) {
            FeatureStatus.AVAILABLE -> progress("Gemini Nano ready")
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                progress("Preparing Gemini Nano model…")
                model.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> progress("Preparing Gemini Nano model…")
                        is DownloadStatus.DownloadProgress -> progress("Downloading Android AI components…")
                        is DownloadStatus.DownloadCompleted -> progress("Gemini Nano ready")
                        is DownloadStatus.DownloadFailed -> throw status.e
                    }
                }
            }
            FeatureStatus.UNAVAILABLE -> error("Gemini Nano is unavailable on this device or AICore configuration.")
            else -> error("Unexpected Gemini Nano availability status: $featureStatus")
        }
        val name = model.getBaseModelName()
        progress("$name ready")
        return name
    }

    suspend fun answer(question: String, evidenceJson: String, progress: (String) -> Unit): AiResult {
        val name = prepare(progress)
        val retrievalMode = runCatching {
            JSONObject(evidenceJson).optJSONObject("retrieval")?.optString("mode")
        }.getOrNull().orEmpty().ifBlank { "general" }

        val requestedOutputTokens = when (retrievalMode) {
            "specific_relation", "specific" -> 512
            "domain" -> 640
            else -> 768
        }

        val system = """
            You are VitalChronicle's fully on-device explanation layer, not a medical device.
            The evidence packet was selected deterministically for this exact question; use only that evidence and do not invent missing measurements.
            Missing data are not zero. Association is not causation. An unreported association is not proof that no relationship exists.
            If relation_checks says not_calculated, explain its stated reason and paired-day count rather than guessing a correlation.
            Mention material coverage limitations and that an incomplete current day can bias cumulative metrics.
            Be concise, precise, and answer in the user's language.
        """.trimIndent()
        val prompt = """
            QUESTION:
            $question

            DETERMINISTIC EVIDENCE:
            $evidenceJson
        """.trimIndent()

        fun request(maxOutput: Int) = generateContentRequest(SystemInstruction(system), TextPart(prompt)) {
            maxOutputTokens = maxOutput
            candidateCount = 1
            enableThinking = false
        }

        var maxOutputTokens = requestedOutputTokens
        var generationRequest = request(maxOutputTokens)

        // Token telemetry is useful, but it is not required to run Nano. Some AICore/
        // Prompt API combinations expose generation correctly while optional token
        // introspection or warm-up calls fail. Never report Nano as unavailable merely
        // because telemetry is unavailable.
        val tokenTelemetry = runCatching {
            val input = model.countTokens(generationRequest).totalTokens
            val limit = model.getTokenLimit()
            input to limit
        }.getOrNull()

        if (tokenTelemetry != null) {
            val (inputTokens, tokenLimit) = tokenTelemetry
            val availableForOutput = tokenLimit - inputTokens
            if (tokenLimit > 0 && availableForOutput >= 128 && maxOutputTokens > availableForOutput) {
                maxOutputTokens = availableForOutput
                generationRequest = request(maxOutputTokens)
            }
            progress(
                "$name · input $inputTokens / $tokenLimit tokens · output max $maxOutputTokens · $retrievalMode · analysing…"
            )
        } else {
            progress("$name · output max $maxOutputTokens · $retrievalMode · analysing…")
        }

        val response = model.generateContent(generationRequest)
        val text = response.candidates.firstOrNull()?.text?.trim().orEmpty()
        if (text.isBlank()) error("Gemini Nano returned an empty answer.")
        return AiResult(text, "Gemini Nano · Android built-in AI", name)
    }

    fun close() = model.close()
}
