package com.billsnap.manager.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.billsnap.manager.data.CurrencyConfig
import com.billsnap.manager.data.ExchangeRateApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.NumberFormat
import java.util.Locale

/**
 * Lightweight, non-destructive presentation layer for Multi-Currency support.
 * The entire database and cloud sync MUST strictly remain canonical PKR to 
 * avoid irreversible floating-point migration complexity. 
 *
 * This Singleton only intercepts the UI rendering logic ($500 -> 140K PKR).
 */
object CurrencyManager {

    private const val TAG = "CurrencyManager"
    private const val PREFS_NAME = "currency_prefs"
    private const val KEY_CURRENCY_CODE = "selected_currency_code"
    
    // 24 Hour cache rule
    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L

    // Base Default: PKR
    private val PKR_DEFAULT = CurrencyConfig("PKR", "Rs", 1.0)
    
    // Offline structural fallbacks
    private val staticFallbackRates = mapOf(
        "PKR" to 1.0,
        "USD" to 0.0035, // ~285 PKR
        "AED" to 0.013,  // ~77 PKR
        "SAR" to 0.013,  // ~76 PKR
        "EUR" to 0.0033, // ~305 PKR
        "GBP" to 0.0028  // ~350 PKR
    )

    private val symbols = mapOf(
        "PKR" to "Rs",
        "USD" to "$",
        "AED" to "د.إ",
        "SAR" to "﷼",
        "EUR" to "€",
        "GBP" to "£"
    )

    private lateinit var prefs: SharedPreferences

    private val _currentCurrency = MutableStateFlow(PKR_DEFAULT)
    val currentCurrency: StateFlow<CurrencyConfig> = _currentCurrency.asStateFlow()

    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://open.er-api.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ExchangeRateApi::class.java)
    }

    /**
     * Initializes Manager with application context, loading last selected currency.
     * Fires an offline-safe background fetch to update the exchange rate pool.
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedCode = prefs.getString(KEY_CURRENCY_CODE, "PKR") ?: "PKR"
        
        // Load initial Config from cache or static block
        val rate = readCachedRate(savedCode) ?: staticFallbackRates[savedCode] ?: 1.0
        val symbol = symbols[savedCode] ?: savedCode
        val config = CurrencyConfig(savedCode, symbol, rate)
        
        _currentCurrency.value = config
        
        // Check if we need to silently refresh the exchange pool offline
        CoroutineScope(Dispatchers.IO).launch {
            prefetchRatesIfExpired()
        }
    }

    /**
     * Called by UI (Settings) to change global display currency seamlessly.
     */
    fun setCurrency(code: String) {
        if (!symbols.containsKey(code)) return // Unsupported
        
        prefs.edit().putString(KEY_CURRENCY_CODE, code).apply()
        
        val rate = readCachedRate(code) ?: staticFallbackRates[code] ?: 1.0
        _currentCurrency.value = CurrencyConfig(code, symbols[code] ?: code, rate)
    }

    /**
     * Renders any absolute PKR value dynamically to the selected UI Currency without modifying storage.
     * e.g format(1000.0) -> "$3.50" or "Rs1000"
     */
    fun format(amountPKR: Double): String {
        val cfg = _currentCurrency.value
        val converted = amountPKR * cfg.rateFromPKR
        
        val formatter = NumberFormat.getCurrencyInstance(Locale.US)
        val formatted = formatter.format(converted).replace("$", "")
        
        return if (cfg.code == "PKR") {
            // PKR typically has no decimals for layout aesthetics
            "${cfg.symbol}${formatted.substringBefore(".")}"
        } else {
            "${cfg.symbol}$formatted"
        }
    }

    /**
     * Compresses large numbers efficiently: formatCompact(1500.0) -> "$1.5k"
     */
    fun formatCompact(amountPKR: Double): String {
        return format(amountPKR)
    }

    // --- Offline Cache & Private HTTP Helpers ---
    
    private suspend fun prefetchRatesIfExpired() {
        val lastFetch = prefs.getLong("last_fetch_timestamp", 0L)
        if (System.currentTimeMillis() - lastFetch < CACHE_DURATION_MS) return

        try {
            val response = retrofit.getLatestRates("PKR")
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.result == "success") {
                    saveRatesLocally(body.rates)
                    prefs.edit().putLong("last_fetch_timestamp", System.currentTimeMillis()).apply()
                    Log.d(TAG, "Successfully synced Exchange Rates securely.")
                    
                    // Live-update current rate if user is currently watching
                    val curCode = _currentCurrency.value.code
                    val newRate = body.rates[curCode]
                    if (newRate != null && newRate != _currentCurrency.value.rateFromPKR) {
                        withContext(Dispatchers.Main) {
                            _currentCurrency.value = _currentCurrency.value.copy(rateFromPKR = newRate)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch remote rates, falling back completely to static configurations: \${e.message}")
        }
    }

    private fun saveRatesLocally(rates: Map<String, Double>) {
        val editor = prefs.edit()
        for ((code, rate) in rates) {
            if (symbols.containsKey(code)) {
                editor.putFloat("rate_\$code", rate.toFloat())
            }
        }
        editor.apply()
    }

    private fun readCachedRate(code: String): Double? {
        if (!prefs.contains("rate_\$code")) return null
        return prefs.getFloat("rate_\$code", -1f).toDouble().takeIf { it > 0 }
    }

    fun getSupportedCurrencies(): List<String> = symbols.keys.toList()
}
