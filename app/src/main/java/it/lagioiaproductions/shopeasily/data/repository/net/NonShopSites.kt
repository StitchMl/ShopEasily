package it.lagioiaproductions.shopeasily.data.repository.net

import java.net.URI
import java.util.Locale

/**
 * Addresses that are not a shop's own website: social networks, link pages,
 * review/map portals, delivery apps and flyer aggregators. OpenStreetMap and
 * Google often list a Facebook page as "website": it has no readable prices and
 * its logo (Meta) must never become the shop's identity.
 */
object NonShopSites {
    private val hosts = listOf(
        "facebook.", "fb.com", "fb.me", "instagram.", "wa.me", "whatsapp.", "linktr.ee", "linktree.", "tiktok.",
        "twitter.", "x.com", "youtube.", "t.me", "telegram.", "google.", "goo.gl", "maps.app", "tripadvisor.",
        "paginegialle.", "paginebianche.", "yelp.", "thefork.", "justeat.", "just-eat.", "deliveroo.", "glovoapp.",
        "ubereats.", "everli.", "doveconviene.", "volantinofacile.", "promoqui.", "kimbino.", "tiendeo.",
        "wikipedia.", "virgilio.", "subito.it",
    )

    fun isNonShop(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { URI(HttpFetcher.secureUrl(url) ?: url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host.isBlank()) return true
        return hosts.any { marker -> host == marker.trimEnd('.') || host.contains(marker) }
    }

    /**
     * Online platforms (and their owners) that show up as a "place" when a shop's only
     * web presence is a social page, or when a text search returns an office: "Meta",
     * "Facebook", "Instagram"… are never a point of sale.
     */
    private val platformNames = setOf(
        "meta", "meta platforms", "facebook", "instagram", "whatsapp", "messenger", "threads", "google", "google maps",
        "youtube", "tiktok", "telegram", "twitter", "x", "linktree", "tripadvisor", "yelp", "thefork", "just eat",
        "justeat", "deliveroo", "glovo", "uber eats", "ubereats", "everli", "amazon", "subito", "pagine gialle",
        "paginegialle", "doveconviene", "volantinofacile", "promoqui", "tiendeo", "shopfully", "wikipedia",
    )

    fun isPlatformName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .removeSuffix(" inc").removeSuffix(" srl").removeSuffix(" spa").removeSuffix(" ltd").trim()
        return normalized in platformNames
    }

    /** The URL if it can be a shop's own website, otherwise null. */
    fun shopWebsiteOrNull(url: String?): String? = url?.takeIf { it.isNotBlank() && !isNonShop(it) }
}
