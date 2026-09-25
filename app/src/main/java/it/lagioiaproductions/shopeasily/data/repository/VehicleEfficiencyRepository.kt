package it.lagioiaproductions.shopeasily.data.repository

import android.content.Context
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.json.JSONObject

data class VehicleOption(val id: String, val label: String)
data class VehicleEfficiency(val litersPer100Km: Double, val label: String)

class VehicleEfficiencyRepository(private val context: Context) {
    suspend fun makes(year: Int): List<String> = withContext(Dispatchers.IO) {
        (
            europeanVehicles().filter { it.availableIn(year) }.map(EuropeanVehicle::make) +
                historicVehicles(year).map(HistoricVehicle::make) +
                menu("menu/make?year=$year").map(VehicleOption::label)
            ).distinct().sorted()
    }

    suspend fun models(year: Int, make: String): List<String> = withContext(Dispatchers.IO) {
        (
            europeanVehicles().filter { it.availableIn(year) && it.make.equals(make, true) }.map(EuropeanVehicle::model) +
                historicVehicles(year).filter { it.make.equals(make, true) }.map(HistoricVehicle::model) +
                menu("menu/model?year=$year&make=${encode(make)}").map(VehicleOption::label)
            ).distinct().sorted()
    }

    suspend fun options(year: Int, make: String, model: String): List<VehicleOption> = withContext(Dispatchers.IO) {
        (
            europeanVehicles().filter {
                it.availableIn(year) && it.make.equals(make, true) && it.model.equals(model, true)
            }.map { VehicleOption("eu:${it.slug}", "${it.model} · ${it.generation}") } +
                historicVehicles(year).filter {
                    it.make.equals(make, true) && it.model.equals(model, true)
                }.map { vehicle ->
                    historicOptions[vehicle.id] = vehicle
                    VehicleOption("vca:${vehicle.id}", "${vehicle.description} · ${vehicle.fuelType}")
                } +
                menu("menu/options?year=$year&make=${encode(make)}&model=${encode(model)}")
            ).distinctBy(VehicleOption::id)
    }

    suspend fun efficiency(option: VehicleOption): VehicleEfficiency? = withContext(Dispatchers.IO) {
        if (option.id.startsWith("eu:")) {
            val vehicle = europeanVehicles().firstOrNull { it.slug == option.id.removePrefix("eu:") }
                ?: return@withContext null
            val consumption = vehicle.litersPer100Km ?: vehicle.kwhPer100Km ?: return@withContext null
            return@withContext VehicleEfficiency(consumption, option.label)
        }
        if (option.id.startsWith("vca:")) {
            val vehicle = historicOptions[option.id.removePrefix("vca:")] ?: return@withContext null
            return@withContext VehicleEfficiency(vehicle.combinedLitersPer100Km, option.label)
        }
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

    private fun europeanVehicles(): List<EuropeanVehicle> {
        europeanCache?.let { return it }
        val connection = URL(EUROPEAN_DATA_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "ShopEasily/0.4")
        val parsed = try {
            if (connection.responseCode !in 200..299) emptyList() else {
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val models = root.getJSONArray("models")
                buildList {
                    for (index in 0 until models.length()) {
                        val item = models.getJSONObject(index)
                        val specs = item.optJSONObject("specs") ?: JSONObject()
                        add(
                            EuropeanVehicle(
                                slug = item.getString("slug"),
                                make = item.getString("merk"),
                                model = item.getString("model"),
                                generation = item.optString("generatie").ifBlank { "Versione europea" },
                                fromYear = item.optInt("bouwjaarVan", 1984),
                                toYear = item.optInt("bouwjaarTot").takeIf { !item.isNull("bouwjaarTot") && it > 0 },
                                litersPer100Km = specs.optDouble("verbruik_wltp_l_100km").takeIf { !it.isNaN() && it > 0 },
                                kwhPer100Km = specs.optDouble("verbruik_wltp_kwh_100km").takeIf { !it.isNaN() && it > 0 },
                            ),
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
        europeanCache = parsed
        return parsed
    }

    private fun historicVehicles(year: Int): List<HistoricVehicle> = historicYearCache.getOrPut(year) {
        val csv = historicCsv() ?: return@getOrPut emptyList()
        csv.lineSequence().drop(1).mapIndexedNotNull { index, line ->
            val columns = parseCsvLine(line)
            if (columns.size < 14 || columns[1].toIntOrNull() != year) return@mapIndexedNotNull null
            val consumption = columns[13].toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
            HistoricVehicle(
                id = "$year-$index",
                make = columns[2].trim(),
                model = columns[3].trim(),
                description = columns[4].trim(),
                fuelType = columns[10].trim(),
                combinedLitersPer100Km = consumption,
            ).takeIf { it.make.isNotBlank() && it.model.isNotBlank() && it.description.isNotBlank() }
        }.toList()
    }

    private fun historicCsv(): String? {
        historicCsvCache?.let { return it }
        val cacheFile = context.cacheDir.resolve("vca-car-fuel-2000-2013.csv")
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < HISTORIC_CACHE_TTL) {
            return cacheFile.readText().also { historicCsvCache = it }
        }
        val connection = URL(HISTORIC_VCA_DATA_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("User-Agent", "ShopEasily/0.4")
        return try {
            if (connection.responseCode !in 200..299) cacheFile.takeIf { it.exists() }?.readText()
            else connection.inputStream.bufferedReader().use { it.readText() }.also {
                cacheFile.writeText(it)
                historicCsvCache = it
            }
        } catch (_: Exception) {
            cacheFile.takeIf { it.exists() }?.readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && line.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { result += field.toString(); field.clear() }
                else -> field.append(char)
            }
            index++
        }
        result += field.toString()
        return result
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class EuropeanVehicle(
        val slug: String,
        val make: String,
        val model: String,
        val generation: String,
        val fromYear: Int,
        val toYear: Int?,
        val litersPer100Km: Double?,
        val kwhPer100Km: Double?,
    ) {
        fun availableIn(year: Int) = year >= fromYear && (toYear == null || year <= toYear)
    }

    private data class HistoricVehicle(
        val id: String,
        val make: String,
        val model: String,
        val description: String,
        val fuelType: String,
        val combinedLitersPer100Km: Double,
    )

    private companion object {
        const val EUROPEAN_DATA_URL = "https://autoseeker.eu/data/models.json"
        const val HISTORIC_VCA_DATA_URL = "https://raw.githubusercontent.com/amercader/car-fuel-and-emissions/master/data.csv"
        const val HISTORIC_CACHE_TTL = 30L * 24 * 60 * 60 * 1_000
        @Volatile var europeanCache: List<EuropeanVehicle>? = null
        @Volatile var historicCsvCache: String? = null
        val historicYearCache = mutableMapOf<Int, List<HistoricVehicle>>()
        val historicOptions = mutableMapOf<String, HistoricVehicle>()
    }
}
