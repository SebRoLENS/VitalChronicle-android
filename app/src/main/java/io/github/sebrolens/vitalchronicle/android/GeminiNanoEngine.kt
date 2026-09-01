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

class GeminiNanoEngine {
    // VitalChronicle already performs the scientific/statistical work before the
    // language model runs. Prefer the latency-optimised on-device model variant:
    // Nano's job here is interpretation and wording, not recomputing statistics.
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
        val system = """
            You are the fully on-device analysis assistant inside VitalChronicle, a wellness-data explorer, not a medical device.
            Use only the deterministic evidence supplied by VitalChronicle. Never invent missing measurements or treat missing data as zero.
            State material data-coverage limitations near the start when the evidence marks the requested interval as partially observed.
            Prefer sustained and multi-metric patterns over trivial day-to-day arithmetic. Distinguish association from causation.
            Do not diagnose disease and do not replace professional medical advice. Be concise, precise, and use the user's language.
        """.trimIndent()
        val prompt = """
            USER QUESTION:
            $question

            VITALCHRONICLE DETERMINISTIC EVIDENCE (local JSON):
            $evidenceJson
        """.trimIndent()
        val request = generateContentRequest(SystemInstruction(system), TextPart(prompt)) {
            // 2048 output tokens made short wellness answers unnecessarily slow on
            // Nano v3. 768 is enough for a useful answer while bounding latency.
            maxOutputTokens = 768
            candidateCount = 1
            enableThinking = false
        }

        // Use ML Kit's tokenizer rather than guessing from JSON length. This count
        // includes the system instruction and wrapper, which the Python packet-size
        // estimate intentionally cannot know about.
        val inputTokens = model.countTokens(request).totalTokens
        val tokenLimit = model.getTokenLimit()
        if (inputTokens + 768 > tokenLimit) {
            error("Gemini Nano prompt is too large: $inputTokens input tokens for a $tokenLimit-token model budget.")
        }
        progress("$name · $inputTokens input tokens · analysing…")

        val response = model.generateContent(request)
        val text = response.candidates.firstOrNull()?.text?.trim().orEmpty()
        if (text.isBlank()) error("Gemini Nano returned an empty answer.")
        return AiResult(text, "Gemini Nano · Android built-in AI", name)
    }

    fun close() = model.close()
}
