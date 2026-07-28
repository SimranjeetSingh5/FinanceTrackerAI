package com.financetracker.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.financetracker.ai.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onBackClick: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val currency by viewModel.currency.collectAsState()
    val appLockEnabled by viewModel.appLockEnabled.collectAsState()
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val budgetAlerts by viewModel.budgetAlertsEnabled.collectAsState()
    val recurringReminders by viewModel.recurringRemindersEnabled.collectAsState()

    var showPinDialog by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBackClick != null) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }

        item { SectionHeader("Currency") }
        item {
            var expanded by remember { mutableStateOf(false) }
            val options = listOf("USD", "EUR", "GBP", "JPY", "INR", "CAD", "AUD")
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(currency) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { code ->
                        DropdownMenuItem(
                            text = { Text(code) },
                            onClick = {
                                viewModel.setCurrency(code)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)); SectionHeader("Security") }
        item {
            SettingsSwitchRow(
                title = "App lock",
                subtitle = "Require a PIN or biometric to open the app",
                checked = appLockEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) showPinDialog = true else viewModel.disableLock()
                }
            )
        }
        if (appLockEnabled) {
            item {
                SettingsSwitchRow(
                    title = "Use biometric unlock",
                    subtitle = "Fingerprint or face, falls back to PIN",
                    checked = biometricEnabled,
                    onCheckedChange = { viewModel.setBiometricEnabled(it) }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)); SectionHeader("Notifications") }
        item {
            SettingsSwitchRow(
                title = "Budget alerts",
                subtitle = "Notify when a category nears its monthly limit",
                checked = budgetAlerts,
                onCheckedChange = { viewModel.setBudgetAlertsEnabled(it) }
            )
        }
        item {
            SettingsSwitchRow(
                title = "Bill reminders",
                subtitle = "Notify before recurring bills are due",
                checked = recurringReminders,
                onCheckedChange = { viewModel.setRecurringRemindersEnabled(it) }
            )
        }

        item { Spacer(Modifier.height(24.dp)); SectionHeader("Data") }
        item {
            OutlinedButton(
                onClick = {
                    viewModel.buildExportIntent { intent ->
                        context.startActivity(android.content.Intent.createChooser(intent, "Export transactions"))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export transactions as CSV") }
        }

        item { Spacer(Modifier.height(24.dp)) }
        item {
            Text(
                "All data and AI processing stay on this device. Nothing is uploaded to a server.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showPinDialog) {
        SetPinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin -> viewModel.setPin(pin); showPinDialog = false }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SetPinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    val mismatch = confirmPin.isNotEmpty() && pin != confirmPin

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin, onValueChange = { if (it.length <= 8) pin = it },
                    label = { Text("New PIN") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    )
                )
                OutlinedTextField(
                    value = confirmPin, onValueChange = { if (it.length <= 8) confirmPin = it },
                    label = { Text("Confirm PIN") },
                    isError = mismatch,
                    supportingText = { if (mismatch) Text("PINs don't match") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pin) },
                enabled = pin.length >= 4 && pin == confirmPin
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}