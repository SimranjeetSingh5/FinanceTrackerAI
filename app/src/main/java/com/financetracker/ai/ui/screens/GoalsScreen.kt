package com.financetracker.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.financetracker.ai.data.Goal
import com.financetracker.ai.ui.components.ScreenHeader
import com.financetracker.ai.ui.components.rememberCurrencyFormatter
import com.financetracker.ai.viewmodel.GoalsViewModel
import java.text.NumberFormat

@Composable
fun GoalsScreen(viewModel: GoalsViewModel = viewModel(), onBack: () -> Unit = {}) {
    val goals by viewModel.goals.collectAsState()
    val currency = rememberCurrencyFormatter()
    var showAddDialog by remember { mutableStateOf(false) }
    var contributingGoal by remember { mutableStateOf<Goal?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add goal")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ScreenHeader("Savings goals", onBack = onBack) }

            items(goals) { goal ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(goal.name, fontWeight = FontWeight.SemiBold)
                            if (goal.isCompleted) Text("Completed 🎉") else TextButton(onClick = { contributingGoal = goal }) { Text("Add funds") }
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (goal.currentAmount / goal.targetAmount).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("${currency.format(goal.currentAmount)} of ${currency.format(goal.targetAmount)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (goals.isEmpty()) item { Text("No savings goals yet — tap + to create one.") }
        }
    }

    if (showAddDialog) {
        AddGoalDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, target ->
                viewModel.addGoal(name, target, null)
                showAddDialog = false
            }
        )
    }

    contributingGoal?.let { goal ->
        ContributeDialog(
            goal = goal,
            onDismiss = { contributingGoal = null },
            onConfirm = { amount ->
                viewModel.contribute(goal, amount)
                contributingGoal = null
            }
        )
    }
}

@Composable
private fun AddGoalDialog(onDismiss: () -> Unit, onConfirm: (String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New savings goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Goal name") })
                OutlinedTextField(
                    value = target, onValueChange = { target = it },
                    label = { Text("Target amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { target.toDoubleOrNull()?.let { onConfirm(name, it) } },
                enabled = name.isNotBlank() && target.toDoubleOrNull() != null
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ContributeDialog(goal: Goal, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var amount by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add funds to ${goal.name}") },
        text = {
            OutlinedTextField(
                value = amount, onValueChange = { amount = it },
                label = { Text("Amount") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { amount.toDoubleOrNull()?.let(onConfirm) }, enabled = amount.toDoubleOrNull() != null) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
