package it.lagioiaproductions.shopeasily.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object StoreLogoResolver {
    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun load(storeName: String, website: String?): Bitmap {
        val key = "${storeName.lowercase(Locale.ROOT)}|${website.orEmpty()}"
        return cache.getOrPut(key) {
            logoCandidates(storeName, website).firstNotNullOfOrNull(::downloadBitmap)
                ?.let(::normalize)
                ?: initialMarker(storeName)
        }
    }

    fun initialMarker(name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(
            SIZE / 2f,
            SIZE / 2f,
            SIZE / 2f - 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 107, 87) },
        )
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(name.trim().take(1).uppercase(), SIZE / 2f, 44f, text)
        return bitmap
    }

    private fun logoCandidates(storeName: String, website: String?): List<String> = buildList {
        OFFICIAL_LOGOS.entries.firstOrNull { storeName.contains(it.key, true) }?.value?.let(::add)
        val homepage = website?.let(::normalizeWebsite)
            ?: OFFICIAL_DOMAINS.entries.firstOrNull { storeName.contains(it.key, true) }
                ?.value?.let { "https://$it" }
        if (homepage != null) {
            discoverIcon(homepage)?.let(::add)
            runCatching {
                val uri = URI(homepage)
                "${uri.scheme}://${uri.authority}/favicon.ico"
            }.getOrNull()?.let(::add)
        }
    }.distinct()

    private fun discoverIcon(homepage: String): String? = runCatching {
        val connection = open(homepage)
        val html = try {
            connection.inputStream.bufferedReader().use { reader ->
                val text = CharArray(MAX_HTML_CHARS)
                val count = reader.read(text)
                if (count <= 0) "" else String(text, 0, count)
            }
        } finally {
            connection.disconnect()
        }
        val tag = LINK_TAG.findAll(html)
            .map(MatchResult::value)
            .firstOrNull { ICON_REL.containsMatchIn(it) }
            ?: return@runCatching null
        val href = HREF.find(tag)?.groupValues?.get(1) ?: return@runCatching null
        URI(homepage).resolve(href).toString()
    }.getOrNull()

    private fun downloadBitmap(value: String): Bitmap? = runCatching {
        val connection = open(value)
        val type = connection.contentType.orEmpty()
        if (type.contains("svg", true)) return@runCatching null
        connection.inputStream.use(BitmapFactory::decodeStream).also { connection.disconnect() }
    }.getOrNull()

    private fun open(value: String) = (URL(value).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 8_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "ShopEasily/0.4")
    }

    private fun normalize(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val scale = minOf(52f / source.width, 52f / source.height)
        val width = source.width * scale
        val height = source.height * scale
        canvas.drawBitmap(
            source,
            null,
            RectF((SIZE - width) / 2, (SIZE - height) / 2, (SIZE + width) / 2, (SIZE + height) / 2),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return output
    }

    private fun normalizeWebsite(value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"

    private const val SIZE = 64
    private const val MAX_HTML_CHARS = 256_000
    private val LINK_TAG = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ICON_REL = Regex("""rel\s*=\s*["'][^"']*(?:icon|apple-touch-icon)[^"']*["']""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OFFICIAL_DOMAINS = mapOf(
        "Esselunga" to "www.esselunga.it",
        "NaturaSì" to "www.naturasi.it",
        "Lidl" to "www.lidl.it",
        "Coop" to "www.coop.it",
        "Conad" to "www.conad.it",
        "Carrefour" to "www.carrefour.it",
        "Eurospin" to "www.eurospin.it",
        "Cortilia" to "www.cortilia.it",
        "Eataly" to "www.eataly.net",
    )
    private val OFFICIAL_LOGOS = mapOf(
        "Eurospin" to "https://www.eurospin.it/wp-content/themes/eurospin/assets/images/obj/logo.png",
    )
}
