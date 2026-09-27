package it.lagioiaproductions.shopeasily.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import it.lagioiaproductions.shopeasily.data.repository.BrandDirectory
import it.lagioiaproductions.shopeasily.data.repository.StoreDeduplicator
import it.lagioiaproductions.shopeasily.data.repository.net.HttpFetcher
import it.lagioiaproductions.shopeasily.data.repository.net.NonShopSites
import org.jsoup.Jsoup

object StoreLogoResolver {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** Blocking: call from Dispatchers.IO. Concurrent calls for the same store download once. */
    fun load(storeName: String, website: String?): Bitmap {
        val key = "${storeName.lowercase(Locale.ROOT)}|${website.orEmpty()}"
        cache[key]?.let { return it }
        val lock = locks.getOrPut(key) { Any() }
        return synchronized(lock) {
            cache[key] ?: (
                runCatching {
                    // Raster images first: SVG logos styled with CSS often render as a black shape.
                    logoCandidates(storeName, website)
                        .sortedBy { url -> if (url.substringBefore('?').endsWith(".svg", true)) 1 else 0 }
                        .firstNotNullOfOrNull { url -> downloadBitmap(url)?.takeIf(::isMeaningful) }
                }.getOrNull()
                    ?.let(::normalize)
                    ?: initialMarker(storeName)
                ).also { cache[key] = it }
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
        // Brand logo and official site are discovered automatically (Wikidata logo, official
        // site found by web search); already-known results come from the on-device cache.
        // 1. Open Food Facts "brand-images": open collection of 8.000+ store logos used by
        //    Open Prices, named by brand slug (crai.png, md.png, carrefour-market.png…).
        brandImageSlugs(storeName).forEach { slug -> add("$BRAND_IMAGES/$slug.png") }
        val knownLogo = BrandDirectory.peekLogo(storeName)
        val brand = if (knownLogo == null) BrandDirectory.resolveForStore(storeName) else null
        (knownLogo ?: brand?.logoUrl)?.let(::add)
        // Offer records often point to an aggregator or flyer URL: prefer the brand's own site.
        val ownSite = website?.takeUnless { url -> AGGREGATORS.any { url.contains(it, ignoreCase = true) } || NonShopSites.isNonShop(url) }
        val homepage = (BrandDirectory.peekWebsite(storeName) ?: brand?.website) ?: ownSite?.let(::normalizeWebsite)
        if (homepage != null) {
            addAll(discoverIcons(homepage))
            runCatching {
                val uri = URI(homepage)
                add("${uri.scheme}://${uri.authority}/favicon.ico")
                // Public favicon services: work even when the site blocks apps or hides its icon.
                val host = uri.host
                add("https://www.google.com/s2/favicons?domain=$host&sz=128")
                add("https://icons.duckduckgo.com/ip3/$host.ico")
            }
        }
    }.distinct()

    private fun discoverIcons(homepage: String): List<String> = runCatching {
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
        val document = Jsoup.parse(html, homepage)
        buildList {
            // Prefer touch icons: they are normally a clean, high-resolution PNG.
            document.select("link[rel~=apple-touch-icon], link[rel~=apple-touch-icon-precomposed]")
                .mapNotNullTo(this) { it.absUrl("href").takeIf(String::isNotBlank) }
            document.select("link[rel~=icon]")
                .sortedByDescending { iconScore(it.attr("href"), it.attr("type"), it.attr("sizes")) }
                .mapNotNullTo(this) { it.absUrl("href").takeIf(String::isNotBlank) }
            document.select("meta[property=og:logo], meta[name=logo], meta[itemprop=logo]")
                .mapNotNullTo(this) { it.absUrl("content").takeIf(String::isNotBlank) }
            document.select("img[src]")
                .filter { element ->
                    listOf(element.id(), element.className(), element.attr("alt"), element.attr("src"))
                        .any { it.contains("logo", true) }
                }
                .mapNotNullTo(this) { it.absUrl("src").takeIf(String::isNotBlank) }
        }.distinct()
    }.getOrDefault(emptyList())

    private fun iconScore(href: String, type: String, sizes: String): Int =
        (if (type.contains("png", true) || href.substringBefore('?').endsWith(".png", true)) 100 else 0) +
            sizes.substringBefore('x').toIntOrNull()?.coerceAtMost(96).orZero() -
            if (type.contains("svg", true) || href.substringBefore('?').endsWith(".svg", true)) 200 else 0

    private fun Int?.orZero(): Int = this ?: 0

    private fun downloadBitmap(value: String): Bitmap? = runCatching {
        val connection = open(value)
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            if (connection.contentLengthLong > MAX_LOGO_BYTES) return@runCatching null
            val type = connection.contentType.orEmpty()
            val bytes = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_LOGO_BYTES) return@runCatching null
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
            if (type.contains("svg", true) || value.substringBefore('?').endsWith(".svg", true)) {
                decodeSvg(bytes)
            } else {
                BitmapLoader.decodeSampled(bytes, SIZE * 2, keepTransparency = true)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun decodeSvg(bytes: ByteArray): Bitmap? = runCatching {
        val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
        val bitmap = Bitmap.createBitmap(SVG_RENDER_SIZE, SVG_RENDER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        svg.setDocumentWidth(SVG_RENDER_SIZE.toFloat())
        svg.setDocumentHeight(SVG_RENDER_SIZE.toFloat())
        svg.renderToCanvas(canvas)
        bitmap
    }.getOrNull()

    private fun open(value: String) = (URL(HttpFetcher.secureUrl(value) ?: value).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 8_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) ShopEasily/0.5 (+https://github.com/StitchMl/ShopEasily)")
    }

    /**
     * Rejects images that would show as a blank or a black blob: mostly transparent,
     * one flat colour, or almost entirely black (a typical failed SVG/ICO render).
     */
    internal fun isMeaningful(bitmap: Bitmap): Boolean {
        if (bitmap.width < 8 || bitmap.height < 8) return false
        val steps = 24
        var opaque = 0
        var dark = 0
        val colours = HashSet<Int>()
        for (y in 0 until steps) for (x in 0 until steps) {
            val pixel = bitmap.getPixel(x * (bitmap.width - 1) / (steps - 1), y * (bitmap.height - 1) / (steps - 1))
            if (Color.alpha(pixel) < 40) continue
            opaque++
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            if (r + g + b < 120) dark++
            colours += ((r / 48) shl 8) or ((g / 48) shl 4) or (b / 48)
        }
        val total = steps * steps
        if (opaque < total / 25) return false
        if (colours.size < 2) return false
        if (dark > opaque * 9 / 10 && colours.size < 4) return false
        return true
    }

    private fun normalize(source: Bitmap): Bitmap {
        if (source.width <= 0 || source.height <= 0) return initialMarker("?")
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
    private const val BRAND_IMAGES = "https://raw.githubusercontent.com/openfoodfacts/brand-images/main/xx/stores"

    internal fun brandImageSlugs(storeName: String): List<String> {
        if (StoreDeduplicator.isGenericName(storeName)) return emptyList()
        fun slug(value: String) = StoreDeduplicator.canonicalName(value).replace(' ', '-')
        return listOf(
            slug(storeName),
            slug(StoreDeduplicator.brandDisplayName(storeName)),
            StoreDeduplicator.meaningfulTokens(storeName).joinToString("-"),
        ).filter { it.length >= 2 }.distinct()
    }
    private val AGGREGATORS = listOf("doveconviene", "volantinofacile", "promoqui", "kimbino", "tiendeo", "openfoodfacts", "shopfully")
    private const val MAX_LOGO_BYTES = 1024 * 1024
    private const val SVG_RENDER_SIZE = 256
    private const val MAX_HTML_CHARS = 256_000
}
