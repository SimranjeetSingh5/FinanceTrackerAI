package com.financetracker.ai.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.Locale

/**
 * Local, on-device settings: app lock (PIN + biometric toggle), currency, and notification
 * preferences. Backed by EncryptedSharedPreferences since it stores the PIN hash.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "finance_tracker_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback for environments where the keystore is unavailable (rare); not encrypted.
        context.getSharedPreferences("finance_tracker_prefs_fallback", Context.MODE_PRIVATE)
    }

    private val _currencyCode = MutableStateFlow(prefs.getString(KEY_CURRENCY, "USD") ?: "USD")
    val currencyCodeFlow: StateFlow<String> = _currencyCode.asStateFlow()

    private val _isAppLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOCK_ENABLED, false))
    val isAppLockEnabledFlow: StateFlow<Boolean> = _isAppLockEnabled.asStateFlow()

    private val _isBiometricEnabled = MutableStateFlow(prefs.getBoolean(KEY_BIOMETRIC_ENABLED, true))
    val isBiometricEnabledFlow: StateFlow<Boolean> = _isBiometricEnabled.asStateFlow()

    private val _budgetAlertsEnabled = MutableStateFlow(prefs.getBoolean(KEY_BUDGET_ALERTS, true))
    val budgetAlertsEnabledFlow: StateFlow<Boolean> = _budgetAlertsEnabled.asStateFlow()

    private val _recurringRemindersEnabled = MutableStateFlow(prefs.getBoolean(KEY_RECURRING_REMINDERS, true))
    val recurringRemindersEnabledFlow: StateFlow<Boolean> = _recurringRemindersEnabled.asStateFlow()

    /**
     * Instruments the user follows, stored separately from the financial data because a ticker
     * is not a transaction — it is a preference, and keeping it here avoids a schema change and
     * another destructive migration.
     */
    private val _watchlist = MutableStateFlow(readWatchlist())
    val watchlistFlow: StateFlow<List<String>> = _watchlist.asStateFlow()

    /** Replaces the watchlist, dropping blanks and duplicates while preserving order. */
    fun setWatchlist(symbols: List<String>) {
        val cleaned = symbols
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .distinct()
        prefs.edit().putString(KEY_WATCHLIST, cleaned.joinToString(",")).apply()
        _watchlist.value = cleaned
    }

    private fun readWatchlist(): List<String> =
        prefs.getString(KEY_WATCHLIST, null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    var currencyCode: String
        get() = _currencyCode.value
        set(value) {
            prefs.edit().putString(KEY_CURRENCY, value).apply()
            _currencyCode.value = value
        }

    var isAppLockEnabled: Boolean
        get() = _isAppLockEnabled.value
        set(value) {
            prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()
            _isAppLockEnabled.value = value
        }

    var isBiometricEnabled: Boolean
        get() = _isBiometricEnabled.value
        set(value) {
            prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()
            _isBiometricEnabled.value = value
        }

    var budgetAlertsEnabled: Boolean
        get() = _budgetAlertsEnabled.value
        set(value) {
            prefs.edit().putBoolean(KEY_BUDGET_ALERTS, value).apply()
            _budgetAlertsEnabled.value = value
        }

    var recurringRemindersEnabled: Boolean
        get() = _recurringRemindersEnabled.value
        set(value) {
            prefs.edit().putBoolean(KEY_RECURRING_REMINDERS, value).apply()
            _recurringRemindersEnabled.value = value
        }

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN_HASH, hash(pin)).apply()
        isAppLockEnabled = true
    }

    fun hasPinSet(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun verifyPin(pin: String): Boolean = prefs.getString(KEY_PIN_HASH, null) == hash(pin)

    fun clearPin() {
        prefs.edit().remove(KEY_PIN_HASH).apply()
        isAppLockEnabled = false
    }

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_CURRENCY = "currency_code"
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_BUDGET_ALERTS = "budget_alerts_enabled"
        private const val KEY_RECURRING_REMINDERS = "recurring_reminders_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_WATCHLIST = "market_watchlist"
    }
}