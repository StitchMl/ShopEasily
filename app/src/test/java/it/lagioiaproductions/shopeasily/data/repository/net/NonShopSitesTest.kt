package it.lagioiaproductions.shopeasily.data.repository.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun platformNamesAreNotShops() {
        assertTrue(NonShopSites.isPlatformName("Meta"))
        assertTrue(NonShopSites.isPlatformName("META Platforms Inc."))
        assertTrue(NonShopSites.isPlatformName("Facebook"))
        assertFalse(NonShopSites.isPlatformName("Metro"))
        assertFalse(NonShopSites.isPlatformName("Meta Market Bio"))
        assertFalse(NonShopSites.isPlatformName("Oasi Tigre"))
    }
}
