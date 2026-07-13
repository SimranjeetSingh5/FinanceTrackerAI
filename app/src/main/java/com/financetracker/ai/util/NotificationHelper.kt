package com.financetracker.ai.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {
    const val CHANNEL_BUDGET = "budget_alerts"
    const val CHANNEL_RECURRING = "recurring_reminders"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_BUDGET, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifies you when a category is approaching or over its monthly budget"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RECURRING, "Bill reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminds you about upcoming recurring bills and subscriptions"
            }
        )
    }

    fun notifyBudgetAlert(context: Context, id: Int, categoryName: String, pctUsed: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_BUDGET)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Budget alert: $categoryName")
            .setContentText("You've used $pctUsed% of your $categoryName budget this month.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun notifyRecurringDue(context: Context, id: Int, name: String, amount: Double) {
        val notification = NotificationCompat.Builder(context, CHANNEL_RECURRING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Upcoming bill: $name")
            .setContentText("$name ($amount) is due today.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
