package com.financetracker.ai.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.ai.BuildConfig
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.data.Category
import com.financetracker.ai.data.TransactionType
import com.financetracker.ai.importing.ParsedTransaction
import com.financetracker.ai.importing.StatementParser
import com.financetracker.ai.importing.StatementScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/** What the import screen is currently doing. */
sealed class ImportState {
    object Idle : ImportState()
    object Scanning : ImportState()
    data class Ready(val transactions: List<ParsedTransaction>) : ImportState()
    data class Failed(val message: String) : ImportState()
    object Saving : ImportState()
    data class Saved(val count: Int, val skipped: Int) : ImportState()
}

/**
 * Drives statement import: scan → parse → let the user review → save.
 *
 * Nothing reaches the database until [save] is called with rows the user has confirmed, because
 * OCR on a real statement is not reliable enough to import blind — a wrong amount silently
 * corrupts a budget, and rows with an unreadable amount can't be recovered after the fact.
 */
class ImportViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FinanceApp
    private val repo = app.repository
    private val scanner = StatementScanner(app)

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** Edits made on the review screen, keyed by the row's fingerprint. */
    private val _edits = MutableStateFlow<Map<String, Edit>>(emptyMap())
    val edits: StateFlow<Map<String, Edit>> = _edits.asStateFlow()

    data class Edit(
        val amount: Double?,
        val dateMillis: Long?,
        val merchant: String?,
        val categoryId: Long?,
        val include: Boolean
    )

    val categories: StateFlow<List<Category>> = repo.allCategories.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    fun importImage(uri: Uri) = scan { scanner.scanImage(uri) }

    fun importPdf(uri: Uri) = scan { scanner.scanPdf(uri) }

    fun importText(text: String) = scan { scanner.scanText(text) }

    private fun scan(block: suspend () -> com.financetracker.ai.importing.ScannedStatement) {
        viewModelScope.launch {
            _state.value = ImportState.Scanning
            try {
                val text = block().text
                if (text.isBlank()) {
                    _state.value = ImportState.Failed(
                        "Couldn't read any text from that file. Try a clearer photo, or paste the text instead."
                    )
                    return@launch
                }
                // Flag against what's already in the app so a re-imported statement shows its
                // duplicates rather than silently skipping them.
                val existing = StatementParser.existingKeysFor(repo.allTransactionsForExport())
                val parsed = StatementParser.markDuplicates(StatementParser.parse(text), existing)
                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "parse() returned ${parsed.size} rows from ${text.length} chars")
                    parsed.take(20).forEach { row ->
                        Log.d(
                            LOG_TAG,
                            "  row: date=${row.dateMillis != null} amt=${row.amount} " +
                                    "merch='${row.merchant}' cat=${row.categoryName} " +
                                    "review=${row.needsReview} raw='${row.rawText.take(90)}'"
                        )
                    }
                }
                if (parsed.isEmpty()) {
                    _state.value = ImportState.Failed(diagnoseEmptyResult(text))
                    return@launch
                }
                // Pre-tick rows the parser is confident about. Duplicates and rows it is unsure
                // about stay unticked, so the user's first job is exactly the set of rows that
                // need a decision rather than the whole statement.
                _edits.value = parsed.associate { row ->
                    row.fingerprint to Edit(
                        amount = row.amount,
                        dateMillis = row.dateMillis,
                        merchant = row.merchant,
                        categoryId = null,
                        include = !row.needsReview && !row.isDuplicate
                    )
                }
                _state.value = ImportState.Ready(parsed)
            } catch (e: Exception) {
                _state.value = ImportState.Failed(e.message ?: "Import failed.")
            }
        }
    }

    /**
     * Explains *why* a statement produced nothing, since "0 rows" is unhelpful on its own.
     *
     * The most common cause is the wrong page: statements are often a multi-page PDF or
     * screenshot set, and the user picks a fees-and-charges page rather than the activity
     * page. Recognising that by its vocabulary is far more actionable than a parse failure.
     */
    private fun diagnoseEmptyResult(text: String): String {
        val lower = text.lowercase()
        val hasDates = text.lines().any { StatementParser.isDateLine(it) }

        // Placeholder dates and stock-template watermarks mean this isn't a real statement —
        // usually a sample downloaded from a bank's website rather than the user's own PDF.
        val isTemplate = listOf(
            "mm/dd/yyyy", "templatelab", "<branch name>", "lorem", "placeholder"
        ).any { lower.contains(it) }

        val feeWords = listOf(
            "monthly fee", "per account", "per additional statement", "service charge",
            "nsf charge", "check overdraft", "stop payment", "bank draft", "atm fee",
            "wire transfer fee", "maintenance fee", "interest rate", "daily limit"
        )
        val feeHits = feeWords.count { lower.contains(it) }

        return when {
            isTemplate ->
                "This looks like a sample or template statement — the dates read as " +
                        "'mm/dd/yyyy' and the page carries template branding. Download the " +
                        "PDF of your own account from your bank's website and import that."

            !hasDates && feeHits >= 2 ->
                "This looks like a fees-and-charges page, not your transactions. " +
                        "Open the page that lists your recent activity — usually labelled " +
                        "'Transaction activity', 'Account activity' or 'Recent transactions'."

            !hasDates ->
                "Couldn't find any dates on this page. Statements are usually several pages " +
                        "long — pick the one listing your recent transactions."

            else -> {
                // Show the user what we actually saw. "Couldn't make sense of it" is useless
                // on its own; the date-like lines usually make the problem obvious — a running
                // balance mistaken for a row, or a date format the reader doesn't know.
                val dateLike = text.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && StatementParser.isDateLine(it) }
                    .take(4)
                val sample = if (dateLike.isEmpty()) "" else
                    "\n\nRecognised date lines:\n" + dateLike.joinToString("\n") { "• $it" }

                "Found dates but couldn't make sense of the rows.$sample\n\n" +
                        "Try a clearer photo, or paste the text instead."
            }
        }
    }

    /** Existing edit for a row, or a fresh one seeded from what the parser found. */
    private fun editFor(row: ParsedTransaction, include: Boolean = true): Edit =
        _edits.value[row.fingerprint]
            ?: Edit(row.amount, row.dateMillis, row.merchant, null, include)

    fun setAmount(row: ParsedTransaction, amount: Double?) {
        _edits.value = _edits.value.toMutableMap().apply {
            this[row.fingerprint] = editFor(row).copy(amount = amount)
        }
    }

    /**
     * Corrects the merchant name. OCR garbles these constantly ("STARBUKS", "Mukesh" from a
     * payment reference), and the name is what the user will recognise later, so it's editable
     * like any other field.
     */
    fun setMerchant(row: ParsedTransaction, merchant: String) {
        _edits.value = _edits.value.toMutableMap().apply {
            this[row.fingerprint] = editFor(row).copy(merchant = merchant)
        }
    }

    fun setCategory(row: ParsedTransaction, categoryId: Long?) {
        _edits.value = _edits.value.toMutableMap().apply {
            this[row.fingerprint] = editFor(row).copy(categoryId = categoryId)
        }
    }

    fun setIncluded(row: ParsedTransaction, include: Boolean) {
        _edits.value = _edits.value.toMutableMap().apply {
            this[row.fingerprint] = editFor(row).copy(include = include)
        }
    }

    fun includeAll(include: Boolean) {
        _edits.value = _edits.value.mapValues { (_, e) -> e.copy(include = include) }
    }

    /**
     * Ticks every row that can actually be written and isn't a likely duplicate.
     *
     * Rows missing a date or amount can't be saved, and duplicates should be a deliberate
     * choice — ticking either would inflate the count with rows that get skipped anyway.
     */
    fun selectAllImportable() {
        val ready = _state.value as? ImportState.Ready ?: return
        _edits.value = _edits.value.mapValues { (print, edit) ->
            val row = ready.transactions.firstOrNull { it.fingerprint == print }
            val usable = (edit.amount ?: row?.amount) != null &&
                    (edit.dateMillis ?: row?.dateMillis) != null
            edit.copy(include = usable && row?.isDuplicate != true)
        }
    }

    fun reset() {
        _state.value = ImportState.Idle
        _edits.value = emptyMap()
    }

    /**
     * Writes the confirmed rows. Categories come from the statement's own column when we could
     * map it; anything left uncategorised is left for the user rather than guessed, since
     * auto-assigning here would silently rewrite their budget structure.
     */
    fun save(accountId: Long) {
        val ready = _state.value as? ImportState.Ready ?: return
        viewModelScope.launch {
            _state.value = ImportState.Saving

            val categoryList = categories.value
            val byName = categoryList.associateBy { it.name.lowercase() }
            var saved = 0
            var skipped = 0

            for (row in ready.transactions) {
                val edit = _edits.value[row.fingerprint] ?: continue
                if (!edit.include) { skipped++; continue }
                val amount = edit.amount
                val timestamp = edit.dateMillis
                if (amount == null || timestamp == null) { skipped++; continue }

                val category = edit.categoryId?.let { id -> categoryList.firstOrNull { it.id == id } }
                    ?: row.categoryName?.let { byName[it.lowercase()] }
                    ?: categoryList.firstOrNull { it.name == "Other" }
                    ?: return@launch

                // Duplicates are the user's decision, not ours. They arrived unticked and, if
                // ticked, are saved — re-importing a statement is a legitimate thing to want.
                val merchant = edit.merchant?.takeIf { it.isNotBlank() } ?: row.merchant
                repo.addTransaction(
                    amount = amount,
                    note = merchant,
                    merchant = merchant,
                    type = if (row.isIncome) TransactionType.INCOME else TransactionType.EXPENSE,
                    accountId = accountId,
                    manualCategoryId = category.id,
                    timestamp = timestamp,
                    useAiCategorization = false
                )
                saved++
            }

            _state.value = ImportState.Saved(saved, skipped)
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanner.close()
    }

    private companion object {
        const val LOG_TAG = "StatementImport"
    }
}
