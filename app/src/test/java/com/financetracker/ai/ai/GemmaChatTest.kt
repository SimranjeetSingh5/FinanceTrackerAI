package com.financetracker.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the prompt shapes that have to stay byte-stable.
 *
 * A malformed turn marker makes an instruction-tuned Gemma terminate immediately and emit
 * nothing, which is indistinguishable from a broken model on device. These run on the JVM, so a
 * regression is caught before it ever reaches a phone.
 */
class GemmaChatTest {

    @Test
    fun `a user turn is balanced`() {
        val turn = GemmaChat.user("hello")
        assertEquals(1, turn.split(GemmaChat.START).size - 1)
        assertEquals(1, turn.split(GemmaChat.END).size - 1)
        assertTrue(turn.startsWith(GemmaChat.START))
        assertTrue(turn.trimEnd().endsWith(GemmaChat.END))
    }

    @Test
    fun `the model turn is left open so generation continues from it`() {
        val turn = GemmaChat.modelTurnOpen()
        assertTrue(turn.startsWith(GemmaChat.START))
        assertTrue("model turn must not be closed", !turn.contains(GemmaChat.END))
    }

    @Test
    fun `every turn marker in a built prompt is balanced`() {
        val prompt = GemmaChat.buildPrompt(
            systemInstruction = GemmaChat.SYSTEM_FINANCE,
            history = listOf(
                "user" to "how much did I spend?",
                "assistant" to "you spent 340",
                "user" to "on what?"
            ),
            userMessage = "on food"
        )
        // The final model turn is deliberately open, hence +1 on the start count.
        val starts = prompt.split(GemmaChat.START).size - 1
        val ends = prompt.split(GemmaChat.END).size - 1
        assertEquals("only the final model turn may be open", starts, ends + 1)
    }

    @Test
    fun `prompt ends with an open model turn`() {
        val prompt = GemmaChat.buildPrompt(
            systemInstruction = GemmaChat.SYSTEM_FINANCE,
            userMessage = "hello"
        )
        assertTrue(prompt.trimEnd().endsWith("model"))
    }

    @Test
    fun `prior assistant replies are replayed as model turns`() {
        val prompt = GemmaChat.buildPrompt(
            systemInstruction = GemmaChat.SYSTEM_FINANCE,
            history = listOf("assistant" to "you spent 340"),
            userMessage = "on what?"
        )
        assertTrue(
            "assistant history must be wrapped as a closed model turn",
            prompt.contains(GemmaChat.model("you spent 340"))
        )
    }

    @Test
    fun `blank history entries are skipped rather than emitting empty turns`() {
        val prompt = GemmaChat.buildPrompt(
            systemInstruction = "",
            history = listOf("user" to "", "assistant" to "   "),
            userMessage = "hi"
        )
        assertEquals(2, prompt.split(GemmaChat.START).size - 1)
    }

    @Test
    fun `an unknown role is treated as the user`() {
        val prompt = GemmaChat.buildPrompt(
            systemInstruction = "",
            history = listOf("tool" to "some data"),
            userMessage = "hi"
        )
        assertTrue(prompt.contains(">user\nsome data"))
    }

    /**
     * Guards the exact failure seen on device: the model returned nothing for every real
     * prompt while still answering the bare "Hi" smoke test.
     *
     * The prompt shape produced two consecutive user turns — the system preamble was sent as
     * one, and the question as another, with no assistant turn between them. Gemma 3 does not
     * reliably continue from that, so it terminated instead of answering.
     */
    @Test
    fun `chat turns alternate user and model`() {
        val prompt = GemmaChat.buildPrompt(
            systemInstruction = GemmaChat.SYSTEM_FINANCE,
            userMessage = "hey"
        )
        val roles = Regex(">(\\w+)\\n").findAll(prompt).map { it.groupValues[1] }.toList()
        assertEquals(listOf("model", "user", "model"), roles)
    }

    @Test
    fun `a prompt never contains two turns of the same role in a row`() {
        val prompt = GemmaChat.buildPrompt(
            systemInstruction = GemmaChat.SYSTEM_FINANCE,
            history = listOf("user" to "earlier question", "assistant" to "earlier answer"),
            userMessage = "new question"
        )
        val roles = Regex(">(\\w+)\\n").findAll(prompt).map { it.groupValues[1] }.toList()
        for (i in 1 until roles.size) {
            assertFalse(
                "consecutive ${roles[i - 1]} turns at index $i in: $roles",
                roles[i] == roles[i - 1]
            )
        }
    }
}
