package it.lagioiaproductions.shopeasily.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CartEntryTest {
    @Test
    fun snapshotKeepsDistanceForImmediateTransportTotals() {
        val original = CartEntry(7L, "Latte", "Conad", 1.49, true, 2_350)

        assertEquals(original, CartEntry.decode(original.encode()))
    }

    @Test
    fun oldFiveFieldSnapshotsRemainReadable() {
        val legacy = "7\u001FLatte\u001FConad\u001F1.49\u001Ftrue"
        val decoded = CartEntry.decode(legacy)

        assertNotNull(decoded)
        assertEquals(0, decoded?.distanceMeters)
    }
}
