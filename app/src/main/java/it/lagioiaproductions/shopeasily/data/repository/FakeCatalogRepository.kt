package it.lagioiaproductions.shopeasily.data.repository

import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import it.lagioiaproductions.shopeasily.data.model.Store
import it.lagioiaproductions.shopeasily.data.model.StoreChannel

class FakeCatalogRepository {
    val stores = listOf(
        Store(1, "Esselunga", StoreChannel.PHYSICAL, 45.4642, 9.1900, 850),
        Store(2, "NaturaSì", StoreChannel.PHYSICAL, 45.4700, 9.1810, 1_400),
        Store(3, "Cortilia", StoreChannel.ONLINE, null, null, 0, 3.90, 0.0, 0.75, 0.78, "Valutazione dimostrativa da sostituire con audit verificato"),
        Store(4, "Carrefour online", StoreChannel.ONLINE, null, null, 0, 5.90, 0.0, 1.30, null, null),
        Store(5, "Macelleria locale", StoreChannel.PHYSICAL, 45.4628, 9.1842, 650),
        Store(6, "Mercato comunale", StoreChannel.PHYSICAL, 45.4681, 9.1941, 1_100),
    )

    val catalog = listOf(
        price(1, "Latte", 1.29, true), price(1, "Pasta", 0.89, true), price(1, "Pomodori", 2.60),
        price(2, "Latte", 1.69, true), price(2, "Pasta", 1.20), price(2, "Pomodori", 2.10, true),
        price(3, "Latte", 1.19), price(3, "Pasta", 0.95), price(3, "Pomodori", 2.30),
        price(4, "Latte", 1.05), price(4, "Pasta", 1.05), price(4, "Pomodori", 1.99),
        price(5, "Pollo", 8.90), price(5, "Uova", 2.80),
        price(6, "Pomodori", 1.80), price(6, "Mele", 1.95), price(6, "Pane", 3.20),
    )

    private fun price(storeId: Long, name: String, value: Double, promotional: Boolean = false) =
        CatalogPrice(
            store = stores.first { it.id == storeId },
            productName = name,
            aliases = setOf(name.lowercase()),
            price = value,
            promotional = promotional,
        )
}
