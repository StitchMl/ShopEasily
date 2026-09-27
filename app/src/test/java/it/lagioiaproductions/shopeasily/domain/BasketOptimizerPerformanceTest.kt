package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import it.lagioiaproductions.shopeasily.data.model.Store
import it.lagioiaproductions.shopeasily.data.model.StoreChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BasketOptimizerPerformanceTest {
    private val products = listOf("Latte intero", "Pasta di semola", "Pomodori pelati", "Mele golden", "Pane", "Uova", "Riso", "Caffè")

    @Test
    fun largeCatalogueIsOptimisedQuickly() {
        // 300 stores × 40 products = 12.000 prices: the old algorithm needed minutes.
        val catalog = (1..300).flatMap { storeIndex ->
            val store = Store(storeIndex.toLong(), "Negozio $storeIndex", StoreChannel.PHYSICAL, 41.9, 12.5, storeIndex * 50)
            (0 until 40).map { productIndex ->
                val name = products[productIndex % products.size] + " variante $productIndex"
                CatalogPrice(store, name, setOf(name.lowercase()), 1.0 + (storeIndex * productIndex % 17) / 10.0, promotional = false)
            }
        }
        val started = System.nanoTime()
        val plans = BasketOptimizer.optimize(listOf("latte", "pasta", "mele", "caffe"), catalog)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(plans.isNotEmpty())
        assertTrue("took $elapsedMs ms", elapsedMs < 3_000)
    }

    @Test
    fun partialPlanListsMissingItems() {
        val store = Store(1, "Bottega", StoreChannel.PHYSICAL, 41.9, 12.5, 300)
        val catalog = listOf(CatalogPrice(store, "Latte fresco", setOf("latte fresco"), 1.5, promotional = false))
        val plan = BasketOptimizer.optimize(listOf("latte", "zafferano"), catalog).single()
        assertEquals(listOf("zafferano"), plan.missingItems)
        assertEquals(1, plan.assignments.size)
    }
}
