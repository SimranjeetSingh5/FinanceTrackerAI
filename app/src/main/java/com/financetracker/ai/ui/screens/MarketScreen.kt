package com.financetracker.ai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.financetracker.ai.market.MarketDataService
import com.financetracker.ai.market.Quote
import com.financetracker.ai.ui.components.PriceLineChart
import com.financetracker.ai.ui.components.ScreenHeader
import com.financetracker.ai.ui.components.rememberCurrencyFormatter
import com.financetracker.ai.viewmodel.ChartState
import com.financetracker.ai.viewmodel.MarketState
import com.financetracker.ai.viewmodel.MarketViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * A watchlist of market quotes.
 *
 * Optional by design: the app has no account, no API key, and works with no network at all.
 * This screen is the single exception, and it degrades to a clear message rather than an
 * error — nothing else in the app depends on it.
 *
 * Prices come from a free delayed feed, which the UI states plainly. Presenting a 15-minute-old
 * price as if it were live would be the more dangerous choice in a finance app.
 */
@Composable
fun MarketScreen(
    viewModel: MarketViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    val chart by viewModel.chart.collectAsState()
    val currency = rememberCurrencyFormatter()
    var addDialog by remember { mutableStateOf(false) }
    var chartSymbol by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ScreenHeader("Markets", onBack = onBack)
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (watchlist.isEmpty()) {
                EmptyWatchlist(
                    onAdd = { addDialog = true },
                    onBack = onBack,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${watchlist.size} of ${MarketDataService.MAX_SYMBOLS} tickers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        IconButton(onClick = { addDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add ticker")
                        }
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                }

                when (val current = state) {
                    is MarketState.Loading -> Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    is MarketState.Failed -> Notice(
                        current.message,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    is MarketState.Loaded -> LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            DelayedNotice(
                                "Quotes are delayed, not live.",
                                fetchedAt = current.at
                            )
                        }
                        items(current.quotes) { quote ->
                            QuoteRow(
                                quote = quote,
                                currency = currency,
                                onRemove = { viewModel.removeSymbol(quote.symbol) },
                                onClick = {
                                    // Set the symbol *and* kick off the fetch — the dialog is
                                    // gated on the symbol, so setting only the chart state
                                    // would load the data with nothing to show it in.
                                    chartSymbol = quote.symbol
                                    viewModel.loadChart(quote.symbol)
                                }
                            )
                        }
                    }

                    else -> Box(Modifier.weight(1f))
                }
            }
        }
    }

    if (chartSymbol != null) {
        ChartDialog(
            symbol = chartSymbol.orEmpty(),
            chart = chart,
            onDismiss = { chartSymbol = null }
        )
    }

    if (addDialog) {
        AddSymbolDialog(
            onDismiss = { addDialog = false },
            onAdd = { symbol ->
                addDialog = false
                viewModel.addSymbol(symbol)
            }
        )
    }
}

@Composable
private fun EmptyWatchlist(onAdd: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "No tickers yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Add a ticker to see its price. This is the only part of the app that uses the " +
                    "network — everything else works offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAdd) { Text("Add a ticker") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun QuoteRow(
    quote: Quote,
    currency: NumberFormat,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val up = quote.isUp
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (quote.hasData) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(quote.symbol, fontWeight = FontWeight.Bold)
                if (quote.hasData) {
                    quote.asOf?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        "No data for this ticker",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (quote.hasData) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        quote.price?.let { currency.format(it) } ?: "—",
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (up) Icons.Filled.TrendingUp else Icons.Filled.TrendingDown,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (up) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            quote.changePercent?.let { "%+.2f%%".format(Locale.US, it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (up) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove ${quote.symbol}")
            }
        }
    }
}

@Composable
private fun DelayedNotice(text: String, fetchedAt: Long) {
    val time = remember(fetchedAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fetchedAt))
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$text Fetched at $time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun ChartDialog(
    symbol: String,
    chart: ChartState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$symbol · last year") },
        text = {
            when (chart) {
                is ChartState.Loading -> Box(
                    Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is ChartState.Ready -> PriceLineChart(series = chart.series)

                is ChartState.Failed -> Notice(chart.message)

                else -> Text("No chart loaded.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun AddSymbolDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var symbol by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a ticker") },
        text = {
            Column {
                Text(
                    "US-listed symbols, e.g. AAPL or MSFT. Up to " +
                            "${MarketDataService.MAX_SYMBOLS} at a time.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it.uppercase(Locale.US) },
                    label = { Text("Ticker") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Ascii
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(symbol) },
                enabled = symbol.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
