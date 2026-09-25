package it.lagioiaproductions.shopeasily.data.repository.parsers

import java.util.Locale

data class ParsedOfferText(val productName: String, val price: Double)

object OfferTextParser {
    fun parse(lines: List<String>): List<ParsedOfferText> = lines.mapIndexedNotNull { index, line ->
        val match = PRICE.find(line) ?: return@mapIndexedNotNull null
        val price = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapIndexedNotNull null
        if (price <= 0.0 || price > MAX_REASONABLE_PRICE) return@mapIndexedNotNull null
        val inline = clean(line.replace(match.value, ""))
        val previous = clean(lines.getOrNull(index - 1).orEmpty())
        val name = sequenceOf(inline, previous).firstOrNull(::looksLikeProductName)
            ?: return@mapIndexedNotNull null
        ParsedOfferText(name, price)
    }.distinctBy { it.productName.lowercase(Locale.ROOT) to it.price }

    fun looksLikeProductName(value: String): Boolean {
        if (value.length !in 3..100 || value.count(Char::isLetter) < 3) return false
        if (value.split(' ').size > 12 || value.contains('€')) return false
        if (BLOCKED.any { value.contains(it, ignoreCase = true) } || IVA_WORD.containsMatchIn(value)) return false
        val firstLetter = value.firstOrNull(Char::isLetter) ?: return false
        return firstLetter.isUpperCase() || value == value.uppercase(Locale.ROOT)
    }

    private fun clean(value: String): String = value
        .replace(Regex("""^[\s•·*–—:;,.-]+|[\s•·*–—:;,.-]+$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private const val MAX_REASONABLE_PRICE = 500.0
    private val PRICE = Regex("""(?:€\s*)?(\d{1,4}[,.]\d{2})(?:\s*€)?""")
    private val BLOCKED = listOf(
        "consegna", "ordine", "ordini", "importo", "gratuita", "gratuito", "fascia oraria",
        "giorno successivo", "spesa minima", "pagamento", "servizio", "condizioni", "fino ad un",
        "entro le ore", "per gli ordini", "dal lunedì", "dal lunedi",
    )
    private val IVA_WORD = Regex("""\biva\b""", RegexOption.IGNORE_CASE)
}
