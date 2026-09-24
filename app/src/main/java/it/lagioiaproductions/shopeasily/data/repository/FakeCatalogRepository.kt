package it.lagioiaproductions.shopeasily.data.repository

import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import it.lagioiaproductions.shopeasily.data.model.Store
import it.lagioiaproductions.shopeasily.data.model.StoreChannel

class FakeCatalogRepository {
    val stores = listOf(
        Store(1, "Supermercato Centro", StoreChannel.PHYSICAL, 45.4642, 9.1900, 850),
        Store(2, "Market Bio", StoreChannel.PHYSICAL, 45.4700, 9.1810, 1_400),
        Store(3, "Spesa Online Verde", StoreChannel.ONLINE, null, null, 0, 3.90, 0.0, 0.75, 0.78, "Valutazione dimostrativa da sostituire con audit verificato"),
        Store(4, "Consegna Rapida", StoreChannel.ONLINE, null, null, 0, 5.90, 0.0, 1.30, null, null),
    )

    val catalog = listOf(
        price(1, "Latte", 1.29, true), price(1, "Pasta", 0.89, true), price(1, "Pomodori", 2.60),
        price(2, "Latte", 1.69, true), price(2, "Pasta", 1.20), price(2, "Pomodori", 2.10, true),
        price(3, "Latte", 1.19), price(3, "Pasta", 0.95), price(3, "Pomodori", 2.30),
        price(4, "Latte", 1.05), price(4, "Pasta", 1.05), price(4, "Pomodori", 1.99),
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
