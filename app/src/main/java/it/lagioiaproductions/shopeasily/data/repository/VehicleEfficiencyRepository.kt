package it.lagioiaproductions.shopeasily.data.repository

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

data class VehicleOption(val id: String, val label: String)
data class VehicleEfficiency(val litersPer100Km: Double, val label: String)

class VehicleEfficiencyRepository {
    suspend fun makes(year: Int): List<String> = menu("menu/make?year=$year").map(VehicleOption::label)

    suspend fun models(year: Int, make: String): List<String> =
        menu("menu/model?year=$year&make=${encode(make)}").map(VehicleOption::label)

    suspend fun options(year: Int, make: String, model: String): List<VehicleOption> =
        menu("menu/options?year=$year&make=${encode(make)}&model=${encode(model)}")

    suspend fun efficiency(option: VehicleOption): VehicleEfficiency? = withContext(Dispatchers.IO) {
        val xml = get(option.id) ?: return@withContext null
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        val mpg = document.selectFirst("comb08")?.text()?.toDoubleOrNull()?.takeIf { it > 0 } ?: return@withContext null
        VehicleEfficiency(litersPer100Km = 235.214583 / mpg, label = option.label)
    }

    private suspend fun menu(path: String): List<VehicleOption> = withContext(Dispatchers.IO) {
        val xml = get(path) ?: return@withContext emptyList()
        Jsoup.parse(xml, "", Parser.xmlParser()).select("menuItem").mapNotNull { item ->
            val value = item.selectFirst("value")?.text()?.trim().orEmpty()
            val label = item.selectFirst("text")?.text()?.trim().orEmpty()
            VehicleOption(value, label).takeIf { value.isNotBlank() && label.isNotBlank() }
        }.distinctBy(VehicleOption::id)
    }

    private fun get(path: String): String? {
        val connection = URL("https://www.fueleconomy.gov/ws/rest/vehicle/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Accept", "application/xml")
        connection.setRequestProperty("User-Agent", "ShopEasily/0.4")
        return try {
            if (connection.responseCode !in 200..299) null else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
