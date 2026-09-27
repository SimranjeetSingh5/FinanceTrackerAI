package com.financetracker.ai.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import com.financetracker.ai.data.Account
import com.financetracker.ai.data.Category
import com.financetracker.ai.data.Transaction
import com.financetracker.ai.data.TransactionType
import com.financetracker.ai.ui.components.iconFor
import com.financetracker.ai.ui.components.rememberCurrencyFormatter
import com.financetracker.ai.ui.components.ScreenHeader
import com.financetracker.ai.viewmodel.TransactionsViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel = viewModel(),
    onBack: () -> Unit
) {
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val isFiltered by viewModel.isFiltered.collectAsState()
    val currency = rememberCurrencyFormatter()
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    var query by rememberSaveable { mutableStateOf("") }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var pendingDelete by remember { mutableStateOf<Transaction?>(null) }

    // Keep the text field in step when the filter is cleared programmatically.
    LaunchedEffect(filter.query) {
        if (filter.query.isBlank() && query.isNotEmpty()) query = ""
    }

    Scaffold(
        topBar = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ScreenHeader("Transactions", onBack = onBack)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        viewModel.setQuery(it)
                    },
                    placeholder = { Text("Search merchant or note") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; viewModel.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showFilters = !showFilters }) {
                        Text(if (showFilters) "Hide filters" else "Filter")
                    }
                    if (isFiltered) {
                        TextButton(onClick = { query = ""; viewModel.clearFilters() }) { Text("Clear all") }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showFilters) {
                FilterRow(
                    selectedCategoryId = filter.categoryId,
                    selectedAccountId = filter.accountId,
                    selectedType = filter.type,
                    categories = categories,
                    accounts = accounts,
                    onCategory = viewModel::setCategory,
                    onAccount = viewModel::setAccount,
                    onType = viewModel::setType
                )
            }

            if (transactions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (isFiltered) "No transactions match these filters."
                        else "No transactions yet — add one from the Add tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(transactions, key = { it.id }) { tx ->
                        val category = categories.firstOrNull { it.id == tx.categoryId }
                        val account = accounts.firstOrNull { it.id == tx.accountId }
                        SwipeToDeleteRow(
                            onDelete = { pendingDelete = tx },
                            content = {
                                TransactionRow(
                                    transaction = tx,
                                    category = category,
                                    account = account,
                                    dateLabel = dateFormat.format(Date(tx.timestamp)),
                                    currency = currency,
                                    onClick = { editing = tx }
                                )
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    editing?.let { tx ->
        EditTransactionDialog(
            transaction = tx,
            categories = categories,
            accounts = accounts,
            onDismiss = { editing = null },
            onConfirm = { amount, note, merchant, type, accountId, categoryId ->
                viewModel.save(tx, amount, note, merchant, type, accountId, categoryId)
                editing = null
            }
        )
    }

    pendingDelete?.let { tx ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete transaction?") },
            text = {
                Text("\"${tx.note.ifBlank { tx.merchant ?: "Transaction" }}\" will be removed from your history. This also adjusts your account balance.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(tx); pendingDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FilterRow(
    selectedCategoryId: Long?,
    selectedAccountId: Long?,
    selectedType: TransactionType?,
    categories: List<Category>,
    accounts: List<Account>,
    onCategory: (Long?) -> Unit,
    onAccount: (Long?) -> Unit,
    onType: (TransactionType?) -> Unit
) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            "Type",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedType == null,
                    onClick = { onType(null) },
                    label = { Text("All") }
                )
            }
            items(TransactionType.values()) { t ->
                FilterChip(
                    selected = selectedType == t,
                    onClick = { onType(if (selectedType == t) null else t) },
                    label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }

        Text(
            "Account",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(accounts) { acc ->
                FilterChip(
                    selected = selectedAccountId == acc.id,
                    onClick = { onAccount(if (selectedAccountId == acc.id) null else acc.id) },
                    label = { Text(acc.name) }
                )
            }
        }

        Text(
            "Category",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { cat ->
                FilterChip(
                    selected = selectedCategoryId == cat.id,
                    onClick = { onCategory(if (selectedCategoryId == cat.id) null else cat.id) },
                    label = { Text(cat.name) }
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(
    transaction: Transaction,
    category: Category?,
    account: Account?,
    dateLabel: String,
    currency: NumberFormat,
    onClick: () -> Unit
) {
    val isExpense = transaction.type == TransactionType.EXPENSE
    val isTransfer = transaction.type == TransactionType.TRANSFER

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(transaction.note.ifBlank { transaction.merchant ?: "Transaction" })
        },
        supportingContent = {
            Text(
                buildString {
                    append(dateLabel)
                    if (category != null) {
                        append(" · ")
                        append(category.name)
                    }
                    if (transaction.aiCategorized) append(" · AI")
                    if (account != null) {
                        append(" · ")
                        append(account.name)
                    }
                },
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Icon(iconFor(category?.icon ?: "more"), contentDescription = null)
        },
        trailingContent = {
            val sign = if (isTransfer) "" else if (isExpense) "−" else "+"
            Text(
                text = sign + currency.format(transaction.amount),
                color = when {
                    isExpense -> MaterialTheme.colorScheme.error
                    isTransfer -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                fontWeight = FontWeight.SemiBold
            )
        }
    )
}

/**
 * Left-swipe a row to reveal a delete affordance; tapping the row still opens the editor.
 *
 * Material3's SwipeToDismiss only exists in 1.3+, and the Material2 equivalent (androidx.compose.material)
 * isn't a dependency of this project, so this is built from primitives to avoid adding one just
 * for a single gesture. Swiping past 96dp triggers the callback, then the row animates back to
 * rest — the list is driven by Room's Flow, so the row removes itself once the delete lands.
 */
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { 96.dp.toPx() }

    Box(Modifier.fillMaxWidth()) {
        // Delete affordance sits behind the row and is revealed as it slides left.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(end = 24.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Delete",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(onDelete) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            // Only allow dragging leftward; the row snaps back otherwise.
                            if (dragAmount < 0 || offsetX.value < 0f) {
                                change.consume()
                                scope.launch {
                                    offsetX.snapTo(
                                        (offsetX.value + dragAmount).coerceIn(-thresholdPx, 0f)
                                    )
                                }
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value <= -thresholdPx) onDelete()
                                offsetX.animateTo(0f)
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f) } }
                    )
                }
        ) {
            content()
        }
    }
}

