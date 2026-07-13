package com.financetracker.ai

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.financetracker.ai.security.BiometricAuthHelper
import com.financetracker.ai.ui.AppNavHost
import com.financetracker.ai.ui.screens.LockScreen
import com.financetracker.ai.ui.theme.FinanceTrackerAITheme

// FragmentActivity (not plain ComponentActivity) is required by androidx.biometric's BiometricPrompt.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = (application as FinanceApp).settingsStore

        setContent {
            var isUnlocked by remember { mutableStateOf(!settings.isAppLockEnabled) }

            FinanceTrackerAITheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (isUnlocked) {
                        AppNavHost()
                    } else {
                        LockScreen(
                            settings = settings,
                            onUnlocked = { isUnlocked = true },
                            onBiometricRequested = {
                                BiometricAuthHelper.prompt(
                                    activity = this@MainActivity,
                                    onSuccess = { isUnlocked = true },
                                    onError = { /* user can fall back to PIN in LockScreen */ }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as FinanceApp).gemmaHelper.close()
    }
}
