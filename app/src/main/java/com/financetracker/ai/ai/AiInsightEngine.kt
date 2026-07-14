package com.financetracker.ai.ai

import android.content.Context
import com.financetracker.ai.data.Category
import com.financetracker.ai.data.Transaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/**
 * Builds deterministic, optimized prompt pipelines for local Gemma inference.
 * Isolates prompt engineering constraints from underlying engine plumbing.
 */
class AiInsightEngine(private val gemma: GemmaInferenceHelper) {

    data class CategorizationResult(val categoryName: String, val confidence: Float, val reasoning: String)

    // Confine SimpleDateFormat to a ThreadLocal wrapper to ensure absolute thread safety
    private val threadLocalFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("MMM d", Locale.getDefault())
        }
    }

    /**
     * Queries the local inference engine to match a transaction transaction against available user budgets.
     */
    suspend fun categorizeTransaction(
        note: String,
        merchant: String?,
        amount: Double,
        categories: List<Category>
    ): Result<CategorizationResult> {
        if (categories.isEmpty()) {
            return Result.failure(IllegalArgumentException("Category reference directory list cannot be empty."))
        }

        val categoryNames = categories.joinToString(", ") { it.name }
        val prompt = """
            You are a finance categorization assistant. Given a transaction, pick exactly one
            category from this list: $categoryNames

            Transaction:
            - Merchant/description: ${merchant ?: note}
            - Note: $note
            - Amount: $amount

            Respond ONLY with strict JSON, no other conversational filler, markdown fences, or text, matching this structure:
            {"category": "<one of the list above>", "confidence": <0.0-1.0>, "reasoning": "<one short sentence>"}
        """.trimIndent()

        // Switch to our local helper's explicit synchronous pipeline method
        return gemma.generateResponse(prompt).mapCatching { raw ->
            val cleanJson = extractJson(raw)
            val json = JSONObject(cleanJson)
            CategorizationResult(
                categoryName = json.getString("category"),
                confidence = json.optDouble("confidence", 0.5).toFloat(),
                reasoning = json.optString("reasoning", "")
            )
        }
    }

    /**
     * Generates an isolated textual spending breakdown execution for your dashboard layer.
     */
    suspend fun generateSpendingInsight(
        transactions: List<Transaction>,
        categories: List<Category>,
        periodLabel: String
    ): Result<String> {
        if (transactions.isEmpty()) {
            return Result.success("No transaction tracking history recorded for $periodLabel.")
        }

        val categoryMap = categories.associateBy { it.id }
        val df = threadLocalFormatter.get() ?: SimpleDateFormat("MMM d", Locale.getDefault())

        val lines = transactions.take(60).joinToString("\n") { t ->
            val catName = categoryMap[t.categoryId]?.name ?: "Uncategorized"
            "${df.format(Date(t.timestamp))} \\vert{}$catName | ${t.type} \\vert{}${t.amount}"
        }

        val prompt = """
            You are a friendly personal finance coach. Below is a list of transactions for
            $periodLabel (date | category | type | amount):

            $lines

            Write a concise 3-4 sentence summary: highlight the biggest spending category,
            any notable spike vs. typical patterns, and one practical, encouraging tip.
            Do not invent numbers or metrics that aren't in the data. Keep it warm and non-judgmental.
        """.trimIndent()

        return gemma.generateResponse(prompt)
    }

    /**
     * Exposes a token-by-token text generation flow pipeline for terminal chat interface elements.
     */
    fun chatStream(
        userMessage: String,
        conversationHistory: List<Pair<String, String>>, // (role, content)
        recentTransactions: List<Transaction>,
        categories: List<Category>
    ): Flow<String> {
        val categoryMap = categories.associateBy { it.id }
        val df = threadLocalFormatter.get() ?: SimpleDateFormat("MMM d", Locale.getDefault())

        val txContext = recentTransactions.take(40).joinToString("\n") { t ->
            val catName = categoryMap[t.categoryId]?.name ?: "Uncategorized"
            "${df.format(Date(t.timestamp))}: ${t.type} of${t.amount} in $catName (${t.note})"
        }
        val history = conversationHistory.takeLast(6).joinToString("\n") { (role, content) ->
            "$role:$content"
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

        // Point explicitly to the real local callbackFlow stream builder we engineered
        return gemma.generateResponseStream(prompt)
    }

    /**
     * Senior-grade JSON boundaries cleaning method. Handles structural markdown syntax
     * cleaning to prevent parse failures if local engine outputs formatting code blocks.
     */
    private fun extractJson(raw: String): String {
        var clean = raw.trim()

        // Strip markdown backticks block syntax if the model included it
        if (clean.startsWith("```")) {
            clean = clean.replace(Regex("^```(?:json)?"), "").replace(Regex("```$"), "").trim()
        }

        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')

        if (start == -1 || end == -1 || end <= start) {
            throw IllegalStateException("Failed to parse valid object frame bounds inside string data payload.")
        }

        return clean.substring(start, end + 1)
    }
}