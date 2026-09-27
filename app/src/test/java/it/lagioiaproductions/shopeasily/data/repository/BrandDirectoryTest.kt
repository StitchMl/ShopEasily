package it.lagioiaproductions.shopeasily.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrandDirectoryTest {
    @Test
    fun picksRetailEntityFromWikidataSearch() {
        val json = """{"search":[
            {"id":"Q1","label":"Elite","description":"film del 2019"},
            {"id":"Q2","label":"Elite Supermercati","description":"catena di supermercati italiana"}
        ]}"""
        assertEquals("Q2", BrandDirectory.pickRetailEntity(json, "Elite"))
        assertNull(BrandDirectory.pickRetailEntity(json, "Esselunga"))
    }

    @Test
    fun readsWebsiteAndLogoFromWikidataEntity() {
        val json = """{"entities":{"Q2":{"descriptions":{"it":{"value":"catena di supermercati"}},
            "claims":{"P856":[{"mainsnak":{"datavalue":{"value":"https://www.superelite.it/"}}}],
                      "P154":[{"mainsnak":{"datavalue":{"value":"Elite logo.svg"}}}]}}}}"""
        val entity = BrandDirectory.parseWikidataEntity(json, "Q2")!!
        assertEquals("https://www.superelite.it/", entity.website)
        assertEquals("Elite logo.svg", entity.logoFile)
        assertEquals(
            "https://commons.wikimedia.org/wiki/Special:FilePath/Elite_logo.svg?width=128",
            BrandDirectory.commonsThumbnail(entity.logoFile!!),
        )
    }

    @Test
    fun officialSiteMustContainTheBrandAndNotBeAnAggregator() {
        val results = listOf(
            "https://www.doveconviene.it/roma/volantino/elite",
            "https://it.wikipedia.org/wiki/Elite",
            "https://www.superelite.it/",
        )
        assertEquals("https://www.superelite.it", BrandDirectory.pickOfficialSite(results, "Elite"))
        assertNull(BrandDirectory.pickOfficialSite(listOf("https://www.example.com"), "Elite"))
        // Generic words in the shop name are ignored when matching the domain.
        assertEquals("https://www.superelite.it", BrandDirectory.pickOfficialSite(results, "Supermercato Elite"))
    }

    @Test
    fun flyerPagesAreAggregatorPagesNamingTheBrand() {
        val results = listOf(
            "https://www.doveconviene.it/roma/volantino/elite",
            "https://www.doveconviene.it/volantino/conad",
            "https://www.superelite.it/volantino",
        )
        assertEquals(listOf("https://www.doveconviene.it/roma/volantino/elite"), BrandDirectory.pickFlyerPages(results, "Elite"))
    }

    @Test
    fun shortBrandsAreStillLookedUp() {
        org.junit.Assert.assertFalse(StoreDeduplicator.isGenericName("MD"))
        org.junit.Assert.assertTrue("www.mdspa.it" in BrandDirectory.candidateDomains("MD"))
        org.junit.Assert.assertTrue("www.superelite.it" in BrandDirectory.candidateDomains("Supermercato Elite"))
    }

    @Test
    fun prefersTheItalianRetailerAndReadsParents() {
        val details = """{"entities":{
            "Q1":{"descriptions":{"it":{"value":"catena di supermercati francese"}},"claims":{"P17":[{"mainsnak":{"datavalue":{"value":{"id":"Q142"}}}}]}},
            "Q2":{"descriptions":{"it":{"value":"catena di supermercati"}},"claims":{"P17":[{"mainsnak":{"datavalue":{"value":{"id":"Q38"}}}}]}}
        }}"""
        assertEquals("Q2", BrandDirectory.pickItalianEntity(details, listOf("Q1", "Q2")))
        val brand = """{"entities":{"Q9":{"claims":{"P749":[{"mainsnak":{"datavalue":{"value":{"id":"Q3777398"}}}}]}}}}"""
        assertEquals(listOf("Q3777398"), BrandDirectory.parseWikidataEntity(brand, "Q9")!!.parents)
    }

    @Test
    fun independentShopSiteMustNameItsPlace() {
        val place = BrandDirectory.locationTokens("Via Appia Nuova 21 Roma 00183")
        assertEquals(listOf("appia", "nuova", "roma"), place)
        org.junit.Assert.assertTrue(BrandDirectory.textMentionsPlace("<p>Fresco Market, via Appia Nuova - Roma</p>", place))
        org.junit.Assert.assertFalse(BrandDirectory.textMentionsPlace("<p>Fresco Market, corso Vittorio - Bari</p>", place))
    }
}
