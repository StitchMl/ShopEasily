package it.lagioiaproductions.shopeasily.data.repository.parsers

import java.text.Normalizer
import java.util.Locale

/**
 * Generic Italian grocery vocabulary (word stems), used to tell a product title
 * from website text when a page has no structured product data.
 */
object ProductVocabulary {
    private val stems = listOf(
        "latte", "yogurt", "formagg", "mozzarel", "parmigian", "grana", "ricott", "burro", "panna", "uov",
        "pasta", "spaghett", "penne", "fusill", "riso", "farina", "pane", "biscott", "fette", "cracker", "grissin",
        "cereal", "merendin", "cornett", "brioche", "torta", "dolc", "cioccolat", "nutella", "marmellat", "miele",
        "zucchero", "sale", "olio", "aceto", "passata", "pelati", "pomodor", "sugo", "pesto", "ragu", "maionese",
        "tonno", "salmone", "merluzz", "pesce", "gamber", "vongol", "carne", "pollo", "tacchin", "manzo", "bovin",
        "maiale", "suino", "vitell", "salsicc", "hamburger", "prosciutt", "salame", "mortadell", "bresaola",
        "speck", "wurstel", "affettat", "frutta", "mela", "mele", "pera", "pere", "banan", "aranc", "limon", "mandarin", "uva",
        "fragol", "kiwi", "pesch", "albicocc", "melon", "anguri", "ananas", "verdur", "insalat", "lattug",
        "zucchin", "melanzan", "peperon", "patat", "carot", "cipoll", "aglio", "finocch", "carciof", "broccol",
        "cavol", "spinac", "funghi", "legum", "fagiol", "ceci", "lenticch", "piselli", "surgelat", "gelat",
        "acqua", "birra", "vino", "succo", "spremut", "bibita", "aranciata", "cola", "tisana",
        "caffe", "caffè", "capsul", "cialde", "detersiv", "ammorbident", "candeggin", "sapone", "shampoo",
        "bagnoschiuma", "dentifric", "deodorant", "carta igienica", "tovagliol", "scottex", "rotol", "pannolin",
        "assorbent", "crocchett", "croccantin", "pet food", "lettiera", "bio", "integrale", "senza glutine",
    )

    val QUANTITY = Regex("""\b\d+([.,]\d+)?\s?(g|gr|kg|ml|cl|l|lt|pz|pezzi|conf|x\s?\d+)\b""", RegexOption.IGNORE_CASE)

    /** A title is credible as a product if it names a grocery item or states a quantity. */
    fun isCredibleProduct(name: String, context: String = ""): Boolean =
        looksLikeGrocery(name) || QUANTITY.containsMatchIn(name) || QUANTITY.containsMatchIn(context)

    fun looksLikeGrocery(name: String): Boolean {
        val words = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter(String::isNotBlank)
        val text = " " + words.joinToString(" ") + " "
        return stems.any { stem ->
            val normalized = Normalizer.normalize(stem, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            if (normalized.contains(' ')) text.contains(" $normalized") else words.any { it.startsWith(normalized) && (normalized.length >= 4 || it == normalized || it.length <= normalized.length + 2) }
        }
    }
}
