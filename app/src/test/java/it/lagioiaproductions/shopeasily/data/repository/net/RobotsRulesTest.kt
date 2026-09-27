package it.lagioiaproductions.shopeasily.data.repository.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotsRulesTest {
    @Test
    fun disallowForOtherBotsDoesNotBlockUs() {
        val robots = """
            User-agent: GPTBot
            Disallow: /

            User-agent: *
            Disallow: /wp-admin/
            Allow: /wp-admin/admin-ajax.php
        """.trimIndent()
        val rules = RobotsRules.parse(robots, "shopeasily")
        assertTrue(rules.allows("/prodotti/latte"))
        assertFalse(rules.allows("/wp-admin/options.php"))
        assertTrue(rules.allows("/wp-admin/admin-ajax.php"))
    }

    @Test
    fun wildcardDisallowApplies() {
        val rules = RobotsRules.parse("User-agent: *\nDisallow: /*?add-to-cart=", "shopeasily")
        assertFalse(rules.allows("/shop/?add-to-cart=12"))
        assertTrue(rules.allows("/shop/"))
    }

    @Test
    fun specificGroupWins() {
        val rules = RobotsRules.parse("User-agent: *\nDisallow: /\n\nUser-agent: ShopEasily\nAllow: /", "shopeasily")
        assertTrue(rules.allows("/offerte"))
    }

    @Test
    fun httpIsUpgradedToHttps() {
        assertTrue(HttpFetcher.secureUrl("http://bottega.it/shop") == "https://bottega.it/shop")
        assertTrue(HttpFetcher.secureUrl("www.bottega.it") == "https://www.bottega.it")
    }
}
