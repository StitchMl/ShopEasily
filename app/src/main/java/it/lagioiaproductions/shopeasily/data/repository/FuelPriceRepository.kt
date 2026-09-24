package it.lagioiaproductions.shopeasily.data.repository

import it.lagioiaproductions.shopeasily.data.preferences.FuelType
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FuelPriceRepository {
    suspend fun nationalMedian(fuel: FuelType): Double? = withContext(Dispatchers.IO) {
        if (fuel == FuelType.NONE) return@withContext 0.0
        if (fuel == FuelType.ELECTRIC) return@withContext ELECTRIC_REFERENCE_EUR_KWH
        runCatching {
            val connection = URL(MIMIT_DAILY_PRICES).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            try {
                val requestedName = when (fuel) {
                    FuelType.GASOLINE -> "Benzina"
                    FuelType.DIESEL -> "Gasolio"
                    FuelType.LPG -> "GPL"
                    FuelType.NONE, FuelType.ELECTRIC -> return@runCatching null
                }
                val prices = connection.inputStream.bufferedReader().useLines { lines ->
                    lines.drop(2).mapNotNull { row ->
                        val cells = row.split('|')
                        if (cells.size < 4 || cells[1] != requestedName) return@mapNotNull null
                        val isSelf = cells[3] == "1"
                        if (fuel != FuelType.LPG && !isSelf) return@mapNotNull null
                        cells[2].toDoubleOrNull()
                    }.sorted().toList()
                }
                prices.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private companion object {
        const val MIMIT_DAILY_PRICES =
            "https://www.mimit.gov.it/images/exportCSV/prezzo_alle_8.csv"
        const val USER_AGENT = "ShopEasily/0.1 (+https://github.com/StitchMl/ShopEasily)"
        const val ELECTRIC_REFERENCE_EUR_KWH = 0.30
    }
}
