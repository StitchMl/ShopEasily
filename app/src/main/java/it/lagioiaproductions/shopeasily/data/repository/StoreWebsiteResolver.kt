package it.lagioiaproductions.shopeasily.data.repository

import it.lagioiaproductions.shopeasily.data.repository.net.HttpFetcher
import it.lagioiaproductions.shopeasily.data.repository.net.NonShopSites

/** Normalises a website published in OpenStreetMap; chains without one are resolved by [BrandDirectory]. */
object StoreWebsiteResolver {
    fun resolve(@Suppress("UNUSED_PARAMETER") storeName: String, supplied: String?): String? =
        NonShopSites.shopWebsiteOrNull(supplied)?.let(HttpFetcher::secureUrl)
}
