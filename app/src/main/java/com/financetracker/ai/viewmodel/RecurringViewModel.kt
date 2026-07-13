package com.financetracker.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.data.RecurrenceFrequency
import com.financetracker.ai.data.RecurringTransaction
import com.financetracker.ai.data.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecurringViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as FinanceApp).repository

    private val _items = MutableStateFlow<List<RecurringTransaction>>(emptyList())
    val items: StateFlow<List<RecurringTransaction>> = _items.asStateFlow()

    init {
        viewModelScope.launch { repo.allRecurring.collect { _items.value = it } }
    }

    fun addRecurring(
        amount: Double,
        note: String,
        categoryId: Long,
        accountId: Long,
        type: TransactionType,
        frequency: RecurrenceFrequency,
        nextDueDate: Long,
        autoAdd: Boolean
    ) {
        viewModelScope.launch {
            repo.addRecurring(
                RecurringTransaction(
                    amount = amount, note = note, categoryId = categoryId, accountId = accountId,
                    type = type, frequency = frequency, nextDueDate = nextDueDate, autoAdd = autoAdd
                )
            )
        }
    }

    fun toggleActive(item: RecurringTransaction) {
        viewModelScope.launch { repo.updateRecurring(item.copy(isActive = !item.isActive)) }
    }

    fun delete(item: RecurringTransaction) {
        viewModelScope.launch { repo.deleteRecurring(item) }
    }
}
