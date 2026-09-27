package it.lagioiaproductions.shopeasily.data.repository.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredSourcesTest {
    @Test
    fun openPricesProductAndCategoryPrices() {
        val json = """
            {"items":[
              {"type":"PRODUCT","price":3.45,"currency":"EUR","price_is_discounted":true,
               "product":{"product_name":"Passata di pomodoro","quantity":"700 g","image_url":"https://images.openfoodfacts.org/x.jpg","labels_tags":["en:organic"]}},
              {"type":"CATEGORY","price":2.2,"currency":"EUR","category_tag":"en:apples","price_per":"KILOGRAM","product":null},
              {"type":"PRODUCT","price":5.0,"currency":"CHF","product":{"product_name":"Formaggio"}}
            ]}
        """.trimIndent()
        val products = OpenPricesSource.parse(json, "https://prices.openfoodfacts.org/api/v1/prices")
        assertEquals(2, products.size)
        val passata = products.first()
        assertEquals("Passata di pomodoro 700 g", passata.name)
        assertTrue(passata.promotional)
        assertEquals(listOf("Biologico"), passata.labels)
        assertEquals("Mele al kg", products[1].name)
    }

    @Test
    fun wooCommerceStoreApiUsesMinorUnits() {
        val json = """[{"name":"Farina di farro &amp; grano","permalink":"https://bottega.it/p/farina","is_in_stock":true,"on_sale":false,
            "prices":{"price":"349","regular_price":"399","currency_code":"EUR","currency_minor_unit":2},
            "images":[{"src":"https://bottega.it/f.jpg"}],"categories":[{"name":"Biologico"}]}]"""
        val product = EcommerceApiSource.parse(EcommerceApiSource.WOO_ID, json, "https://bottega.it/wp-json/wc/store/v1/products").single()
        assertEquals("Farina di farro & grano", product.name)
        assertEquals(3.49, product.price, 0.001)
        assertTrue(product.promotional)
        assertTrue("Biologico" in product.labels)
    }

    @Test
    fun shopifyProductsJson() {
        val json = """{"products":[{"title":"Miele di castagno","handle":"miele","tags":"km0",
            "variants":[{"title":"Default Title","price":"8.50","compare_at_price":null,"available":true}],
            "images":[{"src":"https://cdn.shopify.com/m.jpg"}]}]}"""
        val product = EcommerceApiSource.parse(EcommerceApiSource.SHOPIFY_ID, json, "https://apicoltura.it/products.json").single()
        assertEquals("Miele di castagno", product.name)
        assertEquals(8.5, product.price, 0.001)
        assertEquals("https://apicoltura.it/products/miele", product.sourceUrl)
        assertTrue("Filiera locale" in product.labels)
    }
}
