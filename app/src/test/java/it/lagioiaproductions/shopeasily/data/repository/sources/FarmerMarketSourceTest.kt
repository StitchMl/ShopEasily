package it.lagioiaproductions.shopeasily.data.repository.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmerMarketSourceTest {
    @Test
    fun findsTheMarketShopSubdomainOnly() {
        val html = """<a href="https://www.campagnamica.it/">Home</a>
            <a href="https://spesaromacircomassimo.campagnamica.it/">Fai la spesa online</a>
            <a href="https://mercatocircomassimo.campagnamica.it">Il mercato</a>"""
        assertEquals(
            listOf("https://spesaromacircomassimo.campagnamica.it", "https://mercatocircomassimo.campagnamica.it"),
            FarmerMarketSource.shopLinks(html),
        )
    }

    @Test
    fun recognisesMarketsAndSearchTerms() {
        assertTrue(FarmerMarketSource.isFarmerMarket("Mercato Laurentino", "marketplace"))
        assertTrue(FarmerMarketSource.isFarmerMarket("Mercato di Campagna Amica", "convenience"))
        assertFalse(FarmerMarketSource.isFarmerMarket("Conad City", "supermarket"))
        assertEquals(listOf("circo", "massimo"), FarmerMarketSource.searchTokens("Mercato di Campagna Amica al Circo Massimo"))
    }
}
