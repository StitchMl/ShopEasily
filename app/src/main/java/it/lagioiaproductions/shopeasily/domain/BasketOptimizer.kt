package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import it.lagioiaproductions.shopeasily.data.model.Store
import it.lagioiaproductions.shopeasily.data.model.StoreChannel
import it.lagioiaproductions.shopeasily.data.preferences.FuelType
import it.lagioiaproductions.shopeasily.data.preferences.VehicleType
import java.util.Locale

data class TransportProfile(
    val vehicle: VehicleType = VehicleType.CAR,
    val fuel: FuelType = FuelType.GASOLINE,
    val consumptionPer100Km: Double = 6.5,
    val pricePerUnit: Double = FuelType.GASOLINE.defaultPrice,
) {
    fun costPerKm(): Double = consumptionPer100Km / 100.0 * pricePerUnit
    fun emissionKgPerKm(): Double = consumptionPer100Km / 100.0 * fuel.kgCo2PerUnit * vehicle.emissionMultiplier
}

data class BasketAssignment(
    val requestedItem: String,
    val catalogItem: CatalogPrice,
)

enum class BasketGoal { CHEAPEST, QUALITY, ECOLOGICAL, FAIR_TRADE, BALANCED }

data class BasketPlan(
    val assignments: List<BasketAssignment>,
    val stores: List<Store>,
    val productsTotal: Double,
    val serviceCosts: Double,
    val estimatedTravelCost: Double,
    val estimatedEmissionKgCo2: Double,
    val ethicalRiskPenalty: Double,
    /** Requested items that no store of this plan sells. */
    val missingItems: List<String> = emptyList(),
) {
    val monetaryTotal: Double = productsTotal + serviceCosts + estimatedTravelCost
    val unavailableItems: Int = missingItems.size + assignments.count { it.catalogItem.price.isNaN() }
    val averageQuality: Double = assignments.mapNotNull { it.catalogItem.qualityScore }.average().let {
        if (it.isNaN()) 0.0 else it
    }
    val ecologicalItems: Int = assignments.count { it.catalogItem.ecological }
    val fairTradeItems: Int = assignments.count { it.catalogItem.fairTrade }
}

object BasketOptimizer {
    /** Upper bound on candidate stores: keeps pair enumeration below ~1.300 plans. */
    private const val MAX_CANDIDATE_STORES = 50

    /**
     * Finds the best one- or two-store baskets.
     *
     * The previous implementation filtered the whole catalogue for every store
     * pair and every requested item (O(stores² × catalogue × items)); with a
     * real on-device catalogue of thousands of prices that froze the UI thread
     * for tens of seconds and Android killed the app. Candidates are now
     * indexed once per (store, item), so each pair costs only O(items).
     *
     * Plans that cover only part of the list are kept (after the complete
     * ones) so the user always gets an answer and sees what is missing.
     */
    fun optimize(
        requestedItems: List<String>,
        catalog: List<CatalogPrice>,
        maximumStores: Int = 2,
        transport: TransportProfile = TransportProfile(),
        goal: BasketGoal = BasketGoal.BALANCED,
    ): List<BasketPlan> {
        val requested = requestedItems.map(String::trim).filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (requested.isEmpty() || catalog.isEmpty()) return emptyList()

        // store id -> (item index -> best candidate for the goal)
        val bestByStore = HashMap<Long, Array<CatalogPrice?>>()
        val storesById = LinkedHashMap<Long, Store>()
        val matchCache = HashMap<Pair<String, String>, Boolean>()
        catalog.forEach { candidate ->
            requested.forEachIndexed { index, item ->
                val matches = matchCache.getOrPut(candidate.productName to item) { candidate.matches(item) }
                if (!matches) return@forEachIndexed
                storesById.putIfAbsent(candidate.store.id, candidate.store)
                val slots = bestByStore.getOrPut(candidate.store.id) { arrayOfNulls(requested.size) }
                val current = slots[index]
                slots[index] = if (current == null) candidate else better(current, candidate, goal)
            }
        }
        if (bestByStore.isEmpty()) return emptyList()

        val candidates = storesById.values
            .sortedWith(
                compareByDescending<Store> { store -> bestByStore.getValue(store.id).count { it != null } }
                    .thenBy { if (it.channel == StoreChannel.ONLINE) 0 else it.distanceMeters },
            )
            .take(MAX_CANDIDATE_STORES)
        val storeSets = candidates.map(::listOf) + if (maximumStores >= 2) {
            candidates.flatMapIndexed { index, first ->
                candidates.drop(index + 1).map { second -> listOf(first, second) }
            }
        } else {
            emptyList()
        }

        val sortedPlans = storeSets.mapNotNull { selectedStores ->
            buildPlan(requested, bestByStore, selectedStores, transport, goal)
        }.distinct().sortedWith(comparator(goal))
        val recommended = sortedPlans.take(3)
        val bestOnline = sortedPlans.firstOrNull { plan ->
            plan.stores.any { it.channel == StoreChannel.ONLINE }
        }
        return (recommended + listOfNotNull(bestOnline)).distinct()
    }

