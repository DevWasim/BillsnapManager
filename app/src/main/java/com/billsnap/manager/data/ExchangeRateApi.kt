package com.billsnap.manager.data

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Lightweight external public API to fetch current foreign exchange rates.
 * Used exclusively for DISPLAY mapping via CurrencyManager, not persistent storage logic.
 * Default base is PKR for BillSnap Manager.
 * 
 * Target: https://open.er-api.com/v6/latest/PKR
 */
interface ExchangeRateApi {

    @GET("latest/{baseCode}")
    suspend fun getLatestRates(
        @Path("baseCode") baseCode: String = "PKR"
    ): Response<ExchangeRateResponse>
}
