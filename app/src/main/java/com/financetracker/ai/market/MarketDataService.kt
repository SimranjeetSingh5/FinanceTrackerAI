package com.financetracker.ai.market

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * A single quote for one instrument.
 *
 * [isDelayed] is surfaced to the user: this feed is not a licensed real-time stream, and
 * presenting it as one would be the more dangerous choice in a finance app.
 */
data class Quote(
    val symbol: String,
    val name: String?,
    val price: Double?,
    val change: Double?,
    val changePercent: Double?,
    val currency: String?,
    val isDelayed: Boolean,
    val asOf: String?
) {
    val isUp: Boolean get() = (change ?: 0.0) >= 0
    val hasData: Boolean get() = price != null
}

/**
 * A historical series for one instrument, used to draw the chart.
 *
 * [points] is ordered oldest-first with any missing days already dropped, so callers can draw
 * it directly without re-checking for gaps.
 */
data class PriceSeries(
    val symbol: String,
    val points: List<PricePoint>,
    val currency: String?
)

data class PricePoint(val epochSeconds: Long, val close: Double)

/**
 * Fetches market quotes over HTTPS.
 *
 * Dependency-free by design: one small JSON GET doesn't justify carrying Retrofit or OkHttp in
 * a finance app.
 *
 * ## Provider
 *
 * Yahoo Finance's public chart endpoint. It needs no API key, which matters because the
 * alternative is asking every user to sign up for a developer account before the app works.
 *
 * The first implementation used **Stooq**, which is no longer viable: it now sits behind a
 * JavaScript proof-of-work challenge, so a plain HTTP GET receives an HTML challenge page
 * rather than data. There is no way around that from an app without executing their JS.
 *
 * This endpoint is undocumented and not licensed for redistribution, so treat it as a
 * convenience rather than a foundation — a key-based provider (Finnhub, Alpha Vantage,
 * Twelve Data) is a drop-in replacement if you need production guarantees. Only [fetchJson]
 * and [fetchChart] would change; everything else in the app talks to [Quote] and [PriceSeries].
 */
object MarketDataService {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** One request per symbol, capped so a mistyped watchlist can't hammer the endpoint. */
    const val MAX_SYMBOLS = 10

    private const val BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/"

    private const val LOG_TAG = "MarketData"