    private fun buildPlan(
        requestedItems: List<String>,
        bestByStore: Map<Long, Array<CatalogPrice?>>,
        stores: List<Store>,
        transport: TransportProfile,
        goal: BasketGoal,
    ): BasketPlan? {
        val missing = mutableListOf<String>()
        val assignments = requestedItems.mapIndexedNotNull { index, requested ->
            val options = stores.mapNotNull { bestByStore[it.id]?.get(index) }
            val chosen = options.reduceOrNull { first, second -> better(first, second, goal) }
            if (chosen == null) {
                missing += requested
                null
            } else {
                BasketAssignment(requested, chosen)
            }
        }
        if (assignments.isEmpty()) return null

        val usedStores = assignments.map { it.catalogItem.store }.distinctBy(Store::id)
        val subtotals = assignments.groupBy { it.catalogItem.store.id }
            .mapValues { (_, items) -> items.sumOf { it.catalogItem.price } }
        if (usedStores.any { store -> subtotals.getValue(store.id) < store.minimumOrder }) return null

        val deliveryFees = usedStores.filter { it.channel == StoreChannel.ONLINE }.sumOf(Store::deliveryFee)
        val physicalRoundTripKm = usedStores.filter { it.channel == StoreChannel.PHYSICAL }
            .sumOf { it.distanceMeters * 2.0 / 1_000.0 }
        val deliveryEmissions = usedStores.sumOf(Store::deliveryEmissionKgCo2)
        val laborPenalty = usedStores.filter { it.channel == StoreChannel.ONLINE }
            .sumOf { store -> store.laborScore?.let { (1.0 - it) * 2.0 } ?: 1.0 }

        return BasketPlan(
            assignments = assignments,
            stores = usedStores,
            productsTotal = assignments.sumOf { it.catalogItem.price },
            serviceCosts = deliveryFees,
            estimatedTravelCost = physicalRoundTripKm * transport.costPerKm(),
            estimatedEmissionKgCo2 = deliveryEmissions + physicalRoundTripKm * transport.emissionKgPerKm(),
            ethicalRiskPenalty = laborPenalty,
            missingItems = missing,
        )
    }

    /** Returns the preferred of two candidates for the same item according to the goal. */
    private fun better(first: CatalogPrice, second: CatalogPrice, goal: BasketGoal): CatalogPrice {
        val comparator: Comparator<CatalogPrice> = when (goal) {
            BasketGoal.CHEAPEST -> compareBy { it.price }
            BasketGoal.QUALITY -> compareByDescending<CatalogPrice> { it.qualityScore ?: 0.0 }.thenBy { it.price }
            BasketGoal.ECOLOGICAL -> compareByDescending<CatalogPrice> { it.ecological }
                .thenByDescending { it.qualityScore ?: 0.0 }.thenBy { it.price }
            BasketGoal.FAIR_TRADE -> compareByDescending<CatalogPrice> { it.fairTrade }
                .thenByDescending { it.qualityScore ?: 0.0 }.thenBy { it.price }
            BasketGoal.BALANCED -> compareBy { candidate ->
                candidate.price - (candidate.qualityScore ?: 0.0) * 0.08 -
                    (if (candidate.ecological) 0.18 else 0.0) - (if (candidate.fairTrade) 0.18 else 0.0)
            }
        }
        return if (comparator.compare(second, first) < 0) second else first
    }

    private fun comparator(goal: BasketGoal): Comparator<BasketPlan> {
        val completeFirst = compareBy<BasketPlan> { it.unavailableItems }
        return when (goal) {
            BasketGoal.CHEAPEST -> completeFirst.thenBy(BasketPlan::monetaryTotal)
            BasketGoal.QUALITY -> completeFirst.thenByDescending(BasketPlan::averageQuality).thenBy(BasketPlan::monetaryTotal)
            BasketGoal.ECOLOGICAL -> completeFirst.thenByDescending(BasketPlan::ecologicalItems)
                .thenBy(BasketPlan::estimatedEmissionKgCo2).thenBy(BasketPlan::monetaryTotal)
            BasketGoal.FAIR_TRADE -> completeFirst.thenByDescending(BasketPlan::fairTradeItems)
                .thenBy(BasketPlan::ethicalRiskPenalty).thenBy(BasketPlan::monetaryTotal)
            BasketGoal.BALANCED -> completeFirst.thenBy { plan ->
                plan.monetaryTotal + plan.ethicalRiskPenalty + plan.estimatedEmissionKgCo2 * 0.15 -
                    plan.averageQuality * 0.15 - plan.ecologicalItems * 0.18 - plan.fairTradeItems * 0.18
            }
        }
    }

    private fun CatalogPrice.matches(requested: String): Boolean {
        val normalized = requested.trim().lowercase(Locale.ROOT)
        return normalized in aliases || ProductMatcher.matches(productName, requested)
    }
}
