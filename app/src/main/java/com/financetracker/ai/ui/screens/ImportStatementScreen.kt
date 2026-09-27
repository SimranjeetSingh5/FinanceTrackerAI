@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.financetracker.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.financetracker.ai.data.Account
import com.financetracker.ai.importing.ParsedTransaction
import com.financetracker.ai.ui.components.ScreenHeader
import com.financetracker.ai.ui.components.rememberCurrencyFormatter
import com.financetracker.ai.viewmodel.FinanceViewModel
import com.financetracker.ai.viewmodel.ImportState
import com.financetracker.ai.viewmodel.ImportViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Statement import: pick a file, review what was recognised, then save.
 *
 * The review step is not optional decoration. OCR on a real statement misreads currency symbols
 * and occasionally loses an amount or a date entirely, so every row is shown with its raw text
 * and can be corrected, skipped, or re-categorised before anything is written.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImportStatementScreen(
    financeViewModel: FinanceViewModel,
    viewModel: ImportViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val edits by viewModel.edits.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val accounts by financeViewModel.accounts.collectAsState()
    val currency = rememberCurrencyFormatter()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(viewModel::importImage) }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(viewModel::importPdf) }

    var showPasteDialog by remember { mutableStateOf(false) }
    var showAccountPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ScreenHeader("Import statement", onBack = onBack)
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (val current = state) {
                is ImportState.Idle -> SourcePicker(
                    onImage = { imagePicker.launch("image/*") },
                    onPdf = { pdfPicker.launch("application/pdf") },
                    onPaste = { showPasteDialog = true }
                )

                is ImportState.Scanning -> ScanningState()

                is ImportState.Failed -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                current.message,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = viewModel::reset) { Text("Start over") }
                        }
                    }
                }

                is ImportState.Ready -> ReviewList(
                    rows = current.transactions,
                    edits = edits,
                    categories = categories.map { it.id to it.name },
                    currency = currency,
                    onToggle = { row, on -> viewModel.setIncluded(row, on) },
                    onMerchant = viewModel::setMerchant,
                    onAmount = viewModel::setAmount,
                    onCategory = viewModel::setCategory,
                    onSelectAll = viewModel::includeAll
                )

                is ImportState.Saving -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is ImportState.Saved -> SavedState(current, onDone = onBack)
            }
        }
    }

    // --- Bottom action bar -----------------------------------------------------
    val ready = state as? ImportState.Ready
    if (ready != null) {
        val included = ready.transactions.count { edits[it.fingerprint]?.include == true }
        val flagged = ready.transactions.count { it.needsReview && edits[it.fingerprint]?.include == true }
        val duplicates = ready.transactions.count { it.isDuplicate }
        val account = accounts.firstOrNull()
        val complete = ready.transactions.count {
            !it.isDuplicate &&
                    (edits[it.fingerprint]?.amount ?: it.amount) != null &&
                    (edits[it.fingerprint]?.dateMillis ?: it.dateMillis) != null
        }

        Surface(tonalElevation = 3.dp) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    buildString {
                        append("$included of ${ready.transactions.size} selected")
                        if (flagged > 0) append(" · $flagged need a check")
                        if (duplicates > 0) append(" · $duplicates possible duplicate")
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showAccountPicker = true },
                        modifier = Modifier.weight(1f)
                    ) { Text(account?.name ?: "Choose account") }

                    Button(
                        onClick = { account?.let { viewModel.save(it.id) } },
                        enabled = account != null && included > 0,
                        modifier = Modifier.weight(1f)
                    ) { Text("Import $included") }
                }

                // Only ticks rows that can actually be written — ones missing a date or amount
                // stay unticked rather than inflating the count with a silent no-op.
                TextButton(
                    onClick = { viewModel.selectAllImportable() },
                    enabled = complete > 0 && complete < ready.transactions.size,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select all $complete readable rows")
                }
            }
        }
    }

    if (showPasteDialog) {
        PasteTextDialog(
            onDismiss = { showPasteDialog = false },
            onImport = { text ->
                showPasteDialog = false
                viewModel.importText(text)
            }
        )
    }

    if (showAccountPicker) {
        AccountPickerDialog(
            accounts = accounts,
            onDismiss = { showAccountPicker = false },
            onPick = { showAccountPicker = false }
        )
    }
}

@Composable
private fun SourcePicker(
    onImage: () -> Unit,
    onPdf: () -> Unit,
    onPaste: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Import transactions from a bank statement. Everything is read on this device — " +
                    "nothing is uploaded.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImage, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose a photo or screenshot")
        }
        OutlinedButton(onClick = onPdf, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose a PDF statement")
        }
        OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Paste statement text")
        }
    }
}

