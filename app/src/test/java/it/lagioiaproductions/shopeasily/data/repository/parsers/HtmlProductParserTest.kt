package it.lagioiaproductions.shopeasily.data.repository.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HtmlProductParserTest {
    @Test
    fun `keeps each image inside its own product card`() {
        val html = """
            <img src="/generic-flyer.jpg">
            <article class="product-card">
              <a href="/latte"><h3>Latte intero 1 L</h3></a>
              <span class="price">€ 1,29</span><img src="/latte.jpg">
            </article>
            <article class="product-card">
              <a href="/pasta"><h3>Pasta 500 g</h3></a>
              <span class="price">0,89 €</span><img data-src="/pasta.jpg">
            </article>
        """.trimIndent()

        val products = HtmlProductParser.parse(html, "https://shop.example/catalogo")

        assertEquals(2, products.size)
        assertEquals("https://shop.example/latte.jpg", products.first { it.name.startsWith("Latte") }.imageUrl)
        assertEquals("https://shop.example/pasta.jpg", products.first { it.name.startsWith("Pasta") }.imageUrl)
        assertFalse(products.any { it.imageUrl?.contains("generic-flyer") == true })
    }
}
