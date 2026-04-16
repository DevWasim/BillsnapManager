package com.billsnap.manager.data

/**
 * Lightweight configuration holding the exchange rate metadata.
 * base currency is strictly PKR.
 */
data class CurrencyConfig(
    val code: String,
    val symbol: String,
    val rateFromPKR: Double,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Data mapped from the https://open.er-api.com/v6/latest/PKR response.
 */
data class ExchangeRateResponse(
    val result: String,
    val base_code: String,
    val time_last_update_unix: Long,
    val rates: Map<String, Double>
)
