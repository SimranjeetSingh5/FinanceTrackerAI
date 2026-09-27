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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.financetracker.ai.data.RecurrenceFrequency
import com.financetracker.ai.data.TransactionType
import com.financetracker.ai.ui.components.AskAiSection
import com.financetracker.ai.ui.components.ScreenHeader
import com.financetracker.ai.ui.components.rememberCurrencyFormatter
import com.financetracker.ai.viewmodel.FinanceViewModel
import com.financetracker.ai.viewmodel.RecurringViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RecurringScreen(
    financeViewModel: FinanceViewModel,
    viewModel: RecurringViewModel = viewModel(),
    onBack: () -> Unit = {},
    isModelReady: Boolean = false,
    onAsk: (String) -> Unit = {}
) {
    val items by viewModel.items.collectAsState()
    val categories by financeViewModel.categories.collectAsState()
    val accounts by financeViewModel.accounts.collectAsState()
    val currency = rememberCurrencyFormatter()
    val df = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add recurring")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ScreenHeader("Bills & subscriptions", onBack = onBack) }

            items(items) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(item.note.ifBlank { item.merchant ?: "Recurring" }, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${item.frequency.name.lowercase().replaceFirstChar { it.uppercase() }} · Next: ${df.format(Date(item.nextDueDate))}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(currency.format(item.amount), fontWeight = FontWeight.Bold)
                            Switch(checked = item.isActive, onCheckedChange = { viewModel.toggleActive(item) })
                        }
                    }
                }
            }

            if (items.isEmpty()) item { Text("No recurring bills yet. Add subscriptions, rent, or anything on a schedule.") }

            item {
                val monthly = items.filter { it.isActive && it.type == TransactionType.EXPENSE }
                    .sumOf { it.amount }
                AskAiSection(
                    suggestions = buildList {
                        if (monthly > 0) {
                            add("My fixed costs are $monthly a month — is that too high?")
                        }
                        add("Which subscriptions should I cancel?")
                        add("How can I reduce my recurring bills?")
                        add("Am I saving enough after fixed costs?")
                    },
                    isModelReady = isModelReady,
                    onAsk = onAsk
                )
            }
        }
    }

    if (showAddDialog) {
        AddRecurringDialog(
            categories = categories,
            accounts = accounts,
            onDismiss = { showAddDialog = false },
            onConfirm = { amount, note, categoryId, accountId, type, frequency, autoAdd ->
                viewModel.addRecurring(amount, note, categoryId, accountId, type, frequency, System.currentTimeMillis(), autoAdd)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddRecurringDialog(
    categories: List<com.financetracker.ai.data.Category>,
    accounts: List<com.financetracker.ai.data.Account>,
    onDismiss: () -> Unit,
    onConfirm: (Double, String, Long, Long, TransactionType, RecurrenceFrequency, Boolean) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(RecurrenceFrequency.MONTHLY) }
    var autoAdd by remember { mutableStateOf(false) }
    val categoryId = categories.firstOrNull()?.id ?: 0
    val accountId = accounts.firstOrNull()?.id ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New recurring item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Name (e.g. Netflix, Rent)") })
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it },
                    label = { Text("Amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
                Text("Frequency", style = MaterialTheme.typography.labelMedium)
                RecurrenceFrequency.values().forEach { f ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = frequency == f, onClick = { frequency = f })
                        Text(f.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = autoAdd, onCheckedChange = { autoAdd = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Auto-add transaction when due (otherwise just remind me)")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    amount.toDoubleOrNull()?.let {
                        onConfirm(it, note, categoryId, accountId, TransactionType.EXPENSE, frequency, autoAdd)
                    }
                },
                enabled = note.isNotBlank() && amount.toDoubleOrNull() != null
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
