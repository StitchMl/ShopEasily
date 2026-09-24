package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import it.lagioiaproductions.shopeasily.data.model.Store
import it.lagioiaproductions.shopeasily.data.model.StoreChannel
import it.lagioiaproductions.shopeasily.data.preferences.FuelType
import it.lagioiaproductions.shopeasily.data.preferences.VehicleType

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

data class BasketPlan(
    val assignments: List<BasketAssignment>,
    val stores: List<Store>,
    val productsTotal: Double,
    val serviceCosts: Double,
    val estimatedTravelCost: Double,
    val estimatedEmissionKgCo2: Double,
    val ethicalRiskPenalty: Double,
) {
    val monetaryTotal: Double = productsTotal + serviceCosts + estimatedTravelCost
    val unavailableItems: Int = assignments.count { it.catalogItem.price.isNaN() }
}

object BasketOptimizer {
    fun optimize(
        requestedItems: List<String>,
        catalog: List<CatalogPrice>,
        maximumStores: Int = 2,
        transport: TransportProfile = TransportProfile(),
    ): List<BasketPlan> {
        if (requestedItems.isEmpty()) return emptyList()
        val stores = catalog.map(CatalogPrice::store).distinctBy(Store::id)
        val storeSets = stores.map(::listOf) + if (maximumStores >= 2) {
            stores.flatMapIndexed { index, first ->
                stores.drop(index + 1).map { second -> listOf(first, second) }
            }
        } else {
            emptyList()
        }

        val sortedPlans = storeSets.mapNotNull { selectedStores ->
            buildPlan(requestedItems, catalog, selectedStores, transport)
        }.sortedWith(
            compareBy<BasketPlan> { it.unavailableItems }
                .thenBy { it.monetaryTotal + it.ethicalRiskPenalty },
        )
        val recommended = sortedPlans.take(3)
        val bestOnline = sortedPlans.firstOrNull { plan ->
            plan.stores.any { it.channel == StoreChannel.ONLINE }
        }
        return (recommended + listOfNotNull(bestOnline)).distinct()
    }

    private fun buildPlan(
        requestedItems: List<String>,
        catalog: List<CatalogPrice>,
        stores: List<Store>,
        transport: TransportProfile,
    ): BasketPlan? {
        val available = catalog.filter { candidate -> candidate.store in stores }
        val assignments = requestedItems.mapNotNull { requested ->
            available.filter { candidate -> candidate.matches(requested) }
                .minByOrNull(CatalogPrice::price)
                ?.let { BasketAssignment(requested, it) }
        }
        if (assignments.size != requestedItems.size) return null

        val usedStores = assignments.map { it.catalogItem.store }.distinctBy(Store::id)
        val subtotals = assignments.groupBy { it.catalogItem.store }
            .mapValues { (_, items) -> items.sumOf { it.catalogItem.price } }
        if (usedStores.any { store -> subtotals.getValue(store) < store.minimumOrder }) return null

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
        )
    }

    private fun CatalogPrice.matches(requested: String): Boolean {
        val normalized = requested.trim().lowercase()
        return normalized in aliases || productName.lowercase().contains(normalized)
    }
}
