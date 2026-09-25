package it.lagioiaproductions.shopeasily.data.repository

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

class VehicleEfficiencyRepository {
    suspend fun makes(year: Int): List<String> = (
        europeanVehicles().filter { it.availableIn(year) }.map(EuropeanVehicle::make) +
            menu("menu/make?year=$year").map(VehicleOption::label)
        ).distinct().sorted()

    suspend fun models(year: Int, make: String): List<String> =
        (
            europeanVehicles().filter { it.availableIn(year) && it.make.equals(make, true) }.map(EuropeanVehicle::model) +
                menu("menu/model?year=$year&make=${encode(make)}").map(VehicleOption::label)
            ).distinct().sorted()

    suspend fun options(year: Int, make: String, model: String): List<VehicleOption> =
        (
            europeanVehicles().filter {
                it.availableIn(year) && it.make.equals(make, true) && it.model.equals(model, true)
            }.map { VehicleOption("eu:${it.slug}", "${it.model} · ${it.generation}") } +
                menu("menu/options?year=$year&make=${encode(make)}&model=${encode(model)}")
            ).distinctBy(VehicleOption::id)

    suspend fun efficiency(option: VehicleOption): VehicleEfficiency? = withContext(Dispatchers.IO) {
        if (option.id.startsWith("eu:")) {
            val vehicle = europeanVehicles().firstOrNull { it.slug == option.id.removePrefix("eu:") }
                ?: return@withContext null
            val consumption = vehicle.litersPer100Km ?: vehicle.kwhPer100Km ?: return@withContext null
            return@withContext VehicleEfficiency(consumption, option.label)
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
        val complete = (parsed + BUILTIN_EUROPEAN_FALLBACK).distinctBy(EuropeanVehicle::slug)
        europeanCache = complete
        return complete
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

    private companion object {
        const val EUROPEAN_DATA_URL = "https://autoseeker.eu/data/models.json"
        @Volatile var europeanCache: List<EuropeanVehicle>? = null
        val BUILTIN_EUROPEAN_FALLBACK = listOf(
            EuropeanVehicle("renault-modus-12-16v-2011", "Renault", "Modus", "1.2 16V 75 CV · benzina", 2008, 2012, 5.9, null),
            EuropeanVehicle("renault-modus-12-tce-2011", "Renault", "Modus", "1.2 TCe 100 CV · benzina", 2008, 2012, 5.9, null),
            EuropeanVehicle("renault-modus-15-dci-70-2011", "Renault", "Modus", "1.5 dCi 70 CV · diesel", 2008, 2012, 4.3, null),
            EuropeanVehicle("renault-modus-15-dci-90-2011", "Renault", "Modus", "1.5 dCi 90 CV eco² · diesel", 2010, 2012, 4.1, null),
            EuropeanVehicle("renault-grand-modus-15-dci-90-2011", "Renault", "Grand Modus", "1.5 dCi 90 CV eco² · diesel", 2010, 2012, 4.1, null),
            EuropeanVehicle("renault-clio-v", "Renault", "Clio", "V TCe 90", 2019, null, 5.2, null),
            EuropeanVehicle("renault-captur-ii", "Renault", "Captur", "II E-Tech full hybrid", 2019, null, 4.7, null),
            EuropeanVehicle("renault-austral", "Renault", "Austral", "E-Tech full hybrid 200", 2022, null, 4.7, null),
            EuropeanVehicle("renault-arkana", "Renault", "Arkana", "E-Tech full hybrid", 2021, null, 4.8, null),
            EuropeanVehicle("renault-megane-e-tech", "Renault", "Megane E-Tech", "EV60", 2022, null, null, 16.1),
            EuropeanVehicle("renault-5-e-tech", "Renault", "5 E-Tech", "52 kWh", 2024, null, null, 14.9),
            EuropeanVehicle("renault-4-e-tech", "Renault", "4 E-Tech", "52 kWh", 2025, null, null, 15.1),
            EuropeanVehicle("renault-scenic-e-tech", "Renault", "Scenic E-Tech", "87 kWh", 2024, null, null, 16.8),
            EuropeanVehicle("renault-espace-vi", "Renault", "Espace", "VI E-Tech full hybrid", 2023, null, 4.8, null),
        )
    }
}
