package com.financetracker.ai.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin wrapper around MediaPipe's LLM Inference API running Gemma fully on-device.
 *
 * There is no network call and no per-token billing here — the model runs locally on the
 * phone's CPU/GPU, so "unlimited tokens" in the sense of no usage quota is true by construction.
 * The real ceiling is the model's context window (set via maxTokens below, e.g. Gemma 3 1B/2B
 * variants commonly support 4096-8192 tokens) and on-device compute/battery.
 *
 * Setup required by the user (see README.md):
 *  1. Download a Gemma .task model file (e.g. gemma-3-1b-it-int4.task) from Kaggle/HuggingFace's
 *     LiteRT/MediaPipe model hub.
 *  2. Copy it to the path returned by [defaultModelPath], or let the user pick it via SAF and
 *     copy it there on first launch.
 */
class GemmaInferenceHelper(private val context: Context) {

    private var llmInference: LlmInference? = null
    private var session: LlmInferenceSession? = null

    var isReady: Boolean = false
        private set

    fun defaultModelPath(): String =
        File(context.getExternalFilesDir(null), "models/gemma-model.task").absolutePath

    fun modelFileExists(): Boolean = File(defaultModelPath()).exists()

    /**
     * Loads the model into memory. This is expensive (seconds, depending on model size and
     * device) and should be called once, off the main thread, e.g. from a splash/setup screen.
     */
    suspend fun initialize(
        modelPath: String = defaultModelPath(),
        maxTokens: Int = 4096,
        preferGpu: Boolean = true,
        temperature: Float = 0.7f,
        topK: Int = 40
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setPreferredBackend(if (preferGpu) Backend.GPU else Backend.CPU)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)

            val sessionOptions = LlmInferenceSessionOptions.builder()
                .setTemperature(temperature)
                .setTopK(topK)
                .build()

            session = LlmInferenceSession.createFromOptions(llmInference, sessionOptions)
            isReady = true
        }
    }

    /** One-shot, blocking-ish generation (still runs on IO dispatcher). Good for short prompts
     *  like categorizing a single transaction. */
    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val activeSession = session ?: error("Gemma model not initialized. Call initialize() first.")
            activeSession.addQueryChunk(prompt)
            activeSession.generateResponse()
        }
    }

    /** Streaming generation for the chat assistant screen — emits partial tokens as they're
     *  produced so the UI can show a live typing effect. */
    fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val activeSession = session
        if (activeSession == null) {
            close(IllegalStateException("Gemma model not initialized. Call initialize() first."))
            return@callbackFlow
        }
        activeSession.addQueryChunk(prompt)
        activeSession.generateResponseAsync { partialResult, done ->
            trySend(partialResult)
            if (done) close()
        }
        awaitClose { /* MediaPipe session cleans up internally on completion */ }
    }

    fun resetSession(temperature: Float = 0.7f, topK: Int = 40) {
        session?.close()
        val inference = llmInference ?: return
        val sessionOptions = LlmInferenceSessionOptions.builder()
            .setTemperature(temperature)
            .setTopK(topK)
            .build()
        session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
    }

    fun close() {
        session?.close()
        llmInference?.close()
        session = null
        llmInference = null
        isReady = false
    }
}
