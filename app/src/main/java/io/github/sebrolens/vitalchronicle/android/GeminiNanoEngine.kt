package io.github.sebrolens.vitalchronicle.android

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

class GeminiNanoEngine {
    private val compatibleModelDelegate = lazy { Generation.getClient(generationConfig {}) }
    private val compatibleModel by compatibleModelDelegate
    private val accurateModelDelegate = lazy {
        Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.STABLE
                    preference = ModelPreference.FULL
                }
            }
        )
    }
    private val accurateModel by accurateModelDelegate

    private data class ModelSelection(
        val model: GenerativeModel,
        val profile: String,
        val accurate: Boolean,
    )

    suspend fun capability(): NanoCapability {
        val candidates = listOf(
            Triple(accurateModel, "Accurate local · FULL", true),
            Triple(compatibleModel, "Accurate local · compatible", false),
        )
        for ((model, profile, _) in candidates) {
            val status = runCatching { model.checkStatus() }.getOrNull() ?: continue
            val supported = status == FeatureStatus.AVAILABLE ||
                status == FeatureStatus.DOWNLOADABLE ||
                status == FeatureStatus.DOWNLOADING
            if (!supported) continue
            val name = runCatching { model.getBaseModelName() }.getOrNull()
            return NanoCapability(
                supported = true,
                ready = status == FeatureStatus.AVAILABLE,
                modelName = name?.let { "$it · $profile" } ?: profile,
                status = status.toString(),
            )
        }
        return NanoCapability(status = "Unavailable")
    }

    suspend fun modelName(): String? = capability().takeIf { it.ready }?.modelName

    private suspend fun selectModel(allowDownload: Boolean): ModelSelection {
        val accurateStatus = runCatching { accurateModel.checkStatus() }.getOrNull()
        val accurateUsable = when (accurateStatus) {
            FeatureStatus.AVAILABLE -> true
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> allowDownload
            else -> false
        }
        return if (accurateUsable) {
            ModelSelection(accurateModel, "Accurate local · FULL", accurate = true)
        } else {
            ModelSelection(compatibleModel, "Accurate local · compatible", accurate = false)
        }
    }

    private suspend fun prepare(selection: ModelSelection, progress: (String) -> Unit): String {
        val model = selection.model
        progress("Checking Android built-in AI · ${selection.profile}…")
        when (val featureStatus = model.checkStatus()) {
            FeatureStatus.AVAILABLE -> progress("Gemini Nano ready")
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                progress("Preparing Gemini Nano · ${selection.profile}…")
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
        progress("$name · ${selection.profile} ready")
        return name
    }

    suspend fun plan(plannerRequestJson: String, progress: (String) -> Unit): String {
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

    suspend fun personalAgentTurn(
        systemPrompt: String,
        prompt: String,
        maximumTokens: Int,
        progress: (String) -> Unit,
    ): String {
        val request = JSONObject()
            .put("system", systemPrompt)
            .put("prompt", prompt)
            .put("max_output_tokens", maximumTokens.coerceIn(128, 2048))
            .toString()
        return plan(request, progress)
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
        val maxOutput = planner.optInt("max_output_tokens", 560).coerceIn(128, 2048)
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
        val selection = selectModel(allowDownload = true)
        return try {
            answerWithModel(selection, question, evidenceJson, progress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!selection.accurate) throw e
            progress("FULL profile unavailable · retrying compatible Gemini Nano…")
            answerWithModel(
                ModelSelection(compatibleModel, "Accurate local · compatible", accurate = false),
                question,
                evidenceJson,
                progress,
            )
        }
    }

    private suspend fun answerWithModel(
        selection: ModelSelection,
        question: String,
        evidenceJson: String,
        progress: (String) -> Unit,
    ): AiResult {
        val model = selection.model
        val name = prepare(selection, progress)
        val retrievalMode = runCatching {
            JSONObject(evidenceJson).optJSONObject("retrieval")?.optString("mode")
        }.getOrNull().orEmpty().ifBlank { "general" }

        val requestedOutputTokens = when (retrievalMode) {
            "specific_relation", "specific" -> 1024
            "domain" -> 1280
            else -> 1536
        }
        val thinkingAvailable = runCatching { model.isThinkingModeAvailable() }.getOrDefault(false)

        val system = """
            You are VitalChronicle's fully on-device explanation layer, not a medical device.
            Answer the exact question in the first paragraph. Use only deterministic evidence supplied by the app and never invent missing measurements. Missing data are not zero; association is not causation.
            Be result-first and concise. Do not add Evidence, Reliability, Methods, Sources, data-inventory or tool-log sections unless the user explicitly asks for methodological detail. Do not narrate the evidence-selection process.
            Mention only the one or two measurements or coverage limitations that materially affect the conclusion. If a requested relationship was not calculated, state the concrete reason briefly instead of guessing.
            Respond in the user's language with light Markdown. No tables or fenced code blocks unless specifically requested.
        """.trimIndent()
        val prompt = """
            ## QUESTION
            $question

            ## DETERMINISTIC EVIDENCE
            $evidenceJson
        """.trimIndent()

        fun request(maxOutput: Int) = generateContentRequest(SystemInstruction(system), TextPart(prompt)) {
            temperature = 0.2f
            maxOutputTokens = maxOutput
            candidateCount = 1
            enableThinking = thinkingAvailable
        }

        var maxOutputTokens = requestedOutputTokens
        var generationRequest = request(maxOutputTokens)
        runCatching { model.warmup() }
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
                "$name · ${selection.profile} · input $inputTokens / $tokenLimit tokens · " +
                    "output max $maxOutputTokens · $retrievalMode" +
                    if (thinkingAvailable) " · thinking…" else " · analysing…"
            )
        } else {
            progress(
                "$name · ${selection.profile} · output max $maxOutputTokens · $retrievalMode" +
                    if (thinkingAvailable) " · thinking…" else " · analysing…"
            )
        }

        val response = model.generateContent(generationRequest)
        val text = response.candidates.firstOrNull()?.text?.trim().orEmpty()
        if (text.isBlank()) error("Gemini Nano returned an empty answer.")
        return AiResult(text, "Gemini Nano · Accurate local", "$name · ${selection.profile}")
    }

    fun close() {
        if (accurateModelDelegate.isInitialized()) accurateModelDelegate.value.close()
        if (compatibleModelDelegate.isInitialized()) compatibleModelDelegate.value.close()
    }
}
