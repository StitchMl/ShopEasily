package it.lagioiaproductions.shopeasily.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import it.lagioiaproductions.shopeasily.data.repository.net.HttpFetcher
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Loads product images downsampled to the size they are shown at, with a
 * memory cache and at most 4 downloads in parallel.
 *
 * Product cards used to decode every remote image at full resolution (often
 * 1–4 Mpx, 4–16 MB each) and re-download it on every scroll: with a list of
 * hundreds of offers this exhausted the heap and the app closed.
 */
object BitmapLoader {
    private const val MAX_DOWNLOAD_BYTES = 3 * 1024 * 1024
    private val permits = Semaphore(4)
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(url: String, targetPx: Int): Bitmap? = cache.get("$targetPx|$url")

    suspend fun load(url: String, targetPx: Int): Bitmap? {
        val key = "$targetPx|$url"
        cache.get(key)?.let { return it }
        if (url in failed) return null
        return withContext(Dispatchers.IO) {
            permits.withPermit {
                cache.get(key) ?: runCatching { decode(url, targetPx) }.getOrNull()
                    ?.also { cache.put(key, it) }
                    ?: null.also { failed += url }
            }
        }
    }

    private fun decode(url: String, targetPx: Int): Bitmap? {
        val bytes = if (url.startsWith("http://") || url.startsWith("https://")) {
            download(url) ?: return null
        } else {
            val file = File(url.removePrefix("file://"))
            if (!file.exists() || file.length() > MAX_DOWNLOAD_BYTES * 4) return null
            file.readBytes()
        }
        return decodeSampled(bytes, targetPx)
    }

    /** [keepTransparency] must be true for logos: RGB_565 turns transparent backgrounds black. */
    fun decodeSampled(bytes: ByteArray, targetPx: Int, keepTransparency: Boolean = true): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (keepTransparency) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun download(url: String): ByteArray? {
        val secure = HttpFetcher.secureUrl(url) ?: return null
        val connection = URL(secure).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) ShopEasily/0.5 (+https://github.com/StitchMl/ShopEasily)")
            if (connection.responseCode !in 200..299) return null
            if ((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) > MAX_DOWNLOAD_BYTES) return null
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
