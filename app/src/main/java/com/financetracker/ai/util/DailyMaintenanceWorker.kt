package com.financetracker.ai.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.settings.SettingsStore
import java.text.SimpleDateFormat
import java.util.*

/**
 * Runs once a day (scheduled from FinanceApp.onCreate via WorkManager's periodic API):
 *  1. Processes any recurring transactions whose nextDueDate has passed — either auto-adds them
 *     or fires a reminder notification, per the user's preference on that recurring item.
 *  2. Checks each category's budget for the current month and fires an alert once spend crosses
 *     its threshold percentage.
 */
class DailyMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FinanceApp
        val repo = app.repository
        val settings = SettingsStore(applicationContext)

        if (settings.recurringRemindersEnabled) {
            repo.processDueRecurringTransactions { name, amount, id ->
                NotificationHelper.notifyRecurringDue(applicationContext, id, name, amount)
            }
        }

        if (settings.budgetAlertsEnabled) {
            val monthYear = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
            repo.checkBudgetAlerts(monthYear) { categoryName, pctUsed, categoryId ->
                NotificationHelper.notifyBudgetAlert(applicationContext, categoryId, categoryName, pctUsed)
            }
        }

        return Result.success()
    }
}
