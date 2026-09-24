package it.lagioiaproductions.shopeasily.data.repository

import it.lagioiaproductions.shopeasily.data.model.Offer
import kotlinx.coroutines.flow.Flow

interface OffersRepository {
    fun search(query: String): Flow<List<Offer>>
}
