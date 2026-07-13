package com.financetracker.ai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun fromType(type: TransactionType): String = type.name

    @TypeConverter
    fun toType(value: String): TransactionType = TransactionType.valueOf(value)

    @TypeConverter
    fun fromAccountType(type: AccountType): String = type.name

    @TypeConverter
    fun toAccountType(value: String): AccountType = AccountType.valueOf(value)

    @TypeConverter
    fun fromFrequency(freq: RecurrenceFrequency): String = freq.name

    @TypeConverter
    fun toFrequency(value: String): RecurrenceFrequency = RecurrenceFrequency.valueOf(value)
}

@Database(
    entities = [
        Transaction::class, Category::class, Budget::class, ChatMessage::class,
        Account::class, Goal::class, RecurringTransaction::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun chatDao(): ChatDao
    abstract fun accountDao(): AccountDao
    abstract fun goalDao(): GoalDao
    abstract fun recurringDao(): RecurringDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val DEFAULT_CATEGORIES = listOf(
            Category(name = "Food & Dining", icon = "restaurant", colorHex = "#FF7043", isDefault = true),
            Category(name = "Groceries", icon = "cart", colorHex = "#66BB6A", isDefault = true),
            Category(name = "Transport", icon = "car", colorHex = "#42A5F5", isDefault = true),
            Category(name = "Housing", icon = "home", colorHex = "#AB47BC", isDefault = true),
            Category(name = "Utilities", icon = "bolt", colorHex = "#FFCA28", isDefault = true),
            Category(name = "Entertainment", icon = "movie", colorHex = "#EC407A", isDefault = true),
            Category(name = "Shopping", icon = "bag", colorHex = "#26A69A", isDefault = true),
            Category(name = "Health", icon = "health", colorHex = "#EF5350", isDefault = true),
            Category(name = "Subscriptions", icon = "subscription", colorHex = "#5C6BC0", isDefault = true),
            Category(name = "Income", icon = "cash", colorHex = "#7CB342", isDefault = true),
            Category(name = "Other", icon = "more", colorHex = "#8D6E63", isDefault = true)
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "finance_tracker.db"
                )
                    // Demo-stage app: destructive migration is acceptable pre-release.
                    // Replace with real Migration objects before shipping a v2 update to real users.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
