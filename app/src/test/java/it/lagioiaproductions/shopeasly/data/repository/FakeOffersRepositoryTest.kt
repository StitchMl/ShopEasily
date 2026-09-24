package it.lagioiaproductions.shopeasly.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeOffersRepositoryTest {
    private val repository = FakeOffersRepository()

    @Test
    fun search_filtersByProductNameAndSortsByPrice() = runBlocking {
        val results = repository.search("latte").first()

        assertEquals(3, results.size)
        assertTrue(results.zipWithNext().all { (first, second) -> first.price <= second.price })
    }

    @Test
    fun search_returnsNoResultsForUnknownProduct() = runBlocking {
        val results = repository.search("caffè").first()

        assertTrue(results.isEmpty())
    }
}
