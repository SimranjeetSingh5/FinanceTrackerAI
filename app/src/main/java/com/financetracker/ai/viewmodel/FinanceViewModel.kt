package com.financetracker.ai.viewmodel

import android.app.Application
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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

    // IMPORTANT: This is Temporary link update this URL or hosts it permanently on Firebase.
    private val modelDownloadUrl = "https://storage.to/Q61rIvpxi/download?expires=1785241528&signature=212dea9cd0f77976e3cbdc807214b2f9da448ab4d7c49c95a3f985fb94fac952"

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

    fun downloadAndInstallModel() {
        viewModelScope.launch {
            _modelState.value = ModelState.Downloading

            try {
                val destFile = gemma.getLocalModelFile()
                val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")

                // Cleanup existing files before starting fresh
                if (tempFile.exists()) tempFile.delete()
                destFile.parentFile?.mkdirs()

                val result = withContext(Dispatchers.IO) {
                    downloadFileWithRedirects(modelDownloadUrl, tempFile)
                }

                when (result) {
                    is DownloadResult.Success -> {
                        // Atomic swap of the temporary file to destination file
                        if (tempFile.exists()) {
                            if (destFile.exists()) destFile.delete()
                            val renamed = tempFile.renameTo(destFile)

                            if (renamed && destFile.exists() && destFile.length() > 0) {
                                _modelState.value = ModelState.Loading
                                initializeModel()
                            } else {
                                _modelState.value = ModelState.Error("Failed to commit downloaded model file.")
                            }
                        } else {
                            _modelState.value = ModelState.Error("Downloaded temp file missing.")
                        }
                    }
                    is DownloadResult.Error -> {
                        if (tempFile.exists()) tempFile.delete()
                        _modelState.value = ModelState.Error(result.message)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _modelState.value = ModelState.Error("Download pipeline failed: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Downloads a file handling cross-protocol (HTTP -> HTTPS) redirects,
     * validating content length to prevent broken/incomplete file writes.
     */
    private suspend fun downloadFileWithRedirects(
        urlString: String,
        targetFile: File,
        maxRedirects: Int = 5
    ): DownloadResult {
        var currentUrl = urlString
        var redirects = 0

        while (redirects < maxRedirects) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Android/FinanceApp")
                    instanceFollowRedirects = false // Manual handling prevents protocol-drop issues
                    connectTimeout = 30_000
                    readTimeout = 30_000
                }

                val responseCode = connection.responseCode

                // Handle Redirects manually (HTTP 301, 302, 303, 307, 308)
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return DownloadResult.Error("Redirected with no Location header.")

                    currentUrl = if (location.startsWith("http")) location else URL(url, location).toString()
                    redirects++
                    continue
                }

                if (responseCode !in 200..299) {
                    return DownloadResult.Error("Server returned HTTP response code: $responseCode")
                }

                val contentLength = connection.contentLengthLong
                var bytesDownloaded = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                        }
                        output.flush()
                    }
                }

                // Verify file isn't truncated
                if (contentLength > 0 && bytesDownloaded != contentLength) {
                    return DownloadResult.Error("Download incomplete: Expected $contentLength bytes, got $bytesDownloaded.")
                }

                return DownloadResult.Success

            } catch (e: Exception) {
                return DownloadResult.Error("Network error: ${e.localizedMessage}")
            } finally {
                connection?.disconnect()
            }
        }

        return DownloadResult.Error("Too many redirects.")
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

private sealed class DownloadResult {
    object Success : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}
