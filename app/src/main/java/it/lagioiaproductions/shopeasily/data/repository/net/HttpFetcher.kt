package it.lagioiaproductions.shopeasily.data.repository.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Network access used by the catalogue collector.
 *
 * - never throws: offline, DNS, TLS and timeout errors become `null`
 *   (uncaught IOExceptions from here used to crash the Map screen);
 * - upgrades `http://` to `https://`, because cleartext is disabled by the
 *   network security config and many small shops still publish http links in
 *   OpenStreetMap;
 * - caches robots.txt per host instead of downloading it before every page;
 * - bounds the size of every download to protect the heap.
 */
class HttpFetcher(private val userAgent: String) {
    private val robotsCache = ConcurrentHashMap<String, RobotsRules>()
    private val publicHostCache = ConcurrentHashMap<String, Boolean>()
    private val sitemapCache = ConcurrentHashMap<String, List<String>>()

    /** Sitemaps declared in robots.txt plus the conventional locations. */
    fun sitemapsFor(website: String): List<String> {
        val uri = runCatching { URI(secureUrl(website) ?: return emptyList()) }.getOrNull() ?: return emptyList()
        val authority = uri.authority ?: return emptyList()
        robotsAllows("https://$authority/")
        val declared = sitemapCache[authority.lowercase()].orEmpty()
        return (declared + listOf(
            "https://$authority/sitemap_index.xml",
            "https://$authority/sitemap.xml",
            "https://$authority/product-sitemap.xml",
        )).distinct()
    }

    fun get(url: String, maxBytes: Int = MAX_HTML_BYTES, accept: String? = null): ByteArray? =
        execute(url, "GET", null, maxBytes, accept)

    fun post(url: String, body: ByteArray, maxBytes: Int = MAX_HTML_BYTES): ByteArray? =
        execute(url, "POST", body, maxBytes, null)

    /** JSON POST with extra headers (e.g. official APIs that take a key in a header). */
    fun postJson(url: String, json: String, headers: Map<String, String>, maxBytes: Int = MAX_JSON_BYTES): String? =
        execute(url, "POST", json.toByteArray(), maxBytes, "application/json", "application/json", headers)
            ?.toString(Charsets.UTF_8)

    fun getText(url: String, maxBytes: Int = MAX_HTML_BYTES, accept: String? = null): String? =
        get(url, maxBytes, accept)?.toString(Charsets.UTF_8)

    /** Fetches only if robots.txt allows it and the host is public. */
    fun politeGet(url: String, maxBytes: Int = MAX_HTML_BYTES, accept: String? = null): ByteArray? {
        val secure = secureUrl(url) ?: return null
        if (!isPublicUrl(secure) || !robotsAllows(secure)) return null
        return get(secure, maxBytes, accept)
    }

    fun robotsAllows(url: String): Boolean {
        val uri = runCatching { URI(secureUrl(url) ?: return false) }.getOrNull() ?: return false
        val authority = uri.authority ?: return false
        val rules = robotsCache.getOrPut(authority.lowercase()) {
            val text = getText("${uri.scheme}://$authority/robots.txt", maxBytes = 512 * 1024)
            sitemapCache[authority.lowercase()] = text.orEmpty().lineSequence()
                .filter { it.trim().startsWith("sitemap:", ignoreCase = true) }
                .map { it.substringAfter(':').trim() }
                .filter { it.startsWith("http") }
                .toList()
            if (text == null) RobotsRules.ALLOW_ALL else RobotsRules.parse(text, ROBOTS_AGENT)
        }
        val path = (uri.rawPath ?: "/").ifBlank { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        return rules.allows(path)
    }

    fun isPublicUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https")) return false
        val host = uri.host ?: return false
        return publicHostCache.getOrPut(host.lowercase()) {
            runCatching {
                InetAddress.getAllByName(host).all { address ->
                    !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress && !address.isSiteLocalAddress
                }
            }.getOrDefault(false)
        }
    }

    private fun execute(
        url: String,
        method: String,
        body: ByteArray?,
        maxBytes: Int,
        accept: String?,
        contentType: String = "application/x-www-form-urlencoded",
        headers: Map<String, String> = emptyMap(),
    ): ByteArray? {
        val target = secureUrl(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Language", "it-IT,it;q=0.9,en;q=0.5")
                if (accept != null) setRequestProperty("Accept", accept)
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType)
                    outputStream.use { it.write(body) }
                }
            }
            if (connection.responseCode !in 200..299) return null
            val declared = connection.contentLengthLong
            if (declared > maxBytes) return null
            connection.inputStream.use { it.readLimited(maxBytes) }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val MAX_HTML_BYTES = 6 * 1024 * 1024
        const val MAX_PDF_BYTES = 10 * 1024 * 1024
        const val MAX_JSON_BYTES = 4 * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val ROBOTS_AGENT = "shopeasily"

        fun secureUrl(value: String): String? {
            val trimmed = value.trim()
            return when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring(7)
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.contains("://") -> null
                trimmed.isBlank() -> null
                else -> "https://$trimmed"
            }
        }
    }
}
