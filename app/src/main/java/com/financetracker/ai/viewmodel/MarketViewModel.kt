package com.financetracker.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.ai.FinanceApp
import com.financetracker.ai.market.MarketDataService
import com.financetracker.ai.market.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

/** What the market screen is currently doing. */
sealed class MarketState {
    object Idle : MarketState()
    object Loading : MarketState()
    data class Loaded(val quotes: List<Quote>, val at: Long) : MarketState()
    data class Failed(val message: String) : MarketState()
}

/** Progress of the one-year history request for a single symbol. */
sealed class ChartState {
    object None : ChartState()
    object Loading : ChartState()
    data class Ready(val series: com.financetracker.ai.market.PriceSeries) : ChartState()
    data class Failed(val message: String) : ChartState()
}

/**
 * Loads quotes for the user's watchlist.
 *
 * This is the one place the app touches the network, and it is entirely additive: a failure
 * leaves the rest of the app untouched, and the model, the parser, and every financial screen
 * keep working with no connection at all.
 */
class MarketViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = (application as FinanceApp).settingsStore

    private val _state = MutableStateFlow<MarketState>(MarketState.Idle)
    val state: StateFlow<MarketState> = _state.asStateFlow()

    private val _chart = MutableStateFlow<ChartState>(ChartState.None)
    val chart: StateFlow<ChartState> = _chart.asStateFlow()

    val watchlist: StateFlow<List<String>> = settings.watchlistFlow

    fun refresh() {
        val symbols = watchlist.value
        if (symbols.isEmpty()) {
            _state.value = MarketState.Idle
            return
        }

        viewModelScope.launch {
            _state.value = MarketState.Loading
            val quotes = withContext(Dispatchers.IO) { MarketDataService.fetch(symbols) }
            val anyData = quotes.any { it.hasData }
            _state.value = if (anyData) {
                MarketState.Loaded(quotes, System.currentTimeMillis())
            } else {
                // Distinguish the two failure modes, because they need different fixes: no
                // network at all, versus a response we couldn't read.
                val offline = !MarketDataService.hasNetwork(getApplication())
                MarketState.Failed(
                    if (offline) {
                        "No internet connection. Everything else in the app works offline."
                    } else {
                        "Connected, but no quote came back for ${symbols.joinToString(", ")}. " +
                                "The ticker may be unlisted, or the data service may have " +
                                "changed its response."
                    }
                )
            }
        }
    }

    fun addSymbol(symbol: String) {
        val cleaned = symbol.trim().uppercase(java.util.Locale.US)
        if (cleaned.isEmpty()) return
        if (watchlist.value.contains(cleaned)) return
        if (watchlist.value.size >= MarketDataService.MAX_SYMBOLS) return
        settings.setWatchlist(watchlist.value + cleaned)
        refresh()
    }

    fun removeSymbol(symbol: String) {
        settings.setWatchlist(watchlist.value - symbol)
        refresh()
    }

    /**
     * Loads the one-year history for [symbol]. Separate from [refresh] so opening a chart is
     * on-demand rather than ten extra requests whenever the watchlist refreshes.
     */
    fun loadChart(symbol: String) {
        loadedFor = symbol
        viewModelScope.launch {
            // Clear first: otherwise the previous symbol's chart would be drawn under the new
            // one while this request is still in flight.
            _chart.value = ChartState.Loading
            val result = withContext(Dispatchers.IO) {
                MarketDataService.fetchChart(symbol, MarketDataService.RANGE_1Y)
            }
            // A late response for an earlier symbol must not overwrite a newer one.
            if (loadedFor != symbol) return@launch
            _chart.value = result.fold(
                onSuccess = { ChartState.Ready(it) },
                onFailure = { error ->
                    // Report what actually went wrong. Guessing "too new to have history" was
                    // actively misleading — it sent me looking at listing dates when the real
                    // cause was a network or provider failure.
                    android.util.Log.e(LOG_TAG, "chart failed for $symbol", error)
                    ChartState.Failed(
                        when {
                            error is java.net.UnknownHostException ->
                                "No internet connection."

                            error.message?.contains("HTTP 404") == true ->
                                "No data found for $symbol. Check the ticker is correct."

                            error.message?.contains("No close series") == true ->
                                "The data provider returned an unexpected format for $symbol."

                            error.message?.contains("Not enough data") == true ->
                                "Not enough history to chart $symbol."

                            else ->
                                "Couldn't load the chart for $symbol " +
                                        "(${error.message ?: error::class.java.simpleName})."
                        }
                    )
                }
            )
        }
    }

    private companion object {
        const val LOG_TAG = "MarketChart"
    }

    /** Symbol the in-flight request belongs to, so late responses can be discarded. */
    @Volatile
    private var loadedFor: String? = null
}
