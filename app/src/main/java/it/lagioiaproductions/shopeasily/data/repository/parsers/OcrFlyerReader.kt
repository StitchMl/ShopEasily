package it.lagioiaproductions.shopeasily.data.repository.parsers

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import java.io.FileOutputStream

data class OcrFlyerOffer(val productName: String, val price: Double, val imagePath: String)

object OcrFlyerReader {
    fun readOffers(document: PDDocument, outputDirectory: File, key: String, maximumPages: Int = 8): List<OcrFlyerOffer> {
        outputDirectory.mkdirs()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val renderer = PDFRenderer(document)
            buildList {
                repeat(minOf(document.numberOfPages, maximumPages)) { page ->
                    val bitmap = renderer.renderImageWithDPI(page, 144f)
                    try {
                        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
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
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            recognizer.close()
        }
    }

    fun read(document: PDDocument, maximumPages: Int = 8): List<String> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val renderer = PDFRenderer(document)
            buildList {
                repeat(minOf(document.numberOfPages, maximumPages)) { page ->
                    val bitmap = renderer.renderImageWithDPI(page, 144f)
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
