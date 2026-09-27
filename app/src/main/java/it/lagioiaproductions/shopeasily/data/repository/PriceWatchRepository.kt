package it.lagioiaproductions.shopeasily.data.repository

import android.content.Context
import it.lagioiaproductions.shopeasily.data.model.Offer
import java.util.Locale

data class PriceHistoryUpdate(
    val drops: Set<String>,
    val historicalLows: Int,
)

class PriceWatchRepository(context: Context) {
    private val preferences = context.getSharedPreferences("price_watch", Context.MODE_PRIVATE)

    /**
     * Must be called off the main thread. Uses `commit()` on the caller's
     * thread and writes only changed values: the old version queued thousands of
     * `apply()` writes on every search, which Android flushes synchronously when
     * the activity pauses (a classic ANR).
     */
    fun recordAndFindDrops(offers: List<Offer>): PriceHistoryUpdate {
        val minimums = offers.groupBy { normalize(it.productName) }.mapValues { (_, values) -> values.minOf(Offer::price) }
        val drops = mutableSetOf<String>()
        var historicalLows = 0
        val editor = preferences.edit()
        var changed = false
        minimums.forEach { (product, price) ->
            val value = price.toFloat()
            val previous = preferences.getFloat("last:$product", Float.NaN)
            val oldMinimum = preferences.getFloat("min:$product", Float.NaN)
            val oldMaximum = preferences.getFloat("max:$product", Float.NaN)
            if (!previous.isNaN() && value < previous) drops += product
            if (oldMinimum.isNaN() || value <= oldMinimum) historicalLows++
            if (previous != value) { editor.putFloat("last:$product", value); changed = true }
            if (oldMinimum.isNaN() || value < oldMinimum) { editor.putFloat("min:$product", value); changed = true }
            if (oldMaximum.isNaN() || value > oldMaximum) { editor.putFloat("max:$product", value); changed = true }
        }
        if (changed) editor.commit()
        return PriceHistoryUpdate(drops, historicalLows)
    }

    fun toggleTarget(product: String, target: Double): Boolean {
        val key = "target:${normalize(product)}"
        val enabled = !preferences.contains(key)
        preferences.edit().apply { if (enabled) putFloat(key, target.toFloat()) else remove(key) }.apply()
        return enabled
    }

    fun isWatched(product: String): Boolean = preferences.contains("target:${normalize(product)}")

    fun reachedTargets(offers: List<Offer>): Int = offers.distinctBy { normalize(it.productName) }.count { offer ->
        val target = preferences.getFloat("target:${normalize(offer.productName)}", Float.NaN)
        !target.isNaN() && offer.price <= target
    }

    private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT)
}