    /**
     * Yahoo rejects requests without a browser-like User-Agent, so one is sent deliberately.
     * The trailing space stops the platform appending its own default token.
     */
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) FinanceTrackerAI/1.0 "

    /**
     * Fetches quotes for [symbols], returning one entry per input in the order requested.
     *
     * Never throws: a failure yields blank quotes so the caller can show a message and carry
     * on, because every other feature of the app works offline.
     */
    fun fetch(symbols: List<String>): List<Quote> {
        val wanted = symbols.map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SYMBOLS)
        if (wanted.isEmpty()) return emptyList()

        return wanted.map { symbol ->
            try {
                fetchJson(symbol).toQuote(symbol)
            } catch (e: Exception) {
                android.util.Log.e(LOG_TAG, "fetch failed for $symbol", e)
                blankQuote(symbol)
            }
        }
    }

    private fun blankQuote(symbol: String) = Quote(
        symbol = symbol,
        name = null,
        price = null,
        change = null,
        changePercent = null,
        currency = null,
        isDelayed = true,
        asOf = null
    )

    /**
     * Fetches a daily close series for [symbol] over the last [range].
     *
     * Yahoo returns nulls for days the market was shut, so they're dropped rather than
     * plotted as zero — a zero would draw a cliff to the axis on every weekend.
     */
    fun fetchChart(symbol: String, range: String = RANGE_1Y): Result<PriceSeries> = runCatching {
        val clean = symbol.trim().uppercase(Locale.US)
        if (clean.isEmpty()) throw IllegalArgumentException("No symbol given")

        android.util.Log.d(LOG_TAG, "fetchChart $clean range=$range")
        val result = chartRequest(clean, range)
        val timestamps = result.optJSONArray("timestamp")
            ?: throw IllegalStateException("No timestamps for $clean")
        // `indicators.quote` is an ARRAY of series objects, not a single object. Reading it
        // with optJSONObject returns null and silently fails the whole chart.
        val closes = result.optJSONObject("indicators")
            ?.optJSONArray("quote")
            ?.optJSONObject(0)
            ?.optJSONArray("close")
            ?: run {
                // Log the shape we actually got. Getting the nesting wrong once already cost
                // several rounds of guessing, because the error text alone looked like a
                // data problem rather than a parsing one.
                android.util.Log.e(
                    LOG_TAG,
                    "chart parse failed for $clean — result keys=${result.keys()}, " +
                            "indicators keys=" +
                            "${result.optJSONObject("indicators")?.keys()?.asSequence()?.toList()}, " +
                            "quote isArray=" +
                            "${result.optJSONObject("indicators")?.opt("quote") is org.json.JSONArray}"
                )
                throw IllegalStateException("No close series for $clean")
            }

        val points = buildList {
            for (i in 0 until minOf(timestamps.length(), closes.length())) {
                val close = if (closes.isNull(i)) Double.NaN else closes.optDouble(i)
                // A shut-market day is null, not zero.
                if (close.isNaN() || close == 0.0) continue
                add(PricePoint(timestamps.getLong(i), close))
            }
        }

        android.util.Log.d(LOG_TAG, "fetchChart $clean -> ${points.size} points")
        if (points.size < 2) throw IllegalStateException("Not enough data to chart $clean")

        PriceSeries(
            symbol = clean,
            points = points,
            currency = result.optJSONObject("meta")
                ?.optString("currency")
                ?.takeIf { it.isNotBlank() }
        )
    }

    /** Number of trailing days a [PriceSeries] should cover. */
    const val RANGE_1Y = "1y"

    private fun fetchJson(symbol: String): JSONObject = chartRequest(symbol, "1d")
        .optJSONObject("meta")
        ?: throw IllegalStateException("No metadata for $symbol")

    /** Issues the chart request and returns the first result object. */
    private fun chartRequest(symbol: String, range: String): JSONObject {
        val url = URL("$BASE_URL$symbol?interval=1d&range=$range")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code for $symbol")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)

            // The API reports errors in-band with a 200, so a null result is a real failure.
            val error = root.optJSONObject("chart")?.optJSONObject("error")
            if (error != null) {
                throw IllegalStateException(
                    "Provider error for $symbol: ${error.optString("description", error.toString())}"
                )
            }
            return root.optJSONObject("chart")?.optJSONArray("result")
                ?.optJSONObject(0)
                ?: throw IllegalStateException("Empty response for $symbol")
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toQuote(symbol: String): Quote {
        val price = optDoubleOrNull("regularMarketPrice")
        // Yahoo gives the previous close separately; fall back to yesterday's last close from
        // the series when the meta field is absent.
        val previousClose = optDoubleOrNull("chartPreviousClose")
            ?: optDoubleOrNull("previousClose")
        val change = if (price != null && previousClose != null) price - previousClose else null
        val pct = if (change != null && previousClose != null && previousClose != 0.0) {
            change * 100 / previousClose
        } else null

        return Quote(
            symbol = optString("symbol", symbol),
            name = optString("shortName").takeIf { it.isNotBlank() },
            price = price,
            change = change,
            changePercent = pct,
            currency = optString("currency").takeIf { it.isNotBlank() },
            isDelayed = true,
            asOf = optLong("regularMarketTime").takeIf { it > 0 }?.let { epochToTime(it) }
        )
    }

    /** org.json turns a missing value into NaN rather than omitting it, so check explicitly. */
    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        val value = optDouble(name)
        return if (value.isNaN()) null else value
    }

    private fun epochToTime(epochSeconds: Long): String {
        val format = java.text.SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        return format.format(java.util.Date(epochSeconds * 1000))
    }

    /**
     * Whether the device currently has a validated network. Used only to phrase the error well —
     * it is a capability check, not a reachability test, so it can report true on a network that
     * still can't reach the provider.
     */
    fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }
}
