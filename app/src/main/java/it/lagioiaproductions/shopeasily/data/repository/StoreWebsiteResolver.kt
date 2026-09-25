package it.lagioiaproductions.shopeasily.data.repository

object StoreWebsiteResolver {
    private val knownSites = mapOf(
        "esselunga" to "https://www.esselunga.it",
        "coop" to "https://www.coop.it",
        "conad" to "https://www.conad.it",
        "lidl" to "https://www.lidl.it",
        "eurospin" to "https://www.eurospin.it",
        "carrefour" to "https://www.carrefour.it",
        "aldi" to "https://www.aldi.it",
        "md" to "https://www.mdspa.it",
        "pam" to "https://www.pampanorama.it",
        "todis" to "https://www.todis.it",
        "deco" to "https://www.supermercatideco.it",
        "naturasi" to "https://www.naturasi.it",
        "eataly" to "https://www.eataly.net",
    )

    fun resolve(storeName: String, supplied: String?): String? {
        if (!supplied.isNullOrBlank()) {
            return if (supplied.startsWith("http://") || supplied.startsWith("https://")) supplied else "https://$supplied"
        }
        val normalized = StoreDeduplicator.canonicalName(storeName).replace(" ", "")
        return knownSites.entries.firstOrNull { normalized.contains(it.key) }?.value
    }
}
