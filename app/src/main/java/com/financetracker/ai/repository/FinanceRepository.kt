package com.financetracker.ai.repository

import com.financetracker.ai.ai.AiInsightEngine
import com.financetracker.ai.data.*
import kotlinx.coroutines.flow.Flow
import java.util.*

class FinanceRepository(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val budgetDao: BudgetDao,
    private val chatDao: ChatDao,
    private val accountDao: AccountDao,
    private val goalDao: GoalDao,
    private val recurringDao: RecurringDao,
    private val aiEngine: AiInsightEngine
) {
    val allTransactions: Flow<List<Transaction>> = transactionDao.getAll()
    val allCategories: Flow<List<Category>> = categoryDao.getAll()
    val chatHistory: Flow<List<ChatMessage>> = chatDao.getAll()
    val allAccounts: Flow<List<Account>> = accountDao.getAll()
    val allGoals: Flow<List<Goal>> = goalDao.getAll()
    val allRecurring: Flow<List<RecurringTransaction>> = recurringDao.getAll()

    // ---------- Setup / seeding ----------

    suspend fun ensureDefaultCategories() {
        if (categoryDao.count() == 0) {
            categoryDao.insertAll(AppDatabase.DEFAULT_CATEGORIES)
        }
    }

    suspend fun ensureDefaultAccount(): Long {
        val existing = accountDao.getAllOnce()
        if (existing.isNotEmpty()) return existing.first().id
        return accountDao.insert(Account(name = "Cash", type = AccountType.CASH, colorHex = "#7CB342", icon = "wallet"))
    }

    // ---------- Transactions ----------

    suspend fun addTransaction(
        amount: Double,
        note: String,
        merchant: String?,
        type: TransactionType,
        accountId: Long,
        manualCategoryId: Long?,
        timestamp: Long = System.currentTimeMillis(),
        useAiCategorization: Boolean = true,
        transferToAccountId: Long? = null
    ): Transaction {
        var categoryId = manualCategoryId
        var aiCategorized = false
        var confidence: Float? = null

        if (categoryId == null && useAiCategorization && type != TransactionType.TRANSFER) {
            val categories = categoryDao.getAllOnce()
            aiEngine.categorizeTransaction(note, merchant, amount, categories).onSuccess { result ->
                val match = categories.firstOrNull { it.name.equals(result.categoryName, ignoreCase = true) }
                if (match != null) {
                    categoryId = match.id
                    aiCategorized = true
                    confidence = result.confidence
                }
            }
        }

        if (categoryId == null) {
            categoryId = categoryDao.getAllOnce().firstOrNull { it.name == "Other" }?.id ?: 0
        }

        val transaction = Transaction(
            amount = amount,
            note = note,
            merchant = merchant,
            categoryId = categoryId!!,
            accountId = accountId,
            transferToAccountId = transferToAccountId,
            type = type,
            timestamp = timestamp,
            aiCategorized = aiCategorized,
            aiConfidence = confidence
        )
        val id = transactionDao.insert(transaction)
        return transaction.copy(id = id)
    }

    suspend fun deleteTransaction(transaction: Transaction) = transactionDao.delete(transaction)
    suspend fun updateTransaction(transaction: Transaction) = transactionDao.update(transaction)

    fun searchTransactions(
        query: String = "",
        categoryId: Long? = null,
        accountId: Long? = null,
        type: TransactionType? = null,
        start: Long = 0L,
        end: Long = Long.MAX_VALUE
    ): Flow<List<Transaction>> =
        transactionDao.search(query, categoryId, accountId, type?.name, start, end)

    suspend fun totalsFor(start: Long, end: Long): Pair<Double, Double> {
        val income = transactionDao.totalIncome(start, end) ?: 0.0
        val expense = transactionDao.totalExpenses(start, end) ?: 0.0
        return income to expense
    }

    suspend fun categoryBreakdown(start: Long, end: Long): List<CategoryTotal> =
        transactionDao.categoryTotals(start, end)

    suspend fun allTransactionsForExport(): List<Transaction> = transactionDao.allForExport()

    // ---------- Accounts ----------

    suspend fun addAccount(account: Account): Long = accountDao.insert(account)
    suspend fun updateAccount(account: Account) = accountDao.update(account)
    suspend fun archiveAccount(account: Account) = accountDao.update(account.copy(isArchived = true))

    suspend fun accountBalance(account: Account): Double =
        account.startingBalance + (transactionDao.netForAccount(account.id) ?: 0.0)

    suspend fun netWorth(): Double {
        val accounts = accountDao.getAllOnce()
        return accounts.sumOf { accountBalance(it) }
    }

    // ---------- Budgets ----------

    fun budgetsForMonth(monthYear: String): Flow<List<Budget>> = budgetDao.getForMonth(monthYear)

    suspend fun upsertBudget(categoryId: Long, monthYear: String, limit: Double, alertThresholdPct: Int = 90) {
        val existing = budgetDao.findFor(categoryId, monthYear)
        budgetDao.upsert(
            existing?.copy(limitAmount = limit, alertThresholdPct = alertThresholdPct)
                ?: Budget(categoryId = categoryId, monthYear = monthYear, limitAmount = limit, alertThresholdPct = alertThresholdPct)
        )
    }

    suspend fun deleteBudget(budget: Budget) = budgetDao.delete(budget)

    suspend fun updateCategoryBudget(category: Category, limit: Double?) {
        categoryDao.update(category.copy(monthlyBudget = limit))
    }

    /** Checks each category's monthlyBudget field against actual spend for the month and
     *  invokes [onAlert] for any category that has crossed 90% usage. */
    suspend fun checkBudgetAlerts(monthYear: String, onAlert: (categoryName: String, pctUsed: Int, categoryId: Long) -> Unit) {
        val cal = Calendar.getInstance()
        val parts = monthYear.split("-")
        cal.set(parts[0].toInt(), parts[1].toInt() - 1, 1, 0, 0, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis - 1

        val totals = transactionDao.categoryTotals(start, end).associateBy { it.categoryId }
        val categories = categoryDao.getAllOnce()

        categories.forEach { category ->
            val limit = category.monthlyBudget ?: return@forEach
            val spent = totals[category.id]?.total ?: 0.0
            val pct = if (limit > 0) ((spent / limit) * 100).toInt() else 0
            if (pct >= 90) onAlert(category.name, pct, category.id)
        }
    }

    // ---------- Goals ----------

    suspend fun addGoal(goal: Goal): Long = goalDao.insert(goal)
    suspend fun updateGoal(goal: Goal) = goalDao.update(goal)
    suspend fun deleteGoal(goal: Goal) = goalDao.delete(goal)

    suspend fun contributeToGoal(goal: Goal, amount: Double) {
        val newAmount = goal.currentAmount + amount
        goalDao.update(goal.copy(currentAmount = newAmount, isCompleted = newAmount >= goal.targetAmount))
    }

    // ---------- Recurring transactions ----------

    suspend fun addRecurring(recurring: RecurringTransaction): Long = recurringDao.insert(recurring)
    suspend fun updateRecurring(recurring: RecurringTransaction) = recurringDao.update(recurring)
    suspend fun deleteRecurring(recurring: RecurringTransaction) = recurringDao.delete(recurring)

    /** Advances due recurring items: if autoAdd is on, creates the transaction and rolls the
     *  due date forward; otherwise just fires the reminder callback. */
    suspend fun processDueRecurringTransactions(onReminder: (name: String, amount: Double, id: Long) -> Unit) {
        val now = System.currentTimeMillis()
        val due = recurringDao.getDue(now)
        due.forEach { item ->
            if (item.autoAdd) {
                transactionDao.insert(
                    Transaction(
                        amount = item.amount,
                        note = item.note,
                        merchant = item.merchant,
                        categoryId = item.categoryId,
                        accountId = item.accountId,
                        type = item.type,
                        timestamp = now,
                        isRecurringInstance = true,
                        recurringTransactionId = item.id
                    )
                )
            } else if (item.reminderEnabled) {
                onReminder(item.note.ifBlank { item.merchant ?: "Recurring payment" }, item.amount, item.id)
            }
            recurringDao.update(item.copy(nextDueDate = nextDueDate(item), lastProcessedDate = now))
        }
    }

    private fun nextDueDate(item: RecurringTransaction): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = item.nextDueDate }
        when (item.frequency) {
            RecurrenceFrequency.DAILY -> cal.add(Calendar.DAY_OF_YEAR, 1)
            RecurrenceFrequency.WEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, 1)
            RecurrenceFrequency.BIWEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, 2)
            RecurrenceFrequency.MONTHLY -> cal.add(Calendar.MONTH, 1)
            RecurrenceFrequency.YEARLY -> cal.add(Calendar.YEAR, 1)
        }
        return cal.timeInMillis
    }

    // ---------- AI ----------

    suspend fun getInsight(periodLabel: String): Result<String> {
        val txs = transactionDao.recent(60)
        val categories = categoryDao.getAllOnce()
        return aiEngine.generateSpendingInsight(txs, categories, periodLabel)
    }

    suspend fun saveChatMessage(role: String, content: String) {
        chatDao.insert(ChatMessage(role = role, content = content, timestamp = System.currentTimeMillis()))
    }

    suspend fun clearChat() = chatDao.clear()

    val aiEngineRef get() = aiEngine
}
