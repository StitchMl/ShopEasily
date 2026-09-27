package it.lagioiaproductions.shopeasily.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductMatcherTest {
    @Test
    fun matchesSingularAndPlural() {
        assertTrue(ProductMatcher.matches("Mele Golden 1 kg", "mela"))
        assertTrue(ProductMatcher.matches("Pomodori ciliegino", "pomodoro"))
    }

    @Test
    fun ignoresAccentsCaseAndStopWords() {
        assertTrue(ProductMatcher.matches("CAFFÈ macinato 250 g", "caffe"))
        assertTrue(ProductMatcher.matches("Latte di capra bio", "latte di capra"))
    }

    @Test
    fun doesNotMatchInsideOtherWords() {
        assertFalse(ProductMatcher.matches("Sorriso cioccolato", "riso"))
        assertFalse(ProductMatcher.matches("Pasta", ""))
    }
}
