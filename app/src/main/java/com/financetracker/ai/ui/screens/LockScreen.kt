package com.financetracker.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.financetracker.ai.settings.SettingsStore

@Composable
fun LockScreen(
    settings: SettingsStore,
    onUnlocked: () -> Unit,
    onBiometricRequested: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (settings.isBiometricEnabled) onBiometricRequested()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("FinanceTracker AI is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 8) { pin = it; error = null } },
            label = { Text("Enter PIN") },
            visualTransformation = PasswordVisualTransformation(),
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
            )
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (settings.verifyPin(pin)) onUnlocked() else error = "Incorrect PIN"
            },
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text("Unlock")
        }

        if (settings.isBiometricEnabled) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBiometricRequested) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Use fingerprint / face")
            }
        }
    }
}
