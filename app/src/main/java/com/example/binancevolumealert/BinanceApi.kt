package com.example.binancevolumealert

import retrofit2.http.GET
import retrofit2.http.Query

interface BinanceApi {

    @GET("fapi/v1/exchangeInfo")
    suspend fun getExchangeInfo(): ExchangeInfoResponse

    // Returneaza array de lumanari: [openTime, open, high, low, close, volume, closeTime, ...]
    @GET("fapi/v1/klines")
    suspend fun getKlines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String = "1h",
        @Query("limit") limit: Int = 3
    ): List<List<Any>>
    @GET("fapi/v1/ticker/24hr")
    suspend fun getAllTickers(): List<TickerData>
}