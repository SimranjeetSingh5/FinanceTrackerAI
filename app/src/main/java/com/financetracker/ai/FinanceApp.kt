package com.financetracker.ai

import android.app.Application
import androidx.work.*
import com.financetracker.ai.ai.AiInsightEngine
import com.financetracker.ai.ai.GemmaInferenceHelper
import com.financetracker.ai.data.AppDatabase
import com.financetracker.ai.repository.FinanceRepository
import com.financetracker.ai.settings.SettingsStore
import com.financetracker.ai.util.DailyMaintenanceWorker
import com.financetracker.ai.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class FinanceApp : Application() {

    lateinit var gemmaHelper: GemmaInferenceHelper
        private set
    lateinit var repository: FinanceRepository
        private set
    lateinit var settingsStore: SettingsStore
        private set

    /**
     * Single source of truth for "is Gemma loaded". FinanceViewModel owns the transitions
     * (download → load → ready/error); every other consumer — chat, dashboard, add-transaction —
     * observes this instead of keeping its own copy of the state.
     */
    private val _modelReady = MutableStateFlow(false)
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    /** Writable so the owning ViewModel can publish readiness; read via [modelReady]. */
    var isModelLoaded: Boolean
        get() = _modelReady.value
        set(value) { _modelReady.value = value }

    override fun onCreate() {
        super.onCreate()

        val db = AppDatabase.getInstance(this)
        gemmaHelper = GemmaInferenceHelper(this)
        settingsStore = SettingsStore(this)
        val aiEngine = AiInsightEngine(gemmaHelper)

        repository = FinanceRepository(
            transactionDao = db.transactionDao(),
            categoryDao = db.categoryDao(),
            budgetDao = db.budgetDao(),
            chatDao = db.chatDao(),
            accountDao = db.accountDao(),
            goalDao = db.goalDao(),
            recurringDao = db.recurringDao(),
            aiEngine = aiEngine
        )

        NotificationHelper.createChannels(this)

        CoroutineScope(Dispatchers.IO).launch {
            repository.ensureDefaultCategories()
            repository.ensureDefaultAccount()
        }

        scheduleDailyMaintenance()
    }

    private fun scheduleDailyMaintenance() {
        val request = PeriodicWorkRequestBuilder<DailyMaintenanceWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_maintenance",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
