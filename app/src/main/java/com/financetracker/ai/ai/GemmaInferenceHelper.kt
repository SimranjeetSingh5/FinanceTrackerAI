package com.financetracker.ai.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 100% Offline Local Inference Wrapper using stateful LlmInferenceSession.
 */
class GemmaInferenceHelper(private val context: Context) {

    private val inferenceEngine = AtomicReference<LlmInference?>(null)

    fun getLocalModelFile(): File {
        return File(context.filesDir, "gemma_local_model.task")
    }

    fun isModelDownloaded(): Boolean {
        val file = getLocalModelFile()
        return file.exists() && file.length() > 0
    }

    fun initialize(maxTokens: Int = 2048): Result<Unit> {
        if (inferenceEngine.get() != null) {
            return Result.success(Unit)
        }

        if (!isModelDownloaded()) {
            return Result.failure(
                IllegalStateException("Missing local binary target at: ${getLocalModelFile().absolutePath}")
            )
        }

        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(getLocalModelFile().absolutePath)
                .setMaxTokens(maxTokens)
                .build()

            inferenceEngine.set(LlmInference.createFromOptions(context, options))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun createSessionOptions(temperature: Float, topK: Int): LlmInferenceSession.LlmInferenceSessionOptions {
        return LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(temperature)
            .setTopK(topK)
            .build()
    }

    /**
     * One-shot generation. Uses Guava future blocking safely off the main thread.
     */
    suspend fun generateResponse(
        prompt: String,
        temperature: Float = 0.2f,
        topK: Int = 40
    ): Result<String> = withContext(Dispatchers.IO) {
        val engine = inferenceEngine.get()
            ?: return@withContext Result.failure(IllegalStateException("Engine not initialized."))

        var session: LlmInferenceSession? = null
        try {
            val sessionOptions = createSessionOptions(temperature, topK)
            session = LlmInferenceSession.createFromOptions(engine, sessionOptions)

            session.addQueryChunk(prompt)
            // .get() blocks the thread safely inside Dispatchers.IO until the ListenableFuture resolves
            val response = session.generateResponseAsync().get()

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { session?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Streams response chunks via an inline ProgressListener mapped to a Coroutine Flow.
     */
    fun generateResponseStream(
        prompt: String,
        temperature: Float = 0.2f,
        topK: Int = 40
    ): Flow<String> = callbackFlow {
        val engine = inferenceEngine.get()
        if (engine == null) {
            close(IllegalStateException("Engine not initialized."))
            return@callbackFlow
        }

        var session: LlmInferenceSession? = null
        try {
            val sessionOptions = createSessionOptions(temperature, topK)
            session = LlmInferenceSession.createFromOptions(engine, sessionOptions)

            session.addQueryChunk(prompt)

            // Instantiate the required ProgressListener interface explicitly
            val progressListener = ProgressListener<String> { partialResult, isComplete ->
                if (!partialResult.isNullOrEmpty()) {
                    trySend(partialResult)
                }
                if (isComplete) {
                    close()
                }
            }

            session.generateResponseAsync(progressListener)

        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            try { session?.close() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    fun release() {
        val activeEngine = inferenceEngine.getAndSet(null)
        try {
            activeEngine?.close()
        } catch (_: Exception) {}
    }
}