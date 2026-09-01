package io.github.sebrolens.vitalchronicle.android

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

class GeminiNanoEngine {
    // VitalChronicle performs the scientific/statistical work before the language
    // model runs. Prefer the latency-optimised on-device model variant: Nano only
    // has to explain a small deterministic packet selected for the user's question.
    private val model by lazy {
        Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    preference = ModelPreference.FAST
                }
            }
        )
    }
    private var warmedUp = false

    suspend fun modelName(): String? = runCatching { model.getBaseModelName() }.getOrNull()

    suspend fun prepare(progress: (String) -> Unit): String {
        progress("Checking Android built-in AI…")
        val name = model.getBaseModelName()
        progress("Preparing $name…")
        model.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadStarted -> progress("Preparing Gemini Nano model…")
                is DownloadStatus.DownloadProgress -> progress("Downloading Android AI components…")
                is DownloadStatus.DownloadCompleted -> progress("Gemini Nano ready")
                is DownloadStatus.DownloadFailed -> throw status.e
            }
        }
        if (!warmedUp) {
            progress("Warming up Gemini Nano…")
            model.warmup()
            warmedUp = true
        }
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

        // ML Kit's tokenizer is authoritative. Count the complete request, including
        // system instruction and question, not just the JSON packet estimate.
        val inputTokens = model.countTokens(generationRequest).totalTokens
        val tokenLimit = model.getTokenLimit()
        val availableForOutput = tokenLimit - inputTokens
        if (availableForOutput < 128) {
            error("Gemini Nano prompt is too large: $inputTokens / $tokenLimit input tokens leave too little room for an answer.")
        }
        if (maxOutputTokens > availableForOutput) {
            maxOutputTokens = availableForOutput.coerceAtLeast(128)
            generationRequest = request(maxOutputTokens)
        }

        progress(
            "$name · input $inputTokens / $tokenLimit tokens · output max $maxOutputTokens · $retrievalMode · analysing…"
        )

        val response = model.generateContent(generationRequest)
        val text = response.candidates.firstOrNull()?.text?.trim().orEmpty()
        if (text.isBlank()) error("Gemini Nano returned an empty answer.")
        return AiResult(text, "Gemini Nano · Android built-in AI", name)
    }

    fun close() = model.close()
}
