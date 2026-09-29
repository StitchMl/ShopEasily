package it.lagioiaproductions.shopeasily.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StoreDeduplicatorTest {
    @Before
    fun registerBrandsFromOpenStreetMap() {
        // In the app these come from the OSM `brand` tag of nearby shops, not from a hand-written list.
        StoreDeduplicator.registerBrands(
            listOf(
                BrandRecord("Carrefour Express", "Carrefour"),
                BrandRecord("Conad City", "Conad"),
            ),
        )
    }

    @Test
    fun groupsCarrefourFormatsUnderOneBrand() {
        assertEquals("carrefour", StoreDeduplicator.brandKey("Carrefour Market Roma"))
        assertEquals("Carrefour", StoreDeduplicator.brandDisplayName("Carrefour Express"))
        assertTrue(StoreDeduplicator.belongsToBrand("Carrefour Market", "Carrefour"))
    }

    @Test
    fun groupsConadFormatsUnderOneBrand() {
        assertEquals("conad", StoreDeduplicator.brandKey("Spazio Conad"))
        assertEquals("Conad", StoreDeduplicator.brandDisplayName("Conad Superstore"))
        assertTrue(StoreDeduplicator.belongsToBrand("Conad City", "Conad"))
    }

    @Test
    fun independentShopsStaySeparate() {
        assertFalse(StoreDeduplicator.belongsToBrand("Forno Rossi", "Forno Bianchi"))
        assertTrue(StoreDeduplicator.isGenericName("Frutta e Verdura"))
        assertFalse(StoreDeduplicator.isGenericName("Elite"))
    }

    @Test
    fun articlesAndPrepositionsNeverBecomeStoreFilters() {
        StoreDeduplicator.learnFromNames(
            listOf("Le Delizie", "Le Bontà", "La Dispensa", "La Bottega", "Da Mario", "Da Lucia"),
        )
        StoreDeduplicator.registerBrands(listOf(BrandRecord("Le Delizie", "Le")))

        assertEquals("Le Delizie", StoreDeduplicator.brandDisplayName("Le Delizie"))
        assertFalse(StoreDeduplicator.belongsToBrand("Le Delizie", "Le Bontà"))
        assertFalse(StoreDeduplicator.belongsToBrand("Da Mario", "Da Lucia"))
    }

    @Test
    fun learnsBrandsFromShopNamesWithoutAnyList() {
        StoreDeduplicator.learnFromNames(
            listOf("Todis", "Todis Express", "Punto Todis", "Elite Supermercati", "Supermercato Elite", "Forno Neri", "Forno Verdi"),
        )
        assertTrue(StoreDeduplicator.belongsToBrand("Punto Todis", "Todis"))
        assertTrue(StoreDeduplicator.belongsToBrand("Supermercato Elite", "Elite Supermercati"))
        assertEquals("Todis", StoreDeduplicator.brandDisplayName("Todis Express"))
        assertFalse(StoreDeduplicator.belongsToBrand("Forno Neri", "Forno Verdi"))
    }

    @Test
    fun foldsStoreFormatsTaggedAsSeparateBrandsInOpenStreetMap() {
        // name-suggestion-index tags each format as its own brand: they must still form one filter.
        StoreDeduplicator.registerBrands(
            listOf(
                BrandRecord("Conad City", "Conad City"),
                BrandRecord("Spazio Conad", "Spazio Conad"),
                BrandRecord("Conad Superstore", "Conad Superstore"),
                BrandRecord("Carrefour Market", "Carrefour Market"),
                BrandRecord("Carrefour Express", "Carrefour Express"),
                BrandRecord("Coop", "Coop"),
                BrandRecord("Ipercoop", "Ipercoop"),
            ),
        )
        assertTrue(StoreDeduplicator.belongsToBrand("Spazio Conad", "Conad City"))
        assertTrue(StoreDeduplicator.belongsToBrand("Carrefour Market", "Carrefour Express"))
        assertEquals("Carrefour", StoreDeduplicator.brandDisplayName("Carrefour Market Piazza Bologna"))
        assertTrue(StoreDeduplicator.belongsToBrand("Ipercoop", "Coop"))
        assertEquals("Conad", StoreDeduplicator.brandDisplayName("Conad Superstore"))
    }

    @Test
    fun placeNamesNeverBecomeBrands() {
        StoreDeduplicator.learnFromNames(
            listOf("Eataly Roma", "Roma", "Macelleria Roma", "Alimentari Roma Nord", "Pewex", "Pewex Market"),
            places = listOf("Roma"),
        )
        assertEquals("Eataly Roma", StoreDeduplicator.brandDisplayName("Eataly Roma"))
        assertFalse(StoreDeduplicator.belongsToBrand("Eataly Roma", "Macelleria Roma"))
        assertTrue(StoreDeduplicator.belongsToBrand("Pewex Market", "Pewex"))
    }

    @Test
    fun formatPrefixesAreNotBrands() {
        StoreDeduplicator.learnFromNames(listOf("Iper Triscount", "Iper La grande i", "Iper"))
        StoreDeduplicator.registerBrands(listOf(BrandRecord("Iper Triscount", "Iper")))
        assertFalse(StoreDeduplicator.belongsToBrand("Iper Triscount", "Iper La grande i"))
        assertTrue(StoreDeduplicator.isGenericName("Iper"))
        assertEquals(listOf("triscount"), BrandDirectory.brandTokens("Iper Triscount"))
    }

    @Test
    fun brandsSharingTheOfficialSiteAreOneRetailer() {
        StoreDeduplicator.registerBrands(listOf(BrandRecord("Oasi", "Oasi"), BrandRecord("Tigre", "Tigre")))
        StoreDeduplicator.registerSiteGroups(mapOf("oasi" to "www.oasitigre.it", "tigre" to "www.oasitigre.it"))
        assertEquals("Oasi Tigre", StoreDeduplicator.brandDisplayName("Tigre"))
        assertTrue(StoreDeduplicator.belongsToBrand("Oasi", "Tigre"))
    }
}
