package it.lagioiaproductions.shopeasily.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeOffersRepositoryTest {
    private val repository = FakeOffersRepository()

    @Test
    fun search_filtersByProductName() = runBlocking {
        val results = repository.search("latte").first()

        assertEquals(3, results.size)
    }

    @Test
    fun search_returnsNoResultsForUnknownProduct() = runBlocking {
        val results = repository.search("prodotto inesistente xyz").first()

        assertTrue(results.isEmpty())
    }
}
