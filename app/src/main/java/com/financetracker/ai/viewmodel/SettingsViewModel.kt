package com.financetracker.ai.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.util.CsvExporter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FinanceApp
    private val repo = app.repository
    val settings = app.settingsStore

    val currency: StateFlow<String> = settings.currencyCodeFlow
    val appLockEnabled: StateFlow<Boolean> = settings.isAppLockEnabledFlow
    val biometricEnabled: StateFlow<Boolean> = settings.isBiometricEnabledFlow
    val budgetAlertsEnabled: StateFlow<Boolean> = settings.budgetAlertsEnabledFlow
    val recurringRemindersEnabled: StateFlow<Boolean> = settings.recurringRemindersEnabledFlow
    val watchlist: StateFlow<List<String>> = settings.watchlistFlow

    fun setCurrency(code: String) {
        settings.currencyCode = code
    }

    fun setPin(pin: String) {
        settings.setPin(pin)
    }

    fun disableLock() {
        settings.clearPin()
    }

    fun setBiometricEnabled(enabled: Boolean) {
        settings.isBiometricEnabled = enabled
    }

    fun setBudgetAlertsEnabled(enabled: Boolean) {
        settings.budgetAlertsEnabled = enabled
    }

    fun setRecurringRemindersEnabled(enabled: Boolean) {
        settings.recurringRemindersEnabled = enabled
    }

    fun setWatchlist(symbols: List<String>) {
        settings.setWatchlist(symbols)
    }

    fun buildExportIntent(onReady: (Intent) -> Unit) {
        viewModelScope.launch {
            val transactions = repo.allTransactionsForExport()
            val categories = repo.allCategories.first()
            val accounts = repo.allAccounts.first()
            val intent = CsvExporter.export(app, transactions, categories, accounts)
            onReady(intent)
        }
    }
}