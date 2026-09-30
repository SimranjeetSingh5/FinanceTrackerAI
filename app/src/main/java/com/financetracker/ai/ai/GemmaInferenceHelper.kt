package com.financetracker.ai.ai

import android.content.Context
import android.os.SystemClock
import com.financetracker.ai.BuildConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    suspend fun initialize(maxTokens: Int = DEFAULT_MAX_TOKENS): Result<Unit> {
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

        android.util.Log.e(LOG_TAG, "no working backend")
        return Result.failure(
            ModelUnsupportedException(
                "This model file loaded but produced no output for even a trivial prompt, so it " +
                        "can't answer questions. That usually means it is a base (pre-trained) " +
                        "build rather than an instruction-tuned one. Replace the .task file with " +
                        "an instruction-tuned Gemma build (a .task with '-it' or 'instruction' in " +
                        "its name) and the AI features will work.",
                lastError
            )
        )
    }

    /**
     * The engine initialised but cannot generate. Distinguished from other failures because the
     * remedy is a different model file, not a code change.
     */
    class ModelUnsupportedException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private suspend fun tryBackend(choice: BackendChoice, maxTokens: Int): Result<Unit> = try {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(getLocalModelFile().absolutePath)
            .setMaxTokens(maxTokens)
            .setPreferredBackend(choice.backend)
            .build()

        val t0 = SystemClock.elapsedRealtime()
        val engine = LlmInference.createFromOptions(context, options)
        android.util.Log.d(LOG_TAG, "${choice.name}: engine created in ${SystemClock.elapsedRealtime() - t0}ms")

        // Smoke test: a backend that loads but can't generate is useless, and this is the only
        // point where we find out cheaply. It runs on a worker thread with a hard timeout —
        // a backend that never returns would otherwise hang initialisation forever and leave
        // the UI spinning with no way to recover.
        //
        // The probe uses a real chat turn and default sampling on purpose. An earlier version
        // set topK=1, which pinned the model to a single candidate token and made the result a
        // constant regardless of the prompt — it "passed" for every prompt while proving
        // nothing. The actual chat path is exercised here so a mismatch shows up as a
        // failed init rather than as silent empty answers later.
        val probe = LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(0.7f)
                .setTopK(40)
                .build()
        )
        try {
            probe.addQueryChunk(
                GemmaChat.buildPrompt(
                    systemInstruction = "",
                    userMessage = "Reply with the single word: ready"
                )
            )
            val tProbe = SystemClock.elapsedRealtime()
            val response = withTimeout(SMOKE_TEST_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    probe.generateResponseAsync().get(PROBE_WAIT_MS, TimeUnit.MILLISECONDS)
                }
            }
            val cleaned = cleanResponse(response)
            android.util.Log.d(
                LOG_TAG,
                "${choice.name}: smoke test in ${SystemClock.elapsedRealtime() - tProbe}ms, " +
                        "${cleaned.length} chars -> \"${cleaned.take(80)}\""
            )
            // A backend can load, return a string, and still be useless if that string is
            // empty. That was silently passing as "healthy" before.
            if (cleaned.isBlank()) {
                throw IllegalStateException("Backend produced an empty response")
            }
        } finally {
            try { probe.close() } catch (_: Exception) {}
        }

        inferenceEngine.set(engine)
        Result.success(Unit)
    } catch (e: Exception) {
        android.util.Log.w(LOG_TAG, "${choice.name} failed: ${e::class.java.simpleName}: ${e.message}")
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

    /**
     * The engine, initialising it if needed.
     *
     * Kept separate so every entry point shares the same lazy path, and so a failed
     * initialisation surfaces as a Result rather than an exception mid-render.
     */
    private suspend fun engine(): Result<LlmInference> {
        inferenceEngine.get()?.let { return Result.success(it) }

        val init = initialize()
        if (init.isFailure) {
            return Result.failure(
                init.exceptionOrNull() ?: IllegalStateException("Engine not initialised.")
            )
        }
        return inferenceEngine.get()?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("Engine not initialised."))
    }

    /**
     * Strips a trailing turn marker the model may have emitted.
     *
     * MediaPipe occasionally includes the closing token in the returned text, which then shows
     * up verbatim in the UI as a stray `<end_of_turn>`.
     */
    private fun cleanResponse(text: String): String =
        text.replace(GemmaChat.END, "").replace(GemmaChat.START, "").trim()

    suspend fun generateResponse(
        prompt: String,
        temperature: Float = 0.2f,
        topK: Int = 40
    ): Result<String> = withContext(Dispatchers.IO) {
        engine().mapCatching { eng ->
            var session: LlmInferenceSession? = null
            try {
                val t0 = SystemClock.elapsedRealtime()
                session = LlmInferenceSession.createFromOptions(
                    eng, createSessionOptions(temperature, topK)
                )
                val tSession = SystemClock.elapsedRealtime()

                session.addQueryChunk(prompt)
                val tPrefill = SystemClock.elapsedRealtime()
                val raw = session.generateResponseAsync().get(GENERATE_WAIT_MS, TimeUnit.MILLISECONDS)
                val response = cleanResponse(raw)
                val tDone = SystemClock.elapsedRealtime()

                android.util.Log.d(
                    LOG_TAG,
                    "generateResponse: total=${tDone - t0}ms (setup=${tSession - t0}ms, " +
                            "generate=${tDone - tPrefill}ms) out=${response.length} chars"
                )
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(LOG_TAG, "PROMPT >>>\n$prompt\n<<< END PROMPT")
                    android.util.Log.d(LOG_TAG, "OUTPUT >>>\n$response\n<<< END OUTPUT")
                }
                response
            } finally {
                try { session?.close() } catch (_: Exception) {}
            }
        }.onFailure {
            android.util.Log.e(LOG_TAG, "generateResponse failed: ${it.message}", it)
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
            if (BuildConfig.DEBUG) {
                // Log the whole prompt: turn markers are what has been wrong twice now, and
                // seeing them is the only way to verify the structure is balanced.
                android.util.Log.d(LOG_TAG, "stream: PROMPT >>>\n$prompt\n<<< END PROMPT")
            }
            android.util.Log.d(
                LOG_TAG,
                "stream: submitted promptChars=${prompt.length} in ${tPrefill - t0}ms"
            )

            // MediaPipe's ProgressListener<String> delivers the FULL accumulated response on
            // every callback, not an incremental delta. Callers therefore replace their buffer
            // with each value rather than appending — appending would re-concatenate the whole
            // response on every token and produce runaway duplicated text.
            var emitted = false
            val progressListener = ProgressListener<String> { partialResult, isComplete ->
                val cleaned = cleanResponse(partialResult.orEmpty())
                if (cleaned.isNotEmpty()) {
                    emitted = true
                    trySend(cleaned)
                }
                if (isComplete) {
                    android.util.Log.d(
                        LOG_TAG,
                        "stream: complete in ${SystemClock.elapsedRealtime() - t0}ms " +
                                "(emitted=$emitted, chars=${cleaned.length})"
                    )
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(LOG_TAG, "stream: OUTPUT >>>\n$cleaned\n<<< END OUTPUT")
                    }
                    // A model that terminated without producing anything is a real failure
                    // mode, and silently closing the flow leaves the caller with an empty
                    // reply and no explanation. Surface it instead.
                    if (!emitted) {
                        android.util.Log.w(LOG_TAG, "stream produced no output")
                        close(IllegalStateException("The model returned no response."))
                    } else {
                        close()
                    }
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

        /**
         * How long a backend's smoke test may take before we treat it as unusable. A backend
         * that loads but never generates is a real failure mode, and without a bound here the
         * app sits on "initialising" indefinitely.
         */
        const val SMOKE_TEST_TIMEOUT_MS = 90_000L

        /** Shorter wait on the blocking call itself, so a wedged backend fails fast. */
        const val PROBE_WAIT_MS = 60_000L

        /**
         * Bound on a real generation. Generous, because on a slow emulator a few sentences can
         * take tens of seconds — but bounded, so a wedged session can't hang the UI forever.
         */
        const val GENERATE_WAIT_MS = 180_000L
    }
}