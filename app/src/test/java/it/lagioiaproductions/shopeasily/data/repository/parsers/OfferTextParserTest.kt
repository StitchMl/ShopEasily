package it.lagioiaproductions.shopeasily.data.repository.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferTextParserTest {
    @Test
    fun rejectsDeliveryAndMinimumOrderFragments() {
        val lines = listOf(
            "Le spese di consegna sono IVA compresa 6,90 €",
            "Da € 99.00 la consegna è gratuita 98,99 €",
            "per gli ordini in consegna il giorno successivo 13,00 €",
            "fascia oraria dalle alle 14.00 ed entro le ore 22.00 8,00 €",
        )

        assertTrue(OfferTextParser.parse(lines).isEmpty())
    }

    @Test
    fun acceptsInlineProductAndPrice() {
        assertEquals(
            listOf(ParsedOfferText("Olio Extravergine di Oliva Italiano", 9.9)),
            OfferTextParser.parse(listOf("Olio Extravergine di Oliva Italiano 9,90 €")),
        )
    }

    @Test
    fun usesPreviousProductLineWhenPriceIsSeparate() {
        assertEquals(
            listOf(ParsedOfferText("Pasta di Semola", 0.89)),
            OfferTextParser.parse(listOf("Pasta di Semola", "0,89 €")),
        )
    }
}
