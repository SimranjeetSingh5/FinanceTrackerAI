package com.financetracker.ai.ai

import android.content.Context
import com.financetracker.ai.data.Category
import com.financetracker.ai.data.Transaction
import com.financetracker.ai.data.TransactionType
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
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

    private val threadLocalFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("MMM d", Locale.getDefault())
        }
    }

    /**
     * Queries the local inference engine to match a transaction against available categories.
     */
    suspend fun categorizeTransaction(
        note: String,
        merchant: String?,
        amount: Double,
        categories: List<Category>
    ): Result<CategorizationResult> {
        if (categories.isEmpty()) {
            return Result.failure(IllegalArgumentException("Category list cannot be empty."))
        }

        val categoryNames = categories.joinToString(", ") { it.name }
        val prompt = """
            Categorize this transaction into exactly one category: $categoryNames
            Merchant: ${merchant ?: note} | Note: $note | Amount: $amount
            JSON only: {"category": "<name>", "confidence": <0.0-1.0>, "reasoning": "<short>"}
        """.trimIndent()

        return gemma.generateResponse(prompt).mapCatching { raw ->
            val json = JSONObject(extractJson(raw))
            CategorizationResult(
                categoryName = json.getString("category"),
                confidence = json.optDouble("confidence", 0.5).toFloat(),
                reasoning = json.optString("reasoning", "")
            )
        }
    }

    /**
     * Batch variant of categorizeTransaction — sends the category list once for N transactions
     * instead of paying that prefill cost per-transaction. Use this for bulk import flows.
     */
    suspend fun categorizeTransactionsBatch(
        items: List<Triple<String, String?, Double>>, // (note, merchant, amount)
        categories: List<Category>
    ): Result<List<CategorizationResult>> {
        if (categories.isEmpty()) {
            return Result.failure(IllegalArgumentException("Category list cannot be empty."))
        }
        if (items.isEmpty()) {
            return Result.success(emptyList())
        }

        val categoryNames = categories.joinToString(", ") { it.name }
        val txList = items.mapIndexed { i, (note, merchant, amount) ->
            "$i: merchant=${merchant ?: note}, note=$note, amount=$amount"
        }.joinToString("\n")

        val prompt = """
            Categorize each transaction below into exactly one category: $categoryNames
            Transactions:
            $txList
            JSON array only, one object per transaction in order:
            [{"category": "<name>", "confidence": <0.0-1.0>, "reasoning": "<short>"}, ...]
        """.trimIndent()

        return gemma.generateResponse(prompt).mapCatching { raw ->
            val arr = JSONArray(extractJsonArray(raw))
            (0 until arr.length()).map { i ->
                val json = arr.getJSONObject(i)
                CategorizationResult(
                    categoryName = json.getString("category"),
                    confidence = json.optDouble("confidence", 0.5).toFloat(),
                    reasoning = json.optString("reasoning", "")
                )
            }
        }
    }

    /**
     * Turns an already-computed [SpendingSummary] into prose.
     *
     * The numbers arrive pre-aggregated from [SpendingFacts], so the prompt is a few hundred
     * characters and prefill is negligible — the cost is purely token generation. That also
     * means the model can't invent or miscount a figure: it is only ever handed the values it
     * is allowed to repeat.
     */
    suspend fun generateSpendingInsight(
        summary: SpendingSummary,
        periodLabel: String
    ): Result<String> {
        if (summary.transactionCount == 0) {
            return Result.success("No transaction history recorded for $periodLabel.")
        }

        val facts = buildString {
            append("Spending data for $periodLabel:\n")
            append("- Total spending: ${round2(summary.spending)}\n")
            append("- Total income: ${round2(summary.income)}\n")
            summary.topCategory?.let {
                append("- Biggest category: $it (${round2(summary.topCategoryAmount)}, ${summary.topCategorySharePct}%)\n")
            }
            summary.changeVsLastMonthPct?.let {
                append("- Versus last month: ${if (it > 0) "+" else ""}$it%\n")
            }
            if (summary.overBudget.isNotEmpty()) {
                append("- Over budget: ${summary.overBudget.joinToString(", ")}\n")
            }
        }.trim()

        val prompt = """
            $facts
            In 2-3 sentences: comment on the biggest category, anything notable, and give one
            practical saving tip. Use only these figures. Plain prose, no lists or headings.
        """.trimIndent().trim()

        return gemma.generateResponse(prompt, temperature = 0.4f)
    }

    /**
     * Exposes a token-by-token text generation flow for the chat interface.
     */
    fun chatStream(
        userMessage: String,
        conversationHistory: List<Pair<String, String>>, // (role, content)
        recentTransactions: List<Transaction>,
        categories: List<Category>
    ): Flow<String> {
        val categoryMap = categories.associateBy { it.id }
        val df = threadLocalFormatter.get() ?: SimpleDateFormat("MMM d", Locale.getDefault())

        val txContext = recentTransactions.take(15).joinToString("\n") { t ->
            val catName = categoryMap[t.categoryId]?.name ?: "Uncategorized"
            "${df.format(Date(t.timestamp))}: ${t.type} of ${t.amount} in $catName (${t.note})"
        }
        val history = conversationHistory.takeLast(4).joinToString("\n") { (role, content) ->
            "$role:$content"
        }

        val prompt = """
            On-device finance assistant. Answer using ONLY this data; say so if it's not there.

            Recent transactions:
            $txContext

            $history
            User: $userMessage
            Assistant:
        """.trimIndent()

        return gemma.generateResponseStream(prompt)
    }

    /** Rounds to 2dp and trims a trailing ".0" so prompt numbers stay short. */
    private fun round2(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }

    /**
     * Strips markdown fences and extracts a single JSON object from model output.
     */
    private fun extractJson(raw: String): String {
        var clean = raw.trim()
        if (clean.startsWith("```")) {
            clean = clean.replace(Regex("^```(?:json)?"), "").replace(Regex("```$"), "").trim()
        }
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) {
            throw IllegalStateException("Failed to parse JSON object from model output.")
        }
        return clean.substring(start, end + 1)
    }

    /**
     * Strips markdown fences and extracts a JSON array from model output.
     */
    private fun extractJsonArray(raw: String): String {
        var clean = raw.trim()
        if (clean.startsWith("```")) {
            clean = clean.replace(Regex("^```(?:json)?"), "").replace(Regex("```$"), "").trim()
        }
        val start = clean.indexOf('[')
        val end = clean.lastIndexOf(']')
        if (start == -1 || end == -1 || end <= start) {
            throw IllegalStateException("Failed to parse JSON array from model output.")
        }
        return clean.substring(start, end + 1)
    }
}