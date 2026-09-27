package it.lagioiaproductions.shopeasily.data.repository.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NonShopSitesTest {
    @Test
    fun socialPagesAreNotShopWebsites() {
        assertNull(NonShopSites.shopWebsiteOrNull("https://www.facebook.com/bottegarossi"))
        assertNull(NonShopSites.shopWebsiteOrNull("instagram.com/forno_mirti"))
        assertNull(NonShopSites.shopWebsiteOrNull("https://linktr.ee/mercato"))
        assertEquals("https://www.superelite.it", NonShopSites.shopWebsiteOrNull("https://www.superelite.it"))
    }
}
