package it.lagioiaproductions.shopeasily.data.repository

import it.lagioiaproductions.shopeasily.data.repository.sources.GooglePlace
import it.lagioiaproductions.shopeasily.data.repository.sources.GooglePlacesSource

data class StoreIdentityMatch(val index: Int, val adoptSourceName: Boolean)

/** Reconciles independently sourced branches without collapsing nearby branches of one chain. */
object StoreIdentityMatcher {
    fun match(stores: List<NearbyStore>, place: GooglePlace): StoreIdentityMatch? {
        val placeTokens = StoreDeduplicator.meaningfulTokens(place.name).toSet()
        val named = stores.mapIndexedNotNull { index, store ->
            val distance = distance(store, place)
            val sharedName = StoreDeduplicator.meaningfulTokens(store.name).any(placeTokens::contains)
            if (distance <= NAME_MATCH_METERS && sharedName) Triple(index, distance, store) else null
        }.minByOrNull { it.second }
        if (named != null) {
            return StoreIdentityMatch(
                index = named.first,
                adoptSourceName = named.second <= PRECISE_POSITION_METERS &&
                    StoreDeduplicator.canonicalName(named.third.name) != StoreDeduplicator.canonicalName(place.name),
            )
        }

        // A changed banner can have no word in common. Accept position-only identity only
        // if exactly one food shop is within a tight radius; crowded markets remain separate.
        val positional = stores.mapIndexedNotNull { index, store ->
            val distance = distance(store, place)
            if (distance <= PRECISE_POSITION_METERS) index to distance else null
        }.sortedBy { it.second }
        return positional.singleOrNull()?.let { StoreIdentityMatch(it.first, adoptSourceName = true) }
    }

    private fun distance(store: NearbyStore, place: GooglePlace): Double = GooglePlacesSource.distanceMeters(
        store.latitude,
        store.longitude,
        place.latitude,
        place.longitude,
    )

    private const val NAME_MATCH_METERS = 120.0
    private const val PRECISE_POSITION_METERS = 40.0
}
