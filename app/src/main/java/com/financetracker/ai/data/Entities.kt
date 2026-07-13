package com.financetracker.ai.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TransactionType { INCOME, EXPENSE, TRANSFER }
enum class AccountType { CHECKING, SAVINGS, CREDIT_CARD, CASH, INVESTMENT }
enum class RecurrenceFrequency { DAILY, WEEKLY, BIWEEKLY, MONTHLY, YEARLY }

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val institutionName: String? = null,
    val startingBalance: Double = 0.0,
    val colorHex: String = "#1B5E20",
    val icon: String = "wallet",
    val isArchived: Boolean = false,
    val creditLimit: Double? = null // only meaningful for CREDIT_CARD
)

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val note: String,
    val merchant: String? = null,
    val categoryId: Long,
    val accountId: Long = 0,
    val transferToAccountId: Long? = null, // set only when type == TRANSFER
    val type: TransactionType,
    val timestamp: Long,           // epoch millis
    val aiCategorized: Boolean = false, // true if Gemma auto-assigned the category
    val aiConfidence: Float? = null,
    val isRecurringInstance: Boolean = false,
    val recurringTransactionId: Long? = null,
    val receiptPhotoPath: String? = null,
    val isPending: Boolean = false
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,        // maps to a Material icon key, see IconMapper
    val colorHex: String,
    val monthlyBudget: Double? = null,
    val isDefault: Boolean = false
)

@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val monthYear: String,   // "2026-07"
    val limitAmount: Double,
    val spentAmount: Double = 0.0,
    val alertThresholdPct: Int = 90 // notify when spend crosses this % of limit
)

@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double = 0.0,
    val targetDate: Long? = null,
    val colorHex: String = "#00897B",
    val icon: String = "flag",
    val linkedAccountId: Long? = null,
    val isCompleted: Boolean = false
)

@Entity(tableName = "recurring_transactions")
data class RecurringTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val note: String,
    val merchant: String? = null,
    val categoryId: Long,
    val accountId: Long = 0,
    val type: TransactionType,
    val frequency: RecurrenceFrequency,
    val nextDueDate: Long,
    val lastProcessedDate: Long? = null,
    val isActive: Boolean = true,
    val reminderEnabled: Boolean = true,
    val autoAdd: Boolean = false // if true, auto-creates the transaction on due date instead of just reminding
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,        // "user" or "assistant"
    val content: String,
    val timestamp: Long
)

