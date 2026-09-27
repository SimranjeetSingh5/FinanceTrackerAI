package com.financetracker.ai.ai

import com.financetracker.ai.data.Category
import com.financetracker.ai.data.Transaction
import com.financetracker.ai.data.TransactionType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Deterministic analysis of spending, computed with plain arithmetic instead of the model.
 *
 * Most of what a "spending insight" contains — totals, the biggest category, how much of the
 * budget is gone, whether a month is up or down on the last one — is arithmetic. Doing it here
 * returns in microseconds and is exactly correct; the LLM's only real job is turning the numbers
 * into a readable sentence, which is what [AiInsightEngine] is for. Keeping the two apart means a
 * slow or missing model never blocks the numbers.
 */
data class SpendingSummary(
    val income: Double,
    val spending: Double,
    val net: Double,
    val topCategory: String?,
    val topCategoryAmount: Double,
    val topCategorySharePct: Int,
    val topMerchant: String?,
    val topMerchantAmount: Double,
    /** Signed month-over-month change in spending, or null when there's no prior month. */
    val changeVsLastMonthPct: Int?,
    /** Categories over budget this month, as "Name (103%)". */
    val overBudget: List<String>,
    val transactionCount: Int
) {
    val isUpVsLastMonth: Boolean? get() = changeVsLastMonthPct?.let { it > 0 }
    val surplus: Boolean get() = net >= 0
}

object SpendingFacts {

    /**
     * Summarises [transactions] (all of the current month) and optionally compares against
     * [previousMonth] when it's available.
     */
    fun summarize(
        transactions: List<Transaction>,
        categories: List<Category>,
        previousMonth: List<Transaction> = emptyList()
    ): SpendingSummary {
        val categoryMap = categories.associateBy { it.id }
        val budgetByCategory = categories.mapNotNull { cat ->
            cat.monthlyBudget?.let { cat.id to it }
        }.toMap()

        val income = transactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
        val expenses = transactions.filter { it.type == TransactionType.EXPENSE }
        val spending = expenses.sumOf { it.amount }

        val byCategory = expenses
            .groupBy { categoryMap[it.categoryId]?.name ?: "Uncategorized" }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }

        val top = byCategory.entries.maxByOrNull { it.value }
        val topAmount = top?.value ?: 0.0
        val topShare = if (spending > 0) ((topAmount * 100) / spending).roundToInt() else 0

        val topMerchantEntry = expenses
            .filter { !it.isRecurringInstance } // subscriptions repeat; they skew "top"
            .groupingBy { it.merchant?.takeIf { m -> m.isNotBlank() } ?: it.note }
            .fold(0.0) { acc, t -> acc + t.amount }
            .maxByOrNull { it.value }

        val changePct = if (previousMonth.isEmpty()) {
            null
        } else {
            val prevSpending = previousMonth
                .filter { it.type == TransactionType.EXPENSE }
                .sumOf { it.amount }
            if (prevSpending <= 0) null
            else (((spending - prevSpending) * 100) / prevSpending).roundToInt()
        }

        val overBudget = budgetByCategory.mapNotNull { (categoryId, limit) ->
            if (limit <= 0) return@mapNotNull null
            val name = categoryMap[categoryId]?.name ?: return@mapNotNull null
            val spent = byCategory[name] ?: 0.0
            val pct = ((spent * 100) / limit).roundToInt()
            if (pct >= 100) "$name ($pct%)" else null
        }.sorted()

        return SpendingSummary(
            income = income,
            spending = spending,
            net = income - spending,
            topCategory = top?.key,
            topCategoryAmount = topAmount,
            topCategorySharePct = topShare,
            topMerchant = topMerchantEntry?.key,
            topMerchantAmount = topMerchantEntry?.value ?: 0.0,
            changeVsLastMonthPct = changePct,
            overBudget = overBudget,
            transactionCount = transactions.size
        )
    }

    /**
     * A readable summary built purely from the arithmetic — no model involved, so it renders
     * immediately. Intentionally plain: the value is that it's instant and always right.
     */
    fun toPlainText(summary: SpendingSummary, periodLabel: String): String = buildString {
        append("For $periodLabel you spent ")
        append(money(summary.spending))
        append(" and received ")
        append(money(summary.income))
        append(" — ")
        append(if (summary.surplus) "a surplus of " else "a shortfall of ")
        append(money(abs(summary.net)))
        append(".")

        summary.topCategory?.let {
            append(" Your biggest category was $it at ")
            append(money(summary.topCategoryAmount))
            append(" (${summary.topCategorySharePct}% of spending).")
        }

        summary.changeVsLastMonthPct?.let { change ->
            val direction = if (change > 0) "up" else "down"
            append(" Spending is $direction ${abs(change)}% on last month.")
        }

        if (summary.overBudget.isNotEmpty()) {
            append(" Over budget: ${summary.overBudget.joinToString(", ")}.")
        }

        summary.topMerchant?.takeIf { summary.topMerchantAmount >= 20.0 }?.let {
            append(" Your largest single purchase was $it at ${money(summary.topMerchantAmount)}.")
        }
    }

    private fun money(value: Double): String =
        if (abs(value) >= 1000) String.format(Locale.US, "%,.0f", value) else String.format(Locale.US, "%.2f", value)
}
