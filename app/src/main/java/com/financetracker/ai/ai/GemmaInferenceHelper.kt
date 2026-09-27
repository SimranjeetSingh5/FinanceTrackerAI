package com.financetracker.ai.ai

import android.content.Context
import android.os.SystemClock
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

    /** Name of the backend that survived the smoke test, e.g. "GPU" or "CPU". */
    @Volatile
    var activeBackendName: String = "none"
        private set

    fun getLocalModelFile(): File {
        return File(context.filesDir, "gemma_local_model.task")
    }

    fun isModelDownloaded(): Boolean {
        val file = getLocalModelFile()
        return file.exists() && file.length() > 0
    }

    /**
     * Creates the engine, preferring GPU and falling back to CPU.
     *
     * A GPU that *initialises* is not necessarily a GPU that *works* — MediaPipe commonly
     * throws OpenCL errors only on the first real session. So each candidate backend is
     * smoke-tested with a one-token generation before we commit to it; whichever backend
     * actually returns is the one we keep. Without this, a device with a broken-but-loadable
     * GPU would fail on every inference instead of silently degrading to CPU.
     */
    fun initialize(maxTokens: Int = DEFAULT_MAX_TOKENS): Result<Unit> {
        if (inferenceEngine.get() != null) {
            return Result.success(Unit)
        }

        if (!isModelDownloaded()) {
            return Result.failure(
                IllegalStateException("Missing local binary target at: ${getLocalModelFile().absolutePath}")
            )
        }

        // A previous run may have discovered that GPU is broken on this device; skip straight
        // to CPU rather than paying the failed-attempt cost on every app launch.
        val order = if (prefersCpu()) {
            listOf(BackendChoice("CPU", LlmInference.Backend.CPU))
        } else {
            listOf(
                BackendChoice("GPU", LlmInference.Backend.GPU),
                BackendChoice("CPU", LlmInference.Backend.CPU)
            )
        }

        var lastError: Throwable? = null
        for (choice in order) {
            val result = tryBackend(choice, maxTokens)
            if (result.isSuccess) {
                activeBackendName = choice.name
                android.util.Log.d(LOG_TAG, "engine ready on ${choice.name}")
                return result
            }
            lastError = result.exceptionOrNull()
            android.util.Log.w(
                LOG_TAG,
                "${choice.name} backend unusable: ${lastError?.message}"
            )
        }

        android.util.Log.e(LOG_TAG, "no working backend; falling back to CPU-only answers")
        return Result.failure(
            lastError ?: IllegalStateException("Could not initialise the inference engine.")
        )
    }

    private fun tryBackend(choice: BackendChoice, maxTokens: Int): Result<Unit> = try {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(getLocalModelFile().absolutePath)
            .setMaxTokens(maxTokens)
            .setPreferredBackend(choice.backend)
            .build()

        val engine = LlmInference.createFromOptions(context, options)

        // Smoke test: a backend that loads but can't generate is useless, and this is the only
        // point where we find out cheaply.
        val probe = LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(0f)
                .setTopK(1)
                .build()
        )
        try {
            probe.addQueryChunk("Hi")
            probe.generateResponseAsync().get()
        } finally {
            try { probe.close() } catch (_: Exception) {}
        }

        inferenceEngine.set(engine)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private data class BackendChoice(val name: String, val backend: LlmInference.Backend)

    /**
     * Whether to skip the GPU attempt entirely.
     *
     * Emulators normally have no OpenCL device MediaPipe can use, so we go straight to CPU.
     * Set [FORCE_GPU_ON_EMULATOR] to test that assumption — the smoke test in [tryBackend]
     * reports whether GPU actually works, and we fall back either way.
     */
    private fun prefersCpu(): Boolean {
        if (FORCE_GPU_ON_EMULATOR) return false
        return android.os.Build.FINGERPRINT.startsWith("generic") ||
                android.os.Build.FINGERPRINT.contains("vbox") ||
                android.os.Build.FINGERPRINT.contains("emulator") ||
                android.os.Build.MODEL.contains("Emulator") ||
                android.os.Build.PRODUCT.contains("sdk_gphone") ||
                android.os.Build.HARDWARE.contains("goldfish")
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
            val t0 = SystemClock.elapsedRealtime()
            val sessionOptions = createSessionOptions(temperature, topK)
            session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
            val tSession = SystemClock.elapsedRealtime()
            android.util.Log.d(
                LOG_TAG,
                "generateResponse: session created in ${tSession - t0}ms, " +
                        "promptChars=${prompt.length}"
            )

            session.addQueryChunk(prompt)
            val tPrefill = SystemClock.elapsedRealtime()
            val response = session.generateResponseAsync().get()
            val tDone = SystemClock.elapsedRealtime()

            android.util.Log.d(
                LOG_TAG,
                "generateResponse: total=${tDone - t0}ms " +
                        "(setup=${tSession - t0}ms, generate=${tDone - tPrefill}ms) " +
                        "outputChars=${response.length}"
            )
            Result.success(response)
        } catch (e: Exception) {
            android.util.Log.e(LOG_TAG, "generateResponse failed: ${e.message}", e)
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
            val t0 = SystemClock.elapsedRealtime()
            val sessionOptions = createSessionOptions(temperature, topK)
            session = LlmInferenceSession.createFromOptions(engine, sessionOptions)

            session.addQueryChunk(prompt)
            val tPrefill = SystemClock.elapsedRealtime()
            android.util.Log.d(
                LOG_TAG,
                "stream: submitted promptChars=${prompt.length} in ${tPrefill - t0}ms"
            )

            val progressListener = ProgressListener<String> { partialResult, isComplete ->
                if (!partialResult.isNullOrEmpty()) {
                    trySend(partialResult)
                }
                if (isComplete) {
                    android.util.Log.d(
                        LOG_TAG,
                        "stream: complete in ${SystemClock.elapsedRealtime() - t0}ms"
                    )
                    close()
                }
            }

            session.generateResponseAsync(progressListener)

        } catch (e: Exception) {
            android.util.Log.e(LOG_TAG, "stream failed: ${e.message}", e)
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

    private companion object {
        const val LOG_TAG = "GemmaInference"

        /**
         * Debug switch: attempt the GPU backend even on emulators. Leave this off — the
         * emulator has no `libvndksupport.so`, and MediaPipe's OpenCL path segfaults natively
         * on dlopen failure rather than throwing, so the CPU fallback in [tryBackend] never
         * gets a chance to run and the process dies.
         */
        const val FORCE_GPU_ON_EMULATOR = false

        /**
         * Context window for the engine. Large enough for the richest prompt we send (chat with
         * conversation history plus recent transactions) without paying for KV-cache memory we
         * never use.
         */
        const val DEFAULT_MAX_TOKENS = 2048
    }
}