package it.lagioiaproductions.shopeasily.domain

import java.text.Normalizer
import java.util.Locale

object ProductImageMatcher {
    /** Older cached catalogs did not record whether an image was a product crop.
     * Keep their useful crops, but reject known flyer-cover assets: a cover can
     * look convincing while actually advertising a completely different item.
     */
    fun matchesLegacyImage(productName: String, imageUrl: String): Boolean {
        val url = normalize(imageUrl)
        if (genericAssetMarkers.any(url::contains)) return false
        return productName.isNotBlank()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)

    private val genericAssetMarkers = setOf(
        "gibcover", "flyer-cover", "flyer_cover", "catalog-cover", "catalog_cover",
    )
}
