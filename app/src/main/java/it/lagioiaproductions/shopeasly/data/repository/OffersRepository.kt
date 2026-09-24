package it.lagioiaproductions.shopeasly.data.repository

import it.lagioiaproductions.shopeasly.data.model.Offer
import kotlinx.coroutines.flow.Flow

interface OffersRepository {
    fun search(query: String): Flow<List<Offer>>
}
