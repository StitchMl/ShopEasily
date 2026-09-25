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

    fun recordAndFindDrops(offers: List<Offer>): PriceHistoryUpdate {
        val minimums = offers.groupBy { normalize(it.productName) }.mapValues { (_, values) -> values.minOf(Offer::price) }
        val drops = minimums.mapNotNull { (product, price) ->
            val previous = preferences.getFloat("last:$product", Float.NaN).toDouble()
            product.takeIf { !previous.isNaN() && price < previous }
        }.toSet()
        val historicalLows = minimums.count { (product, price) ->
            val minimum = preferences.getFloat("min:$product", Float.NaN).toDouble()
            minimum.isNaN() || price <= minimum
        }
        preferences.edit().apply {
            minimums.forEach { (product, price) ->
                val oldMinimum = preferences.getFloat("min:$product", Float.NaN).toDouble()
                val oldMaximum = preferences.getFloat("max:$product", Float.NaN).toDouble()
                putFloat("last:$product", price.toFloat())
                putFloat("min:$product", if (oldMinimum.isNaN()) price.toFloat() else minOf(oldMinimum, price).toFloat())
                putFloat("max:$product", if (oldMaximum.isNaN()) price.toFloat() else maxOf(oldMaximum, price).toFloat())
            }
        }.apply()
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
