package io.github.sebrolens.vitalchronicle.android

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

data class LocalGenerationSnapshot(
    val thinking: String,
    val answer: String,
    val thinkingActive: Boolean,
    val generatedTokens: Int,
    val maximumTokens: Int,
    val tokensPerSecond: Double,
)

class OllamaOnDeviceEngine(context: Context) {
    private val engineDelegate = lazy { AiChat.getInferenceEngine(context.applicationContext) }
    private val engine: InferenceEngine by engineDelegate

    suspend fun answer(
        model: OllamaModelSpec,
        modelFile: File,
        question: String,
        evidenceJson: String,
        maximumTokens: Int,
        onStage: (String) -> Unit,
        onSnapshot: (LocalGenerationSnapshot) -> Unit,
    ) {
        require(modelFile.isFile) { "The selected Ollama model is not installed." }
        prepareFreshModel(model, modelFile, onStage)

        val prompt = buildString {
            if (model.supportsThinking) append("/think\n")
            append("## QUESTION\n").append(question.trim())
            append("\n\n## DETERMINISTIC EVIDENCE\n").append(evidenceJson)
        }
        val raw = StringBuilder()
        var generatedTokens = 0
        val startedAt = System.nanoTime()
        onStage("${model.id} · generating locally…")

        try {
            engine.sendUserPrompt(prompt, maximumTokens).collect { tokenText ->
                generatedTokens += 1
                raw.append(tokenText)
                val parsed = splitThinking(raw.toString())
                val elapsedSeconds = ((System.nanoTime() - startedAt) / 1_000_000_000.0).coerceAtLeast(0.001)
                onSnapshot(
                    LocalGenerationSnapshot(
                        thinking = parsed.thinking,
                        answer = parsed.answer,
                        thinkingActive = parsed.thinkingActive,
                        generatedTokens = generatedTokens,
                        maximumTokens = maximumTokens,
                        tokensPerSecond = generatedTokens / elapsedSeconds,
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        }

        val final = splitThinking(raw.toString())
        require(final.answer.isNotBlank()) {
            if (final.thinking.isNotBlank()) "The model completed its reasoning without producing a final answer."
            else "The local model returned an empty answer."
        }
    }

    private suspend fun prepareFreshModel(
        model: OllamaModelSpec,
        file: File,
        onStage: (String) -> Unit,
    ) {
        awaitInitialState()
        if (engine.state.value.isModelLoaded) {
            onStage("Resetting the previous local model session…")
            withContext(Dispatchers.IO) { engine.cleanUp() }
        }
        val error = engine.state.value as? InferenceEngine.State.Error
        if (error != null) withContext(Dispatchers.IO) { engine.cleanUp() }

        onStage("Loading ${model.id} into memory…")
        engine.loadModel(file.absolutePath)
        onStage("Preparing the private analysis context…")
        engine.setSystemPrompt(SYSTEM_PROMPT)
    }

    private suspend fun awaitInitialState() {
        when (val state = engine.state.value) {
            InferenceEngine.State.Uninitialized, InferenceEngine.State.Initializing -> {
                val ready = withTimeout(NATIVE_INITIALIZATION_TIMEOUT_MS) {
                    engine.state.first {
                        it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                    }
                }
                if (ready is InferenceEngine.State.Error) throw ready.exception
            }
            is InferenceEngine.State.Error -> throw state.exception
            else -> Unit
        }
    }

    suspend fun unload() {
        if (!engineDelegate.isInitialized()) return
        awaitInitialState()
        if (engine.state.value.isModelLoaded || engine.state.value is InferenceEngine.State.Error) {
            withContext(Dispatchers.IO) { engine.cleanUp() }
        }
    }

    fun close() {
        if (!engineDelegate.isInitialized()) return
        runCatching { engineDelegate.value.destroy() }
    }

    private data class ParsedThinking(
        val thinking: String,
        val answer: String,
        val thinkingActive: Boolean,
    )

    private fun splitThinking(raw: String): ParsedThinking {
        val openTags = listOf("<think>", "<analysis>")
        val closeTags = listOf("</think>", "</analysis>")
        val open = openTags.map { raw.indexOf(it, ignoreCase = true) to it }
            .filter { it.first >= 0 }
            .minByOrNull { it.first }
            ?: return ParsedThinking("", stripControlTags(raw).trimStart(), false)
        val contentStart = open.first + open.second.length
        val close = closeTags.map { raw.indexOf(it, contentStart, ignoreCase = true) to it }
            .filter { it.first >= 0 }
            .minByOrNull { it.first }

        return if (close == null) {
            ParsedThinking(
                thinking = raw.substring(contentStart).trimStart(),
                answer = stripControlTags(raw.substring(0, open.first)).trim(),
                thinkingActive = true,
            )
        } else {
            ParsedThinking(
                thinking = raw.substring(contentStart, close.first).trim(),
                answer = stripControlTags(
                    raw.substring(0, open.first) + raw.substring(close.first + close.second.length)
                ).trimStart(),
                thinkingActive = false,
            )
        }
    }

    private fun stripControlTags(text: String): String = text
        .replace(Regex("</?(think|analysis)>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""<\|(?:im_start|im_end)\|>"""), "")

    companion object {
        private const val NATIVE_INITIALIZATION_TIMEOUT_MS = 30_000L
        private val SYSTEM_PROMPT = """
            You are VitalChronicle's fully on-device health-data explanation layer, not a medical device.
            Answer the exact question first and use only the deterministic evidence supplied by the app.
            Never invent missing measurements. Missing data are not zero and association is not causation.
            Separate measured facts, calculated relationships, and cautious interpretation. State material coverage limits.
            Respond in the user's language with light Markdown: short headings, **bold** key findings, and concise bullets.
            Keep hidden/internal reasoning inside the model's thinking channel and provide a self-contained final answer.
        """.trimIndent()
    }
}
