package com.financetracker.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.data.Account
import com.financetracker.ai.data.Category
import com.financetracker.ai.data.Transaction
import com.financetracker.ai.data.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class ModelState {
    object NotDownloaded : ModelState()
    object Loading : ModelState()
    object Ready : ModelState()
    data class Error(val message: String) : ModelState()
}

class FinanceViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FinanceApp
    private val repo = app.repository
    private val gemma = app.gemmaHelper

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _accountBalances = MutableStateFlow<Map<Long, Double>>(emptyMap())
    val accountBalances: StateFlow<Map<Long, Double>> = _accountBalances.asStateFlow()

    private val _netWorth = MutableStateFlow(0.0)
    val netWorth: StateFlow<Double> = _netWorth.asStateFlow()

    private val _insight = MutableStateFlow<String?>(null)
    val insight: StateFlow<String?> = _insight.asStateFlow()

    private val _monthlyIncome = MutableStateFlow(0.0)
    val monthlyIncome: StateFlow<Double> = _monthlyIncome.asStateFlow()
    private val _monthlyExpense = MutableStateFlow(0.0)
    val monthlyExpense: StateFlow<Double> = _monthlyExpense.asStateFlow()

    init {
        viewModelScope.launch {
            repo.ensureDefaultCategories()
            repo.ensureDefaultAccount()
        }
        viewModelScope.launch { repo.allTransactions.collect { _transactions.value = it; refreshTotals(); refreshAccountBalances() } }
        viewModelScope.launch { repo.allCategories.collect { _categories.value = it } }
        viewModelScope.launch { repo.allAccounts.collect { list -> _accounts.value = list; refreshAccountBalances() } }
        if (gemma.modelFileExists()) initializeModel()
    }

    fun initializeModel() {
        viewModelScope.launch {
            _modelState.value = ModelState.Loading
            gemma.initialize(modelPath = gemma.defaultModelPath())
                .onSuccess { _modelState.value = ModelState.Ready }
                .onFailure { _modelState.value = ModelState.Error(it.message ?: "Failed to load Gemma model") }
        }
    }

    fun installModelFromUri(inputStream: java.io.InputStream) {
        viewModelScope.launch {
            _modelState.value = ModelState.Loading
            try {
                val dest = File(gemma.defaultModelPath())
                dest.parentFile?.mkdirs()
                dest.outputStream().use { out -> inputStream.copyTo(out) }
                initializeModel()
            } catch (e: Exception) {
                _modelState.value = ModelState.Error(e.message ?: "Failed to install model")
            }
        }
    }

    fun addTransaction(
        amount: Double,
        note: String,
        merchant: String?,
        type: TransactionType,
        accountId: Long,
        manualCategoryId: Long? = null,
        transferToAccountId: Long? = null
    ) {
        viewModelScope.launch {
            repo.addTransaction(
                amount = amount,
                note = note,
                merchant = merchant,
                type = type,
                accountId = accountId,
                manualCategoryId = manualCategoryId,
                useAiCategorization = manualCategoryId == null && modelState.value == ModelState.Ready,
                transferToAccountId = transferToAccountId
            )
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch { repo.deleteTransaction(transaction) }
    }

    fun addAccount(account: Account) {
        viewModelScope.launch { repo.addAccount(account) }
    }

    fun generateMonthlyInsight() {
        viewModelScope.launch {
            _insight.value = "Thinking..."
            repo.getInsight("this month")
                .onSuccess { _insight.value = it }
                .onFailure { _insight.value = "Couldn't generate insight: ${it.message}" }
        }
    }

    private fun refreshTotals() {
        viewModelScope.launch {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            val start = cal.timeInMillis
            val end = System.currentTimeMillis()
            val (income, expense) = repo.totalsFor(start, end)
            _monthlyIncome.value = income
            _monthlyExpense.value = expense
        }
    }

    private fun refreshAccountBalances() {
        viewModelScope.launch {
            val balances = _accounts.value.associate { it.id to repo.accountBalance(it) }
            _accountBalances.value = balances
            _netWorth.value = balances.values.sum()
        }
    }
}
