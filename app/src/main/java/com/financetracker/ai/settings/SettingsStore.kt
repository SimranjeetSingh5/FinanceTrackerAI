package com.financetracker.ai.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

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

    var currencyCode: String
        get() = prefs.getString(KEY_CURRENCY, "USD") ?: "USD"
        set(value) = prefs.edit().putString(KEY_CURRENCY, value).apply()

    var isAppLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()

    var isBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    var budgetAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUDGET_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_BUDGET_ALERTS, value).apply()

    var recurringRemindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECURRING_REMINDERS, true)
        set(value) = prefs.edit().putBoolean(KEY_RECURRING_REMINDERS, value).apply()

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN_HASH, hash(pin)).apply()
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
    }
}
