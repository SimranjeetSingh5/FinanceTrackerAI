package com.financetracker.ai.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.util.CsvExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FinanceApp
    private val repo = app.repository
    val settings = app.settingsStore

    private val _currency = MutableStateFlow(settings.currencyCode)
    val currency: StateFlow<String> = _currency.asStateFlow()

    private val _appLockEnabled = MutableStateFlow(settings.isAppLockEnabled)
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(settings.isBiometricEnabled)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _budgetAlertsEnabled = MutableStateFlow(settings.budgetAlertsEnabled)
    val budgetAlertsEnabled: StateFlow<Boolean> = _budgetAlertsEnabled.asStateFlow()

    private val _recurringRemindersEnabled = MutableStateFlow(settings.recurringRemindersEnabled)
    val recurringRemindersEnabled: StateFlow<Boolean> = _recurringRemindersEnabled.asStateFlow()

    fun setCurrency(code: String) { settings.currencyCode = code; _currency.value = code }

    fun setPin(pin: String) {
        settings.setPin(pin)
        settings.isAppLockEnabled = true
        _appLockEnabled.value = true
    }

    fun disableLock() {
        settings.clearPin()
        _appLockEnabled.value = false
    }

    fun setBiometricEnabled(enabled: Boolean) {
        settings.isBiometricEnabled = enabled
        _biometricEnabled.value = enabled
    }

    fun setBudgetAlertsEnabled(enabled: Boolean) {
        settings.budgetAlertsEnabled = enabled
        _budgetAlertsEnabled.value = enabled
    }

    fun setRecurringRemindersEnabled(enabled: Boolean) {
        settings.recurringRemindersEnabled = enabled
        _recurringRemindersEnabled.value = enabled
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
