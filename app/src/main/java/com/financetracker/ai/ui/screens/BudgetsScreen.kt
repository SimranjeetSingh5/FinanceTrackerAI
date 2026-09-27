package com.financetracker.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.financetracker.ai.data.Category
import com.financetracker.ai.ui.components.AskAiSection
import com.financetracker.ai.ui.components.iconFor
import com.financetracker.ai.ui.components.rememberCurrencyFormatter
import com.financetracker.ai.viewmodel.BudgetsViewModel
import com.financetracker.ai.viewmodel.CategoryBudgetProgress
import com.financetracker.ai.viewmodel.FinanceViewModel
import java.text.NumberFormat

@Composable
fun BudgetsScreen(
    financeViewModel: FinanceViewModel,
    budgetsViewModel: BudgetsViewModel = viewModel(),
    isModelReady: Boolean = false,
    onAsk: (String) -> Unit = {}
) {
    val progress by budgetsViewModel.progress.collectAsState()
    val allCategories by financeViewModel.categories.collectAsState()
    val currency = rememberCurrencyFormatter()
    var editingCategory by remember { mutableStateOf<Category?>(null) }

    // Suggestions reference the categories the user actually budgeted, so the question is
    // answerable from their data rather than generic.
    val suggestions = remember(progress) {
        val worst = progress.filter { it.pct > 0.5f }.maxByOrNull { it.pct }
        buildList {
            worst?.let { add("Am I over budget on ${it.category.name}?") }
            add("How can I cut spending this month?")
            add("Which categories are trending up?")
            add("Is my budget realistic for my income?")
        }.distinct()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Monthly budgets", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        items(progress) { p ->
            BudgetRow(p, currency) { editingCategory = p.category }
        }

        item {
            Text("Set a budget for a category", style = MaterialTheme.typography.titleSmall)
        }
        items(allCategories.filter { c -> progress.none { it.category.id == c.id } }) { cat ->
            OutlinedButton(onClick = { editingCategory = cat }, modifier = Modifier.fillMaxWidth()) {
                Icon(iconFor(cat.icon), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add budget for ${cat.name}")
            }
        }

        item {
            AskAiSection(
                suggestions = suggestions,
                isModelReady = isModelReady,
                onAsk = onAsk
            )
        }
    }

    editingCategory?.let { cat ->
        val existing = progress.firstOrNull { it.category.id == cat.id }?.limit
        SetBudgetDialog(
            category = cat,
            currentLimit = existing,
            onDismiss = { editingCategory = null },
            onConfirm = { limit ->
                budgetsViewModel.setBudget(cat, limit)
                editingCategory = null
            }
        )
    }
}

@Composable
private fun BudgetRow(p: CategoryBudgetProgress, currency: NumberFormat, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(p.category.icon), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(p.category.name, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onEdit) { Text("Edit") }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { p.pct.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (p.isOverBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${currency.format(p.spent)} of ${currency.format(p.limit)}" +
                    if (p.isOverBudget) " · Over budget" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (p.isOverBudget) MaterialTheme.colorScheme.error else Color.Unspecified
            )
        }
    }
}

@Composable
private fun SetBudgetDialog(
    category: Category,
    currentLimit: Double?,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var limit by remember { mutableStateOf(currentLimit?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Budget for ${category.name}") },
        text = {
            OutlinedTextField(
                value = limit, onValueChange = { limit = it },
                label = { Text("Monthly limit") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { limit.toDoubleOrNull()?.let(onConfirm) }, enabled = limit.toDoubleOrNull() != null) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
