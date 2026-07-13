package com.financetracker.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.financetracker.ai.ui.components.iconFor
import com.financetracker.ai.viewmodel.AnalyticsViewModel
import com.financetracker.ai.viewmodel.CategorySlice
import com.financetracker.ai.viewmodel.MonthPoint
import java.text.NumberFormat

@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel = viewModel()) {
    val breakdown by viewModel.breakdown.collectAsState()
    val trend by viewModel.trend.collectAsState()
    val currency = remember { NumberFormat.getCurrencyInstance() }
    val total = breakdown.sumOf { it.amount }.takeIf { it > 0 } ?: 1.0

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text("Analytics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Income vs. expenses — last 6 months", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    TrendChart(trend, currency)
                }
            }
        }

        item { Text("This month by category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }

        items(breakdown) { slice ->
            CategoryBar(slice, slice.amount / total, currency)
        }

        if (breakdown.isEmpty()) item { Text("No expenses recorded this month yet.") }
    }
}

@Composable
private fun CategoryBar(slice: CategorySlice, fraction: Double, currency: NumberFormat) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(slice.category.icon), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(slice.category.name, style = MaterialTheme.typography.bodyMedium)
            }
            Text(currency.format(slice.amount), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.small)
        )
    }
}

@Composable
private fun TrendChart(points: List<MonthPoint>, currency: NumberFormat) {
    val maxVal = (points.maxOfOrNull { maxOf(it.income, it.expense) } ?: 0.0).takeIf { it > 0 } ?: 1.0

    Row(
        Modifier.fillMaxWidth().height(160.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        points.forEach { point ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        Modifier
                            .width(10.dp)
                            .height((120 * (point.income / maxVal)).dp)
                            .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall)
                    )
                    Box(
                        Modifier
                            .width(10.dp)
                            .height((120 * (point.expense / maxVal)).dp)
                            .background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.extraSmall)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(point.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row {
        LegendDot(MaterialTheme.colorScheme.primary, "Income")
        Spacer(Modifier.width(16.dp))
        LegendDot(MaterialTheme.colorScheme.error, "Expenses")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, shape = androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