@Composable
private fun ScanningState() {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text("Reading your statement…", style = MaterialTheme.typography.bodyMedium)
        Text(
            "This runs on-device and can take a few seconds per page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SavedState(state: ImportState.Saved, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Imported ${state.count}", style = MaterialTheme.typography.headlineSmall)
        if (state.skipped > 0) {
            Text(
                "${state.skipped} skipped (already imported, missing an amount, or unselected).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun ReviewList(
    rows: List<ParsedTransaction>,
    edits: Map<String, ImportViewModel.Edit>,
    categories: List<Pair<Long, String>>,
    currency: java.text.NumberFormat,
    onToggle: (ParsedTransaction, Boolean) -> Unit,
    onMerchant: (ParsedTransaction, String) -> Unit,
    onAmount: (ParsedTransaction, Double?) -> Unit,
    onCategory: (ParsedTransaction, Long?) -> Unit,
    onSelectAll: (Boolean) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val allSelected = rows.all { edits[it.fingerprint]?.include == true }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Check these before saving",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                // "Select all" here only takes the readable rows; the bottom bar button does
                // the same thing, so the two never disagree about what will be imported.
                TextButton(
                    onClick = { onSelectAll(!allSelected) },
                    enabled = !allSelected
                ) {
                    Text(if (allSelected) "All selected" else "Select readable")
                }
            }
        }
        item {
            Text(
                "Rows marked for review had an unreadable amount, date, or merchant. " +
                        "Tap a row to fix or skip it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(rows, key = { it.fingerprint }) { row ->
            val edit = edits[row.fingerprint]
            ReviewRow(
                row = row,
                edit = edit,
                categories = categories,
                currency = currency,
                dateFormat = dateFormat,
                onToggle = { onToggle(row, it) },
                onMerchant = { onMerchant(row, it) },
                onAmount = { onAmount(row, it) },
                onCategory = { onCategory(row, it) }
            )
        }
    }
}

@Composable
private fun ReviewRow(
    row: ParsedTransaction,
    edit: ImportViewModel.Edit?,
    categories: List<Pair<Long, String>>,
    currency: java.text.NumberFormat,
    dateFormat: SimpleDateFormat,
    onToggle: (Boolean) -> Unit,
    onMerchant: (String) -> Unit,
    onAmount: (Double?) -> Unit,
    onCategory: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(row.needsReview) }
    val included = edit?.include ?: !row.needsReview
    val amount = edit?.amount ?: row.amount
    val merchant = edit?.merchant ?: row.merchant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !included -> MaterialTheme.colorScheme.surfaceVariant
                row.needsReview -> MaterialTheme.colorScheme.errorContainer
                row.isDuplicate -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            if (row.isDuplicate) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when (row.duplicateOf) {
                            ParsedTransaction.DuplicateReason.REPEATED_IN_FILE ->
                                "Repeated on this statement"
                            ParsedTransaction.DuplicateReason.ALREADY_IMPORTED ->
                                "Already in your transactions"
                            else -> "Possible duplicate"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = included, onCheckedChange = onToggle)
                Column(Modifier.weight(1f)) {
                    Text(
                        merchant.ifBlank { row.merchant },
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(
                        buildString {
                            row.dateMillis?.let { append(dateFormat.format(Date(it))) }
                            if (row.categoryName != null) {
                                if (isNotEmpty()) append(" · ")
                                append(row.categoryName)
                            }
                            if (row.isIncome) append(" · income")
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    amount?.let { currency.format(it) } ?: "no amount",
                    fontWeight = FontWeight.Bold,
                    color = if (amount == null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
            }

            if (row.needsReview) {
                Text(
                    "Raw text: ${row.rawText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Available on every row, not just flagged ones: a name OCR garbled but an
            // otherwise complete row still needs correcting before it's saved.
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    when {
                        expanded -> "Hide details"
                        row.needsReview -> "Fix this row"
                        else -> "Edit"
                    }
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = merchant,
                    onValueChange = onMerchant,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount?.toString().orEmpty(),
                    onValueChange = { text -> onAmount(text.toDoubleOrNull()) },
                    label = { Text("Amount") },
                    singleLine = true,
                    isError = amount == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { (id, name) ->
                        val selected = edit?.categoryId == id ||
                                (edit?.categoryId == null && row.categoryName.equals(name, ignoreCase = true))
                        FilterChip(
                            selected = selected,
                            onClick = { onCategory(if (selected) null else id) },
                            label = { Text(name) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PasteTextDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste statement text") },
        text = {
            Column {
                Text(
                    "Useful if your bank only offers a text copy of the statement.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("26 Sep, 2026\nCard Transaction\n…") },
                    modifier = Modifier.fillMaxWidth().height(240.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.isNotBlank()
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AccountPickerDialog(
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onPick: (Account) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import into which account?") },
        text = {
            Column {
                accounts.forEach { account ->
                    TextButton(onClick = { onPick(account) }, modifier = Modifier.fillMaxWidth()) {
                        Text(account.name, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
