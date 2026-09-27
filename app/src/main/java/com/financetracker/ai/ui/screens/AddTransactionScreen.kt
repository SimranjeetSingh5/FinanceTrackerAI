package com.financetracker.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financetracker.ai.data.Account
import com.financetracker.ai.data.Category
import com.financetracker.ai.data.TransactionType
import com.financetracker.ai.viewmodel.FinanceViewModel
import com.financetracker.ai.viewmodel.ModelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(
    viewModel: FinanceViewModel,
    onDone: () -> Unit,
    onImportStatement: (() -> Unit)? = null
) {
    val categories by viewModel.categories.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val modelState by viewModel.modelState.collectAsState()

    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TransactionType.EXPENSE) }
    var selectedCategory by remember { mutableStateOf<Category?>(null) }
    var selectedAccount by remember(accounts) { mutableStateOf(accounts.firstOrNull()) }
    var transferToAccount by remember { mutableStateOf<Account?>(null) }
    var useAi by remember { mutableStateOf(modelState is ModelState.Ready) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Add transaction",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            if (onImportStatement != null) {
                TextButton(onClick = onImportStatement) {
                    Icon(
                        Icons.Filled.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Import")
                }
            }
        }

        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = type == TransactionType.EXPENSE,
                onClick = { type = TransactionType.EXPENSE },
                shape = SegmentedButtonDefaults.itemShape(0, 3)
            ) { Text("Expense") }
            SegmentedButton(
                selected = type == TransactionType.INCOME,
                onClick = { type = TransactionType.INCOME },
                shape = SegmentedButtonDefaults.itemShape(1, 3)
            ) { Text("Income") }
            SegmentedButton(
                selected = type == TransactionType.TRANSFER,
                onClick = { type = TransactionType.TRANSFER },
                shape = SegmentedButtonDefaults.itemShape(2, 3)
            ) { Text("Transfer") }
        }

        OutlinedTextField(
            value = amount, onValueChange = { amount = it },
            label = { Text("Amount") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        Text("Account", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(accounts) { acc ->
                FilterChip(
                    selected = selectedAccount?.id == acc.id,
                    onClick = { selectedAccount = acc },
                    label = { Text(acc.name) }
                )
            }
        }

        if (type == TransactionType.TRANSFER) {
            Text("Transfer to", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(accounts.filter { it.id != selectedAccount?.id }) { acc ->
                    FilterChip(
                        selected = transferToAccount?.id == acc.id,
                        onClick = { transferToAccount = acc },
                        label = { Text(acc.name) }
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = merchant, onValueChange = { merchant = it },
                label = { Text("Merchant (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth()
            )

            if (modelState is ModelState.Ready) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = useAi, onCheckedChange = { useAi = it; if (it) selectedCategory = null })
                    Spacer(Modifier.width(8.dp))
                    Text("Let Gemma categorize this automatically")
                }
            }

            if (!useAi || modelState !is ModelState.Ready) {
                Text("Category", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { cat ->
                        FilterChip(
                            selected = selectedCategory?.id == cat.id,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat.name) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        val canSave = amount.toDoubleOrNull() != null && selectedAccount != null &&
            (type != TransactionType.TRANSFER || transferToAccount != null)

        Button(
            onClick = {
                val amt = amount.toDoubleOrNull() ?: return@Button
                val account = selectedAccount ?: return@Button
                viewModel.addTransaction(
                    amount = amt,
                    note = note,
                    merchant = merchant.ifBlank { null },
                    type = type,
                    accountId = account.id,
                    manualCategoryId = if (type == TransactionType.TRANSFER) null
                        else if (useAi && modelState is ModelState.Ready) null else selectedCategory?.id,
                    transferToAccountId = transferToAccount?.id
                )
                onDone()
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save transaction")
        }
    }
}
