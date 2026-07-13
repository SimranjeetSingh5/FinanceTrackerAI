package com.financetracker.ai.ai

import com.financetracker.ai.data.Category
import com.financetracker.ai.data.Transaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Builds prompts for Gemma and parses its responses. Kept separate from GemmaInferenceHelper so
 * the prompt-engineering logic (the part you'll want to tune) is isolated from the plumbing.
 */
class AiInsightEngine(private val gemma: GemmaInferenceHelper) {

    data class CategorizationResult(val categoryName: String, val confidence: Float, val reasoning: String)

    /** Ask Gemma to pick the best category for a transaction from the user's own category list. */
    suspend fun categorizeTransaction(
        note: String,
        merchant: String?,
        amount: Double,
        categories: List<Category>
    ): Result<CategorizationResult> {
        val categoryNames = categories.joinToString(", ") { it.name }
        val prompt = """
            You are a finance categorization assistant. Given a transaction, pick exactly one
            category from this list: $categoryNames

            Transaction:
            - Merchant/description: ${merchant ?: note}
            - Note: $note
            - Amount: $amount

            Respond ONLY with strict JSON, no other text, in this exact shape:
            {"category": "<one of the list above>", "confidence": <0.0-1.0>, "reasoning": "<one short sentence>"}
        """.trimIndent()

        return gemma.generate(prompt).mapCatching { raw ->
            val json = JSONObject(extractJson(raw))
            CategorizationResult(
                categoryName = json.getString("category"),
                confidence = json.optDouble("confidence", 0.5).toFloat(),
                reasoning = json.optString("reasoning", "")
            )
        }
    }

    /** Generates a short natural-language spending summary for a period, entirely on-device. */
    suspend fun generateSpendingInsight(
        transactions: List<Transaction>,
        categories: List<Category>,
        periodLabel: String
    ): Result<String> {
        val categoryMap = categories.associateBy { it.id }
        val df = SimpleDateFormat("MMM d", Locale.getDefault())
        val lines = transactions.take(60).joinToString("\n") { t ->
            val catName = categoryMap[t.categoryId]?.name ?: "Uncategorized"
            "${df.format(Date(t.timestamp))} | $catName | ${t.type} | ${t.amount}"
        }

        val prompt = """
            You are a friendly personal finance coach. Below is a list of transactions for
            $periodLabel (date | category | type | amount):

            $lines

            Write a concise 3-4 sentence summary: highlight the biggest spending category,
            any notable spike vs. typical patterns, and one practical, encouraging tip.
            Do not invent numbers that aren't in the data. Keep it warm and non-judgmental.
        """.trimIndent()

        return gemma.generate(prompt)
    }

    /** Streaming chat for the "Ask your finances" assistant screen. Includes recent transaction
     *  context so the user can ask things like "how much did I spend on food this month?". */
    fun chatStream(
        userMessage: String,
        conversationHistory: List<Pair<String, String>>, // (role, content)
        recentTransactions: List<Transaction>,
        categories: List<Category>
    ): Flow<String> {
        val categoryMap = categories.associateBy { it.id }
        val df = SimpleDateFormat("MMM d", Locale.getDefault())
        val txContext = recentTransactions.take(40).joinToString("\n") { t ->
            val catName = categoryMap[t.categoryId]?.name ?: "Uncategorized"
            "${df.format(Date(t.timestamp))}: ${t.type} of ${t.amount} in $catName (${t.note})"
        }
        val history = conversationHistory.takeLast(6).joinToString("\n") { (role, content) ->
            "$role: $content"
        }

        val prompt = """
            You are an on-device personal finance assistant. Answer using ONLY the transaction
            data provided below. If the data doesn't contain the answer, say so honestly rather
            than guessing. Be concise and specific with numbers.

            Recent transactions:
            $txContext

            Conversation so far:
            $history

            User: $userMessage
            Assistant:
        """.trimIndent()

        return gemma.generateStream(prompt)
    }

    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start) raw.substring(start, end + 1) else raw
    }
}
