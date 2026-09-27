package it.lagioiaproductions.shopeasily.data.repository.parsers

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.FileOutputStream

data class OcrFlyerOffer(val productName: String, val price: Double, val imagePath: String)

object OcrFlyerReader {
    fun readOffers(document: PDDocument, outputDirectory: File, key: String, maximumPages: Int = 6): List<OcrFlyerOffer> {
        outputDirectory.mkdirs()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val renderer = PDFRenderer(document)
            buildList {
                repeat(minOf(document.numberOfPages, maximumPages)) { page ->
                    // RGB_565 at 120 dpi: ~2 MB per A4 page instead of ~8 MB (ARGB, 144 dpi).
                    val bitmap = try {
                        renderer.renderImageWithDPI(page, RENDER_DPI, ImageType.RGB)
                    } catch (_: OutOfMemoryError) {
                        return@repeat
                    } catch (_: Exception) {
                        return@repeat
                    }
                    try {
                        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
                        result.textBlocks.forEachIndexed { blockIndex, block ->
                            // Pair each price with its immediately preceding text
                            // line. Using the whole OCR block mixed columns and
                            // produced one fake product with the whole flyer image.
                            block.lines.forEachIndexed { lineIndex, line ->
                                val priceBounds = line.boundingBox ?: return@forEachIndexed
                                val previous = block.lines.getOrNull(lineIndex - 1)
                                val parsed = OfferTextParser.parse(
                                    listOfNotNull(previous?.text, line.text),
                                ).singleOrNull() ?: return@forEachIndexed
                                val bounds = previous?.boundingBox?.let { union(it, priceBounds) } ?: priceBounds
                                val crop = cropProductArea(bitmap, bounds) ?: return@forEachIndexed
                                val file = File(outputDirectory, "${key.hashCode()}-$page-$blockIndex-$lineIndex.jpg")
                                FileOutputStream(file).use { crop.compress(Bitmap.CompressFormat.JPEG, 86, it) }
                                crop.recycle()
                                add(OcrFlyerOffer(parsed.productName, parsed.price, file.absolutePath))
                            }
                        }
                    } catch (_: OutOfMemoryError) {
                        // Skip this page; the rest of the flyer is still useful.
                    } catch (_: Exception) {
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            recognizer.close()
        }
    }

    private const val RENDER_DPI = 120f

    fun read(document: PDDocument, maximumPages: Int = 8): List<String> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val renderer = PDFRenderer(document)
            buildList {
                repeat(minOf(document.numberOfPages, maximumPages)) { page ->
                    val bitmap = renderer.renderImageWithDPI(page, RENDER_DPI, ImageType.RGB)
                    try {
                        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                        addAll(result.text.lineSequence().map(String::trim).filter(String::isNotBlank))
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            recognizer.close()
        }
    }

    private fun cropProductArea(bitmap: Bitmap, textBounds: Rect): Bitmap? {
        val horizontalPadding = textBounds.width().coerceAtLeast(140)
        val upperPadding = (textBounds.height() * 3).coerceAtLeast(220)
        val lowerPadding = textBounds.height().coerceAtLeast(60)
        val left = (textBounds.left - horizontalPadding / 2).coerceAtLeast(0)
        val top = (textBounds.top - upperPadding).coerceAtLeast(0)
        val right = (textBounds.right + horizontalPadding / 2).coerceAtMost(bitmap.width)
        val bottom = (textBounds.bottom + lowerPadding).coerceAtMost(bitmap.height)
        if (right - left < 80 || bottom - top < 80) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun union(first: Rect, second: Rect) = Rect(
        minOf(first.left, second.left),
        minOf(first.top, second.top),
        maxOf(first.right, second.right),
        maxOf(first.bottom, second.bottom),
    )
}
