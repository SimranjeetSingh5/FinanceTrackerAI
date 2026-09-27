package com.financetracker.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.data.Account
import com.financetracker.ai.data.Category
import com.financetracker.ai.data.Transaction
import com.financetracker.ai.data.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The user-facing filter set for the transactions list. Nulls mean "no constraint". */
data class TransactionFilter(
    val query: String = "",
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val type: TransactionType? = null
)

/**
 * Backs the transactions list. Filtering is done by the Room `search()` query rather than in
 * memory, so the text box stays responsive as the history grows.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TransactionsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as FinanceApp).repository

    private val _filter = MutableStateFlow(TransactionFilter())
    val filter: StateFlow<TransactionFilter> = _filter.asStateFlow()

    private val _categories: StateFlow<List<Category>> = repo.allCategories.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    val categories: StateFlow<List<Category>> = _categories

    private val _accounts: StateFlow<List<Account>> = repo.allAccounts.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    val accounts: StateFlow<List<Account>> = _accounts

    // Debounced so we don't re-query Room on every keystroke.
    val transactions: StateFlow<List<Transaction>> =
        _filter
            .debounce { if (it.query.isBlank()) 0L else 250L }
            .flatMapLatest { repo.searchTransactions(it.query, it.categoryId, it.accountId, it.type) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether any filter is active — drives the "Clear all" affordance, independent of
     *  whether the current filters happen to match anything. */
    val isFiltered: StateFlow<Boolean> =
        _filter.map { it.isNarrowed() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
    }

    fun setCategory(categoryId: Long?) {
        _filter.value = _filter.value.copy(categoryId = categoryId)
    }

    fun setAccount(accountId: Long?) {
        _filter.value = _filter.value.copy(accountId = accountId)
    }

    fun setType(type: TransactionType?) {
        _filter.value = _filter.value.copy(type = type)
    }

    fun clearFilters() {
        _filter.value = TransactionFilter()
    }

    fun delete(transaction: Transaction) {
        viewModelScope.launch { repo.deleteTransaction(transaction) }
    }

    fun save(
        original: Transaction,
        amount: Double,
        note: String,
        merchant: String?,
        type: TransactionType,
        accountId: Long,
        categoryId: Long
    ) {
        viewModelScope.launch {
            repo.updateTransaction(
                original.copy(
                    amount = amount,
                    note = note,
                    merchant = merchant,
                    type = type,
                    accountId = accountId,
                    categoryId = categoryId,
                    // A hand-edited category is no longer an AI guess.
                    aiCategorized = original.aiCategorized && original.categoryId == categoryId
                )
            )
        }
    }
}

private fun TransactionFilter.isNarrowed(): Boolean =
    query.isNotBlank() || categoryId != null || accountId != null || type != null
