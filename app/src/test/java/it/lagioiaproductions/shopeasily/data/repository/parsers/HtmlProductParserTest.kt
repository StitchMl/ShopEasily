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

    @Test
    fun `reads offer tiles with custom markup`() {
        val html = """
            <div class="promo-grid">
              <div class="box-offerta"><img src="/rummo.jpg"><div class="txt"><p class="marca">Rummo Pasta di semola 500 g</p>
                <div class="prezzi"><span class="old">€ 1,19</span> <span class="new">€ 0,79</span></div></div></div>
              <div class="box-offerta"><img src="/olio.jpg"><div class="txt"><p class="marca">Olio extravergine San Giorgio 1 L</p>
                <div class="prezzi"><span class="new">€ 4,99</span></div></div></div>
              <footer>Spedizione gratuita sopra € 49,00</footer>
            </div>
        """.trimIndent()

        val products = HtmlProductParser.parse(html, "https://super.example/")

        val pasta = products.first { it.name.startsWith("Rummo") }
        assertEquals(0.79, pasta.price, 0.001)
        assertEquals("https://super.example/rummo.jpg", pasta.imageUrl)
        assertEquals(4.99, products.first { it.name.startsWith("Olio") }.price, 0.001)
        assertFalse(products.any { it.name.contains("Spedizione") })
    }

    @Test
    fun `joins offer titles split over several lines`() {
        val html = """
            <div class="offerte"><div class="item"><img src="/r.jpg">
              <div class="marca">Rummo</div><div class="nome">Pasta di semola</div>
              <div class="descr">Lenta lavorazione formati normali g 500</div>
              <div class="old">Anziché €<b>0,00</b></div><div class="prezzo">€ 0,79</div><div class="kg">al Kg € 1,58</div>
            </div></div>
        """.trimIndent()

        val product = HtmlProductParser.parse(html, "https://www.superelite.it/").single()

        assertEquals("Rummo Pasta di semola Lenta lavorazione formati normali g 500", product.name)
        assertEquals(0.79, product.price, 0.001)
    }

    @Test
    fun `ignores footer, privacy and other website text next to numbers`() {
        val html = """
            <div class="grid"><div class="item"><img src="/b.jpg"><p>Biscotti frollini 700 g</p><span>€ 1,99</span></div></div>
            <div class="box"><p>Privacy policy</p><span>€ 50,00</span></div>
            <div class="box"><p>Carta fedeltà Club</p><span>€ 45,00</span></div>
            <footer><p>Spedizione gratuita sopra</p><span>€ 49,00</span><p>P.IVA 01234567890</p></footer>
        """.trimIndent()

        val products = HtmlProductParser.parse(html, "https://www.todis.it/")

        assertEquals(listOf("Biscotti frollini 700 g"), products.map { it.name })
    }
}
