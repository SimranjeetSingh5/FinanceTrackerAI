package com.financetracker.ai.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAll(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getBetween(start: Long, end: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE categoryId = :categoryId ORDER BY timestamp DESC")
    fun getByCategory(categoryId: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY timestamp DESC")
    fun getByAccount(accountId: Long): Flow<List<Transaction>>

    @Query("""
        SELECT * FROM transactions
        WHERE (:query = '' OR note LIKE '%' || :query || '%' OR merchant LIKE '%' || :query || '%')
        AND (:categoryId IS NULL OR categoryId = :categoryId)
        AND (:accountId IS NULL OR accountId = :accountId)
        AND (:type IS NULL OR type = :type)
        AND timestamp BETWEEN :start AND :end
        ORDER BY timestamp DESC
    """)
    fun search(
        query: String,
        categoryId: Long?,
        accountId: Long?,
        type: String?,
        start: Long,
        end: Long
    ): Flow<List<Transaction>>

    @Query("SELECT SUM(amount) FROM transactions WHERE type = 'EXPENSE' AND timestamp BETWEEN :start AND :end")
    suspend fun totalExpenses(start: Long, end: Long): Double?

    @Query("SELECT SUM(amount) FROM transactions WHERE type = 'INCOME' AND timestamp BETWEEN :start AND :end")
    suspend fun totalIncome(start: Long, end: Long): Double?

    @Query("""
        SELECT SUM(
            CASE type
                WHEN 'INCOME' THEN amount
                WHEN 'EXPENSE' THEN -amount
                WHEN 'TRANSFER' THEN -amount
                ELSE 0
            END
        )
        FROM transactions WHERE accountId = :accountId
    """)
    suspend fun netForAccount(accountId: Long): Double?

    /**
     * A transfer leaves the source account via [netForAccount] and arrives here, so the same
     * transaction is counted from both ends and balances across accounts stay conserved.
     */
    @Query("""
        SELECT SUM(amount) FROM transactions
        WHERE type = 'TRANSFER' AND transferToAccountId = :accountId
    """)
    suspend fun transfersIntoAccount(accountId: Long): Double?

    @Query("""
        SELECT categoryId, SUM(amount) as total FROM transactions
        WHERE type = 'EXPENSE' AND timestamp BETWEEN :start AND :end
        GROUP BY categoryId ORDER BY total DESC
    """)
    suspend fun categoryTotals(start: Long, end: Long): List<CategoryTotal>

    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Update
    suspend fun update(transaction: Transaction)

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<Transaction>

    @Query("SELECT * FROM transactions ORDER BY timestamp ASC")
    suspend fun allForExport(): List<Transaction>
}

data class CategoryTotal(val categoryId: Long, val total: Double)

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun getAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY name ASC")
    suspend fun getAllOnce(): List<Category>

    @Insert
    suspend fun insert(category: Category): Long

    @Insert
    suspend fun insertAll(categories: List<Category>)

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE monthYear = :monthYear")
    fun getForMonth(monthYear: String): Flow<List<Budget>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: Budget)

    @Query("SELECT * FROM budgets WHERE categoryId = :categoryId AND monthYear = :monthYear LIMIT 1")
    suspend fun findFor(categoryId: Long, monthYear: String): Budget?

    @Delete
    suspend fun delete(budget: Budget)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY name ASC")
    fun getAll(): Flow<List<Account>>

    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY name ASC")
    suspend fun getAllOnce(): List<Account>

    @Insert
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Delete
    suspend fun delete(account: Account)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY isCompleted ASC, targetDate ASC")
    fun getAll(): Flow<List<Goal>>

    @Insert
    suspend fun insert(goal: Goal): Long

    @Update
    suspend fun update(goal: Goal)

    @Delete
    suspend fun delete(goal: Goal)
}

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring_transactions WHERE isActive = 1 ORDER BY nextDueDate ASC")
    fun getAll(): Flow<List<RecurringTransaction>>

    @Query("SELECT * FROM recurring_transactions WHERE isActive = 1 AND nextDueDate <= :now")
    suspend fun getDue(now: Long): List<RecurringTransaction>

    @Insert
    suspend fun insert(recurring: RecurringTransaction): Long

    @Update
    suspend fun update(recurring: RecurringTransaction)

    @Delete
    suspend fun delete(recurring: RecurringTransaction)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAll(): Flow<List<ChatMessage>>

    @Insert
    suspend fun insert(message: ChatMessage): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clear()
}
