package com.financetracker.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.ai.ui.components.AskAiSection
import com.financetracker.ai.ui.components.iconFor
import com.financetracker.ai.ui.components.rememberCurrencyFormatter
import com.financetracker.ai.viewmodel.FinanceViewModel
import com.financetracker.ai.viewmodel.ModelState
import java.text.NumberFormat

@Composable
fun DashboardScreen(viewModel: FinanceViewModel, onAsk: (String) -> Unit = {}) {
    val income by viewModel.monthlyIncome.collectAsState()
    val expense by viewModel.monthlyExpense.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val insight by viewModel.insight.collectAsState()
    val insightGenerating by viewModel.insightGenerating.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val netWorth by viewModel.netWorth.collectAsState()

    val currencyFormatter = rememberCurrencyFormatter()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Net worth", style = MaterialTheme.typography.labelLarge)
                    Text(
                        currencyFormatter.format(netWorth),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("This month", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Income", style = MaterialTheme.typography.bodySmall)
                            Text(
                                currencyFormatter.format(income),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Expenses", style = MaterialTheme.typography.bodySmall)
                            Text(
                                currencyFormatter.format(expense),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (income > 0) (expense / income).toFloat().coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("AI Insight", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        insight == null ->
                            TextButton(onClick = { viewModel.generateMonthlyInsight() }) {
                                Text("Generate insight")
                            }
                        else -> {
                            Text(insight ?: "", style = MaterialTheme.typography.bodyMedium)
                            if (insightGenerating) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Writing summary on device…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = { viewModel.generateMonthlyInsight() }) {
                                    Text("Refresh")
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("Recent transactions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        items(transactions.take(20)) { tx ->
            val category = categories.firstOrNull { it.id == tx.categoryId }
            ListItem(
                headlineContent = { Text(tx.note.ifBlank { tx.merchant ?: "Transaction" }) },
                supportingContent = {
                    Text(
                        (category?.name ?: "Uncategorized") +
                                if (tx.aiCategorized) " · AI categorized" else ""
                    )
                },
                leadingContent = { Icon(iconFor(category?.icon ?: "more"), contentDescription = null) },
                trailingContent = {
                    Text(
                        currencyFormatter.format(tx.amount),
                        color = if (tx.type.name == "EXPENSE") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }

        item {
            AskAiSection(
                suggestions = listOf(
                    "How much did I spend this month?",
                    "What is my biggest expense category?",
                    "Am I saving enough this month?",
                    "Where can I cut spending?"
                ),
                isModelReady = modelState is ModelState.Ready,
                onAsk = onAsk
            )
        }
    }
}