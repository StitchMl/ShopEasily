package it.lagioiaproductions.shopeasily.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductImageMatcherTest {
    @Test
    fun `keeps matching legacy image`() {
        assertTrue(
            ProductImageMatcher.matchesLegacyImage(
                "BRETZEL",
                "https://cdn.example/public/assets/penny_nazionale_24set_05_09.png?w=300",
            ),
        )
    }

    @Test
    fun `rejects unrelated flyer image`() {
        assertFalse(
            ProductImageMatcher.matchesLegacyImage(
                "Sprint Way - Integratore",
                "https://it-it-media.shopfully.cloud/images/flyergibs/gibCover_6ab12211.png",
            ),
        )
    }
}
