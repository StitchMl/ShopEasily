package it.lagioiaproductions.shopeasily.data.repository.parsers

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer

object OcrFlyerReader {
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
}
