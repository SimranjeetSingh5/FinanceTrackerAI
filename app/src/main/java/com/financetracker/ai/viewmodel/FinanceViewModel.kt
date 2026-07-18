package com.financetracker.ai.viewmodel

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.data.Account
import com.financetracker.ai.data.Category
import com.financetracker.ai.data.Transaction
import com.financetracker.ai.data.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

sealed class ModelState {
    object NotDownloaded : ModelState()
    object Downloading : ModelState()
    object Loading : ModelState()
    object Ready : ModelState()
    data class Error(val message: String) : ModelState()
}

class FinanceViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FinanceApp
    private val repo = app.repository
    private val gemma = app.gemmaHelper

    private val modelDownloadUrl = "https://storage.to/0zQRQhzZ0/download?expires=1784364026&signature=539085c8121b8b3db8c2f54c2ac1af8ab6903c2a9c5af092d6b9a481359a9afd"

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

        checkAndInitializeModel()
    }

    private fun checkAndInitializeModel() {
        if (gemma.isModelDownloaded()) {
            initializeModel()
        } else {
            _modelState.value = ModelState.NotDownloaded
        }
    }

    fun initializeModel() {
        viewModelScope.launch {
            _modelState.value = ModelState.Loading

            // Fixed the syntax by safely capturing gemma initialization into a Result block
            val result = withContext(Dispatchers.IO) {
                runCatching { gemma.initialize() }
            }

            result.onSuccess {
                _modelState.value = ModelState.Ready
            }.onFailure {
                _modelState.value = ModelState.Error(it.message ?: "Failed to load Gemma engine runtime.")
            }
        }
    }

    /**
     * Downloads the hosted .task package programmatically from Google Drive/Cloud storage.
     */


    fun downloadAndInstallModel() {
        viewModelScope.launch {
            _modelState.value = ModelState.Downloading

            try {
                val destFile = gemma.getLocalModelFile()
                if (destFile.exists()) destFile.delete()
                destFile.parentFile?.mkdirs()

                val tempStagingFile = File(app.getExternalCacheDir(), "gemma_staging.task")
                if (tempStagingFile.exists()) tempStagingFile.delete()

                val downloadManager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val request = DownloadManager.Request(Uri.parse(modelDownloadUrl))
                    .setTitle("Downloading AI Model")
                    .setDescription("Fetching Gemma local task package...")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationUri(Uri.fromFile(tempStagingFile))
                    .addRequestHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

                val downloadId = downloadManager.enqueue(request)

                val success = withContext(Dispatchers.IO) {
                    var downloading = true
                    var isSuccessful = false
                    while (downloading) {
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        if (cursor.moveToFirst()) {
                            val statusColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            if (statusColumnIndex != -1) {
                                val status = cursor.getInt(statusColumnIndex)
                                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                    downloading = false
                                    isSuccessful = true
                                } else if (status == DownloadManager.STATUS_FAILED) {
                                    downloading = false
                                }
                            }
                        } else {
                            downloading = false
                        }
                        cursor.close()
                        if (downloading) {
                            Thread.sleep(1000)
                        }
                    }
                    isSuccessful
                }

                if (success && tempStagingFile.exists()) {
                    _modelState.value = ModelState.Loading

                    withContext(Dispatchers.IO) {
                        FileInputStream(tempStagingFile).use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempStagingFile.delete()
                    }

                    if (destFile.exists() && destFile.length() > 50_000_000) {
                        initializeModel()
                    } else {
                        _modelState.value = ModelState.Error("Downloaded file is empty or corrupted.")
                    }
                } else {
                    _modelState.value = ModelState.Error("System DownloadManager failed to download the file.")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _modelState.value = ModelState.Error("Download pipeline failed: ${e.message}")
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
                useAiCategorization = manualCategoryId == null && _modelState.value == ModelState.Ready,
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
