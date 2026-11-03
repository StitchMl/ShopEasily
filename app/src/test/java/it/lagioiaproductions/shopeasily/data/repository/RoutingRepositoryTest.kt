package it.lagioiaproductions.shopeasily.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingRepositoryTest {
    @Test
    fun `same point has zero fallback distance`() {
        val point = RoutePoint(41.9028, 12.4964)
        assertEquals(0, RoutingRepository.estimatedRoadMeters(point, point))
    }

    @Test
    fun `fallback distance is plausible and symmetric`() {
        val a = RoutePoint(41.9028, 12.4964)
        val b = RoutePoint(41.9109, 12.4818)
        val forward = RoutingRepository.estimatedRoadMeters(a, b)
        assertTrue(forward in 1_500..2_500)
        assertEquals(forward, RoutingRepository.estimatedRoadMeters(b, a))
    }
}
