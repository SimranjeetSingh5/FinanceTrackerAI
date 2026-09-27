package com.financetracker.ai.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.ai.ModelDownloader
import com.financetracker.ai.ai.SpendingFacts
import com.financetracker.ai.data.Account
import com.financetracker.ai.data.Category
import com.financetracker.ai.data.Transaction
import com.financetracker.ai.data.TransactionType
import com.financetracker.ai.util.Constants
import com.financetracker.ai.util.ModelDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class ModelState {
    object NotDownloaded : ModelState()

    /** [progress] is 0..1 when the size is known, else 0. [resumedFrom] is the prior byte count. */
    data class Downloading(
        val progress: Float = 0f,
        val source: String = "",
        val resumedFrom: Long = 0L
    ) : ModelState()

    object Loading : ModelState()
    object Ready : ModelState()
    data class Error(val message: String) : ModelState()
}

class FinanceViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FinanceApp
    private val repo = app.repository
    private val gemma = app.gemmaHelper
    private val settings = app.settingsStore

    val currency: StateFlow<String> = settings.currencyCodeFlow

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /** True when Gemma is loaded and inference calls will actually return results. */
    val isModelReady: StateFlow<Boolean> = app.modelReady

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

    /** True while the on-device model is still writing the prose layer. */
    private val _insightGenerating = MutableStateFlow(false)
    val insightGenerating: StateFlow<Boolean> = _insightGenerating.asStateFlow()

    private val _monthlyIncome = MutableStateFlow(0.0)
    val monthlyIncome: StateFlow<Double> = _monthlyIncome.asStateFlow()
    private val _monthlyExpense = MutableStateFlow(0.0)
    val monthlyExpense: StateFlow<Double> = _monthlyExpense.asStateFlow()

    init {
        viewModelScope.launch {
            repo.ensureDefaultCategories()
            repo.ensureDefaultAccount()
        }
        viewModelScope.launch { repo.allTransactions.collect { _transactions.value = it; refreshTotals(); refreshAccountBalances(_accounts.value) } }
        viewModelScope.launch { repo.allCategories.collect { _categories.value = it } }
        viewModelScope.launch { repo.allAccounts.collect { list -> _accounts.value = list; refreshAccountBalances(list) } }

        observeDownloadWork()
        checkAndInitializeModel()
    }

    private fun checkAndInitializeModel() {
        if (gemma.isModelDownloaded()) {
            initializeModel()
        } else {
            // A download may already be in flight from a previous session.
            _modelState.value = ModelState.NotDownloaded
        }
    }

    /** Diagnostic log for the model download/load path. Filter: `adb logcat -s ModelDownload`. */
    private fun log(message: String) = ModelDownloader.log(message)

    fun initializeModel() {
        viewModelScope.launch {
            _modelState.value = ModelState.Loading
            app.isModelLoaded = false
            log("initializeModel() starting; modelFile=${gemma.getLocalModelFile().absolutePath} " +
                    "downloaded=${gemma.isModelDownloaded()}")

            val result = withContext(Dispatchers.IO) {
                runCatching { gemma.initialize() }
            }

            result.onSuccess {
                log("initializeModel() OK")
                _modelState.value = ModelState.Ready
                app.isModelLoaded = true
            }.onFailure {
                log("initializeModel() FAILED: ${it.message}")
                _modelState.value = ModelState.Error(it.message ?: "Failed to load Gemma engine runtime.")
                app.isModelLoaded = false
            }
        }
    }

    /** True when a previous download left a resumable partial file on disk. */
    fun hasPartialDownload(): Boolean = ModelDownloader.partialBytes(partFile()) > 0

    private fun partFile(): File {
        val dest = gemma.getLocalModelFile()
        return File(dest.parentFile, "${dest.name}.part")
    }

    /**
     * True when the device has a validated internet connection. Note this is a *capability*
     * check, not a reachability test — Android can report a validated network that still can't
     * resolve a given host, so a real download can still fail after this returns true.
     */
    private suspend fun hasInternet(): Boolean = withContext(Dispatchers.IO) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) return@withContext false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
        log("NET check -> $result")
        result
    }

    /**
     * Hands the transfer to [ModelDownloadWorker] so it keeps running when the user leaves the
     * app or the process is killed. Progress stays in the .part file, so a retry — whether the
     * user taps again or WorkManager reschedules — resumes rather than starting over.
     */
    fun downloadAndInstallModel() {
        viewModelScope.launch {
            if (!hasInternet()) {
                _modelState.value = ModelState.Error(
                    "No internet connection. The model downloads once, then everything works offline — " +
                            "connect to Wi-Fi or mobile data and try again."
                )
                return@launch
            }

            val alreadyHave = ModelDownloader.partialBytes(partFile())
            _modelState.value = ModelState.Downloading(0f, "Starting", alreadyHave)

            log("enqueueing ModelDownloadWorker (resuming from $alreadyHave bytes)")
            ModelDownloadWorker.enqueue(app)
        }
    }

    /**
     * Mirrors the background worker's progress into [modelState] so the setup screen keeps
     * updating even though the transfer now lives outside this ViewModel, and loads the model
     * once the file lands.
     */
    private fun observeDownloadWork() {
        viewModelScope.launch {
            WorkManager.getInstance(app)
                .getWorkInfosForUniqueWorkFlow(Constants.WORK_NAME_DOWNLOAD)
                .collect { infos ->
                    val work = infos.firstOrNull() ?: return@collect
                    log("work state=${work.state} progress=${work.progress.keyValueMap}")
                    when (work.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                            if (_modelState.value is ModelState.Downloading) {
                                _modelState.value = ModelState.NotDownloaded
                            }

                        WorkInfo.State.RUNNING -> {
                            val done = work.progress.getLong(Constants.PROGRESS_BYTES_DONE, 0L)
                            val total = work.progress.getLong(Constants.PROGRESS_BYTES_TOTAL, -1L)
                            _modelState.value = ModelState.Downloading(
                                progress = if (total > 0) done.toFloat() / total else 0f,
                                source = "Background"
                            )
                        }

                        WorkInfo.State.SUCCEEDED -> {
                            log("worker succeeded; loading model")
                            if (gemma.isModelDownloaded()) initializeModel()
                        }

                        WorkInfo.State.FAILED -> {
                            log("worker failed")
                            _modelState.value = ModelState.Error(
                                "Download failed. Any progress was kept — tap retry to continue."
                            )
                        }

                        WorkInfo.State.CANCELLED -> {
                            if (_modelState.value is ModelState.Downloading) {
                                _modelState.value = ModelState.NotDownloaded
                            }
                        }
                    }
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

    /**
     * Two-stage insight: the arithmetic renders immediately from [SpendingFacts], then the
     * on-device model appends a prose layer. The user gets real numbers in milliseconds instead
     * of waiting on inference to describe numbers we already knew.
     */
    fun generateMonthlyInsight() {
        viewModelScope.launch {
            val periodLabel = "this month"
            val t0 = SystemClock.elapsedRealtime()

            val summary = withContext(Dispatchers.IO) { repo.spendingSummary(periodLabel) }
            _insight.value = SpendingFacts.toPlainText(summary, periodLabel)
            log("facts rendered in ${SystemClock.elapsedRealtime() - t0}ms")

            if (_modelState.value !is ModelState.Ready) {
                _insight.value = "${_insight.value}\n\nSet up the on-device model for a written summary."
                return@launch
            }

            _insightGenerating.value = true
            repo.getInsight(periodLabel)
                .onSuccess {
                    log("AI narration generated in ${SystemClock.elapsedRealtime() - t0}ms")
                    _insight.value = "${_insight.value}\n\n$it"
                }
                .onFailure {
                    log("narration failed after ${SystemClock.elapsedRealtime() - t0}ms: ${it.message}")
                    // The numbers are already on screen, so a failed narration isn't fatal.
                }
            _insightGenerating.value = false
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

    /**
     * Recomputes every account balance from [accounts] rather than reading `_accounts.value`,
     * so a transaction arriving before the account list has been emitted still balances
     * against the correct account set.
     */
    private fun refreshAccountBalances(accounts: List<Account>) {
        viewModelScope.launch {
            val balances = accounts.associate { it.id to repo.accountBalance(it) }
            _accountBalances.value = balances
            _netWorth.value = balances.values.sum()
        }
    }
}
