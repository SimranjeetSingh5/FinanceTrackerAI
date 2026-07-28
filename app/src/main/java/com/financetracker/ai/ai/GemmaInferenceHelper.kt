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
            // Force CPU backend directly to avoid OpenCL invalid work group size crashes on Adreno/Mali GPUs
            val cpuOptions = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(getLocalModelFile().absolutePath)
                .setMaxTokens(maxTokens)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()

            inferenceEngine.set(LlmInference.createFromOptions(context, cpuOptions))
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

    suspend fun generateResponse(
        prompt: String,
        temperature: Float = 0.2f,
        topK: Int = 40
    ): Result<String> = withContext(Dispatchers.IO) {
        val engine = inferenceEngine.get() ?: run {
            val initResult = initialize()
            if (initResult.isFailure) {
                return@withContext Result.failure(
                    initResult.exceptionOrNull() ?: IllegalStateException("Engine not initialized.")
                )
            }
            inferenceEngine.get()
                ?: return@withContext Result.failure(IllegalStateException("Engine not initialized."))
        }

        var session: LlmInferenceSession? = null
        try {
            val sessionOptions = createSessionOptions(temperature, topK)
            session = LlmInferenceSession.createFromOptions(engine, sessionOptions)

            session.addQueryChunk(prompt)
            val response = session.generateResponseAsync().get()

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { session?.close() } catch (_: Exception) {}
        }
    }

    fun generateResponseStream(
        prompt: String,
        temperature: Float = 0.2f,
        topK: Int = 40
    ): Flow<String> = callbackFlow {
        val engine = inferenceEngine.get() ?: run {
            val initResult = initialize()
            if (initResult.isFailure) {
                close(initResult.exceptionOrNull() ?: IllegalStateException("Engine not initialized."))
                return@callbackFlow
            }
            inferenceEngine.get() ?: run {
                close(IllegalStateException("Engine not initialized."))
                return@callbackFlow
            }
        }

        var session: LlmInferenceSession? = null
        try {
            val sessionOptions = createSessionOptions(temperature, topK)
            session = LlmInferenceSession.createFromOptions(engine, sessionOptions)

            session.addQueryChunk(prompt)

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