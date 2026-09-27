package com.financetracker.ai.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
                val parsed = StatementParser.parse(text)
                if (parsed.isEmpty()) {
                    _state.value = ImportState.Failed(
                        "No transactions recognised in that statement. Each row needs at least a date."
                    )
                    return@launch
                }
                // Pre-tick rows the parser is unsure about, so the user's first job is the
                // small number of rows that actually need attention.
                _edits.value = parsed.associate { row ->
                    row.fingerprint to Edit(row.amount, row.dateMillis, null, !row.needsReview)
                }
                _state.value = ImportState.Ready(parsed)
            } catch (e: Exception) {
                _state.value = ImportState.Failed(e.message ?: "Import failed.")
            }
        }
    }

    fun setAmount(row: ParsedTransaction, amount: Double?) {
        _edits.value = _edits.value.toMutableMap().apply {
            this[row.fingerprint] = (this[row.fingerprint] ?: Edit(row.amount, row.dateMillis, null, true))
                .copy(amount = amount)
        }
    }

    fun setCategory(row: ParsedTransaction, categoryId: Long?) {
        _edits.value = _edits.value.toMutableMap().apply {
            this[row.fingerprint] = (this[row.fingerprint] ?: Edit(row.amount, row.dateMillis, null, true))
                .copy(categoryId = categoryId)
        }
    }

    fun setIncluded(row: ParsedTransaction, include: Boolean) {
        _edits.value = _edits.value.toMutableMap().apply {
            this[row.fingerprint] = (this[row.fingerprint] ?: Edit(row.amount, row.dateMillis, null, true))
                .copy(include = include)
        }
    }

    fun includeAll(include: Boolean) {
        _edits.value = _edits.value.mapValues { (_, e) -> e.copy(include = include) }
    }

    /**
     * Ticks every row that can actually be written — those with both a date and an amount.
     *
     * Rows missing either can't be saved, so ticking them would inflate the count and produce
     * a silent no-op on import. They stay unticked so the user is left looking at exactly the
     * rows that need a decision.
     */
    fun selectAllImportable() {
        val ready = _state.value as? ImportState.Ready ?: return
        _edits.value = _edits.value.mapValues { (print, edit) ->
            val row = ready.transactions.firstOrNull { it.fingerprint == print }
            val usable = (edit.amount ?: row?.amount) != null &&
                    (edit.dateMillis ?: row?.dateMillis) != null
            edit.copy(include = usable)
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
            val existing = repo.allTransactionsForExport()
            val existingPrints = existing.mapNotNull { fingerprintOf(it.timestamp, it.amount, it.note) }.toHashSet()

            var saved = 0
            var skipped = 0

            for (row in ready.transactions) {
                val edit = _edits.value[row.fingerprint] ?: continue
                if (!edit.include) { skipped++; continue }
                val amount = edit.amount
                val timestamp = edit.dateMillis
                if (amount == null || timestamp == null) { skipped++; continue }

                val print = fingerprintOf(timestamp, amount, row.merchant)
                if (print != null && print in existingPrints) { skipped++; continue }

                val category = edit.categoryId?.let { id -> categoryList.firstOrNull { it.id == id } }
                    ?: row.categoryName?.let { byName[it.lowercase()] }
                    ?: categoryList.firstOrNull { it.name == "Other" }
                    ?: return@launch

                repo.addTransaction(
                    amount = amount,
                    note = row.merchant,
                    merchant = row.merchant,
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

    /** Same shape as the parser's fingerprint, so re-imports of the same statement collide. */
    private fun fingerprintOf(timestamp: Long, amount: Double, merchant: String?): String? {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val day = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
        val amt = String.format(java.util.Locale.US, "%.2f", amount)
        val who = (merchant ?: "").lowercase().replace(Regex("[^a-z0-9]"), "").take(24)
        return "$day|$amt|$who"
    }

    override fun onCleared() {
        super.onCleared()
        scanner.close()
    }
}
