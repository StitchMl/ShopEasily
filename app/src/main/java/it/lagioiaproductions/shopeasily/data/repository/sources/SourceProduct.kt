package it.lagioiaproductions.shopeasily.data.repository.sources

/** Product/price found by a structured source (API or feed), before becoming an offer. */
data class SourceProduct(
    val name: String,
    val price: Double,
    val imageUrl: String?,
    val promotional: Boolean,
    val sourceUrl: String,
    val labels: List<String> = emptyList(),
    val parserId: String,
)
