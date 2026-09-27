package com.financetracker.ai.ai

/**
 * Where the Gemma `.task` model is fetched from.
 *
 * The model is deliberately not bundled in the APK: it is ~800 MB, far past the 150 MB AAB
 * limit, and shipping it inside the app would make every install pay the download cost and
 * freeze users on whichever version was current at release time.
 *
 * Hosted as a GitHub Release asset, which gives a stable, non-expiring public URL at no cost.
 * Keep the mirrors here rather than inline — swapping hosts should be a one-line change.
 */
object ModelSources {

    /**
     * Name the release asset is published under. This is only the remote filename — on the
     * device the file is always saved as GemmaInferenceHelper's gemma_local_model.task, so the
     * two names don't have to match. The URL must reference the asset name below exactly.
     */
    const val ASSET_FILE_NAME = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task"

    /** Where the model is stored on the device. Must match GemmaInferenceHelper. */
    const val LOCAL_FILE_NAME = "gemma_local_model.task"

    /**
     * Ordered mirrors. The first is preferred; the rest are tried if it fails, so a bad day on
     * one host doesn't take the whole feature down for every user.
     *
     * The release must be PUBLIC — a private repo serves assets only to authenticated
     * requests, and this app sends no token.
     */
    val mirrors: List<ModelMirror> = listOf(
        ModelMirror(
            label = "GitHub Release",
            url = "https://github.com/SimranjeetSingh5/FinanceTrackerAI/releases/download/" +
                "gemma-v2/$ASSET_FILE_NAME",
            sha256 = "ddfaf1210d8b4d1b812b5fadb6652999e852c8be6dd9abe353b9213a25262c10"
        )
    )

    data class ModelMirror(
        val label: String,
        val url: String,
        /** Hex SHA-256 of the file, or empty to skip verification. */
        val sha256: String
    )
}
