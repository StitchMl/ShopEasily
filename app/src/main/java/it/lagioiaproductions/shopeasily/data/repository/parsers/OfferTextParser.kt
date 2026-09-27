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
        // Flyers/OCR mix titles, slogans and prices: keep only credible grocery lines.
        if (!ProductVocabulary.isCredibleProduct(name, line)) return@mapIndexedNotNull null
        ParsedOfferText(name, price)
    }.distinctBy { it.productName.lowercase(Locale.ROOT) to it.price }

    fun looksLikeProductName(value: String): Boolean {
        if (value.length !in 3..100 || value.count(Char::isLetter) < 3) return false
        if (value.split(' ').size > 12 || value.contains('€')) return false
        if (BLOCKED.any { value.contains(it, ignoreCase = true) } || IVA_WORD.containsMatchIn(value)) return false
        // Long letter/digit blends are typically barcodes or OCR fragments
        // (for example "PASSAELEGarR0"), not product descriptions.
        if (value.split(Regex("[\\s/_.-]+"))
                .any { token -> token.length >= 7 && token.any(Char::isDigit) && token.any(Char::isLetter) }
        ) return false
        if (value.split(Regex("[\\s/_.-]+")).any { token ->
                token.length >= 8 && token.zipWithNext().count { (left, right) ->
                    left.isLetter() && right.isLetter() && left.isUpperCase() != right.isUpperCase()
                } >= 3
            }
        ) return false
        if (value.count { it == '|' || it == '\\' || it == '[' || it == ']' } > 0) return false
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
        // Website boilerplate that sits next to numbers (VAT ids, thresholds, dates):
        // "Privacy policy", "Spedizione gratuita sopra 50,00 €"…
        "privacy", "cookie", "policy", "termini d", "termini e", "informativa", "copyright", "p.iva", "p. iva",
        "partita iva", "diritti riservati", "all rights", "newsletter", "iscriviti", "registrati", "accedi",
        "login", "il mio account", "carrello", "checkout", "spedizion", "resi e", "rimborso", "contattaci",
        "contatti", "chi siamo", "lavora con noi", "punti vendita", "trova il negozio", "scarica l", "app store",
        "google play", "seguici", "facebook", "instagram", "sfoglia", "scopri di", "leggi tutto", "clicca",
        "regolamento", "concorso", "gift card", "carta regalo", "codice sconto", "coupon", "raccolta punti",
        "catalogo premi", "orari di apertura", "faq", "assistenza clienti", "servizio clienti", "mappa del sito",
        "sitemap", "powered by", "metodi di pagamento", "sede legale", "capitale sociale", "rea ",
    )
    private val IVA_WORD = Regex("""\biva\b""", RegexOption.IGNORE_CASE)
}
