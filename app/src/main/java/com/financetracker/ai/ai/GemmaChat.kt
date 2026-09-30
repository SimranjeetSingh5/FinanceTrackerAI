package com.financetracker.ai.ai

/**
 * Gemma 3 chat formatting.
 *
 * Instruction-tuned Gemma models are trained on a specific turn structure and behave badly
 * without it — the common symptoms are echoing the prompt back verbatim, or returning nothing
 * at all when the turn markers are unbalanced. The MediaPipe runtime does not insert these
 * tokens for you: whatever string is handed to the session is what the model sees.
 *
 * The control tokens are part of Gemma's vocabulary, so they cost nothing to emit here. If you
 * ever swap in a model that doesn't use them, this is the single place to change.
 */
object GemmaChat {

    const val START = "<start_of_turn>"
    const val END = "<end_of_turn>"

    /** A user turn, closed. */
    fun user(content: String): String = "$START>user\n$content$END\n"

    /** A model turn, closed. Used when replaying prior assistant replies. */
    fun model(content: String): String = "$START>model\n$content$END\n"

    /**
     * Opens a model turn and leaves it open, so the model continues directly from here. This is
     * what a completion prompt ends with.
     */
    fun modelTurnOpen(): String = "$START>model\n"

    /**
     * Builds a completion prompt from prior turns plus the user's latest message.
     *
     * Roles map onto Gemma's two turn types; anything unrecognised is treated as the user, so a
     * malformed history still produces a valid prompt rather than a broken one.
     */
    fun buildPrompt(
        systemInstruction: String,
        history: List<Pair<String, String>> = emptyList(),
        userMessage: String
    ): String = buildString {
        // The system instruction goes in a MODEL turn, not a user turn. Sending it as a user
        // turn produces two consecutive user turns once the real question follows, and Gemma 3
        // does not reliably continue from that — it terminates and emits nothing.
        if (systemInstruction.isNotBlank()) append(model(systemInstruction))
        history.forEach { (role, content) ->
            if (content.isBlank()) return@forEach
            if (role.equals("assistant", ignoreCase = true)) {
                append(model(content))
            } else {
                append(user(content))
            }
        }
        append(user(userMessage))
        append(modelTurnOpen())
    }

    /**
     * Stated once rather than repeated in every prompt: it makes the model treat the supplied
     * numbers as the only permitted source and discourages the echoing that an unconstrained
     * chat prompt tends to produce.
     */
    const val SYSTEM_FINANCE =
        "You are a personal finance assistant. Answer using only the data provided in the " +
            "conversation. If the data does not contain the answer, say so plainly. Never " +
            "invent figures, and never repeat the question or the data back verbatim."
}
