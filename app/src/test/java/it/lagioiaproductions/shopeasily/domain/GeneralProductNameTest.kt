package it.lagioiaproductions.shopeasily.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GeneralProductNameTest {
    @Test
    fun removesStoreBrandAndPackageSize() {
        assertEquals("Latte", GeneralProductName.from("Latte CONAD 1 L", "Conad Superstore Torrino"))
    }

    @Test
    fun mapsBrandedCleanerToGenericCategory() {
        assertEquals("Detergente vetri", GeneralProductName.from("Quasar vetri 650 ml", "Todis"))
    }

    @Test
    fun removesBrandsRangesAndAdjectivesFromKnownProducts() {
        assertEquals(
            "Lenticchie",
            GeneralProductName.from("Bonduelle Bonduelle Legumeria Lenticchie Piccole", "Carrefour Market"),
        )
        assertEquals("Passata di pomodoro", GeneralProductName.from("Classic Passata di Pomodoro", "Carrefour"))
        assertEquals("Zuppa", GeneralProductName.from("Il Mercato Zuppa Toscana", "Carrefour Market"))
    }
}
