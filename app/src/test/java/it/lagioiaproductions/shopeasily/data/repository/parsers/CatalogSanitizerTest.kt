package it.lagioiaproductions.shopeasily.data.repository.parsers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSanitizerTest {
    @Test
    fun rejectsImplausiblyLowParsedPrice() {
        assertFalse(CatalogSanitizer.isPlausible("Pasta di Semola Rummo 500 g", 0.08, "Esselunga", "https://esselunga.it", null))
    }

    @Test
    fun rejectsFlyerFromAnotherChainAttributedToLocalShop() {
        assertFalse(
            CatalogSanitizer.isPlausible(
                "Yogurt alla greca Zymil Parmalat",
                1.29,
                "Panificio Pelliccioni",
                "https://cercavolantini.it/",
                "https://cdn.test/pages/it/esselunga/flyer/page_018.webp",
            ),
        )
    }

    @Test
    fun acceptsPlausibleMatchingStoreProduct() {
        assertTrue(CatalogSanitizer.isPlausible("Yogurt alla greca", 1.29, "Esselunga", "https://esselunga.it", null))
    }

    @Test
    fun acceptsAggregatorOnlyWhenItNamesTheStore() {
        assertTrue(
            CatalogSanitizer.isPlausible(
                "Bretzel", 0.39, "Todis", "https://doveconviene.it/volantino/todis", null,
            ),
        )
        assertFalse(
            CatalogSanitizer.isPlausible(
                "Carne", 3.99, "Macelleria Rossi", "https://volantinofacile.it/confronta-prezzi/spesa/carne", null,
            ),
        )
    }
}
