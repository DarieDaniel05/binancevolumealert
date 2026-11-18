package com.example.binancevolumealert

data class ExchangeInfoResponse(
    val symbols: List<SymbolInfo>
)

data class SymbolInfo(
    val symbol: String,
    val status: String,
    val quoteAsset: String,
    val contractType: String?
)
data class TickerData(
    val symbol: String = "",
    val priceChangePercent: String = "0",
    val quoteVolume: String = "0"
)