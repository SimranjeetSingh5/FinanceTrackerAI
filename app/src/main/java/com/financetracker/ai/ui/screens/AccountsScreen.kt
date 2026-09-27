package com.financetracker.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.ai.data.Account
import com.financetracker.ai.data.AccountType
import com.financetracker.ai.ui.components.AskAiSection
import com.financetracker.ai.ui.components.iconForAccountType
import com.financetracker.ai.ui.components.rememberCurrencyFormatter
import com.financetracker.ai.ui.components.ScreenHeader
import com.financetracker.ai.viewmodel.FinanceViewModel
import java.text.NumberFormat

@Composable
fun AccountsScreen(
    viewModel: FinanceViewModel,
    onBack: () -> Unit = {},
    isModelReady: Boolean = false,
    onAsk: (String) -> Unit = {}
) {
    val accounts by viewModel.accounts.collectAsState()
    val balances by viewModel.accountBalances.collectAsState()
    val netWorth by viewModel.netWorth.collectAsState()
    val currency = rememberCurrencyFormatter()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add account")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ScreenHeader("Accounts", onBack = onBack)
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Net worth", style = MaterialTheme.typography.labelLarge)
                        Text(
                            currency.format(netWorth),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            items(accounts) { account ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(iconForAccountType(account.type), contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(account.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    account.type.name.replace("_", " ").lowercase()
                                        .replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Text(
                            currency.format(balances[account.id] ?: 0.0),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (accounts.isEmpty()) {
                item { Text("No accounts yet — add one to get started.") }
            }

            item {
                AskAiSection(
                    suggestions = listOf(
                        "How is my net worth trending?",
                        "Where should I keep an emergency fund?",
                        "Should I pay off my credit card first?",
                        "How much should I save each month?"
                    ),
                    isModelReady = isModelReady,
                    onAsk = onAsk
                )
            }
        }
    }

    if (showAddDialog) {
        AddAccountDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, type, startingBalance ->
                viewModel.addAccount(Account(name = name, type = type, startingBalance = startingBalance))
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddAccountDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, AccountType, Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AccountType.CHECKING) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Account name") })
                OutlinedTextField(
                    value = balance, onValueChange = { balance = it },
                    label = { Text("Starting balance") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
                Text("Type", style = MaterialTheme.typography.labelMedium)
                AccountType.values().forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(t.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, type, balance.toDoubleOrNull() ?: 0.0) },
                enabled = name.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