@Composable
private fun EditTransactionDialog(
    transaction: Transaction,
    categories: List<Category>,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onConfirm: (Double, String, String?, TransactionType, Long, Long) -> Unit
) {
    var amount by remember { mutableStateOf(transaction.amount.toString()) }
    var note by remember { mutableStateOf(transaction.note) }
    var merchant by remember { mutableStateOf(transaction.merchant ?: "") }
    var type by remember { mutableStateOf(transaction.type) }
    var categoryId by remember { mutableStateOf(transaction.categoryId) }
    var accountId by remember { mutableStateOf(transaction.accountId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit transaction") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = merchant, onValueChange = { merchant = it },
                    label = { Text("Merchant") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Type", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransactionType.values().forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }

                if (accounts.isNotEmpty()) {
                    Text("Account", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(accounts) { acc ->
                            FilterChip(
                                selected = accountId == acc.id,
                                onClick = { accountId = acc.id },
                                label = { Text(acc.name) }
                            )
                        }
                    }
                }

                if (categories.isNotEmpty()) {
                    Text("Category", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories) { cat ->
                            FilterChip(
                                selected = categoryId == cat.id,
                                onClick = { categoryId = cat.id },
                                label = { Text(cat.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    amount.toDoubleOrNull()?.let {
                        onConfirm(it, note, merchant.ifBlank { null }, type, accountId, categoryId)
                    }
                },
                enabled = amount.toDoubleOrNull() != null
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
