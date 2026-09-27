package it.lagioiaproductions.shopeasily.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Matches a free-text shopping item ("pomodori", "latte bio") against a
 * product title. Every meaningful token of the request must be the prefix of
 * a word of the product, after removing accents and the final vowel of
 * Italian singular/plural forms ("mela" ↔ "mele", "pomodoro" ↔ "pomodori").
 * This avoids the old substring matching where "riso" matched "sorriso".
 */
object ProductMatcher {
    private val stopWords = setOf(
        "di", "da", "del", "della", "dei", "delle", "per", "con", "il", "la", "lo", "gli", "le",
        "un", "una", "uno", "al", "allo", "alla", "ai", "e", "ed", "in", "a",
    )

    fun matches(productName: String, requested: String): Boolean {
        val wanted = tokens(requested)
        if (wanted.isEmpty()) return false
        val words = tokens(productName)
        if (words.isEmpty()) return false
        return wanted.all { token -> words.any { word -> word.startsWith(token) } }
    }

    /** Stable key used to group equivalent requests and product names. */
    fun key(value: String): String = tokens(value).joinToString(" ")

    fun tokens(value: String): List<String> = normalize(value)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 && it !in stopWords }
        .map(::stem)

    private fun stem(token: String): String =
        if (token.length >= 4 && token.last() in "aeiou") token.dropLast(1) else token

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
}
