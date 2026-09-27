package com.financetracker.ai.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import java.text.NumberFormat
import java.util.Currency

/**
 * Currency code provided once by [com.financetracker.ai.ui.AppNavHost] from the user's Settings
 * choice, so every screen formats money identically instead of each one silently falling back to
 * the device locale.
 */
val LocalCurrencyCode = compositionLocalOf { "USD" }

/** A [NumberFormat] bound to the current [LocalCurrencyCode], recomputed only when the code changes. */
@Composable
fun rememberCurrencyFormatter(currencyCode: String = LocalCurrencyCode.current): NumberFormat =
    remember(currencyCode) {
        NumberFormat.getCurrencyInstance().apply {
            try {
                currency = Currency.getInstance(currencyCode)
            } catch (e: Exception) {
                // Safe fallback if currencyCode is invalid — keep the device locale's symbol.
            }
        }
    }
