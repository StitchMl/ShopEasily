package it.lagioiaproductions.shopeasily.domain

/** Converts a commercial title into a store-independent shopping request. */
object GeneralProductName {
    private data class Rule(val label: String, val alternatives: List<Set<String>>)
    private fun rule(label: String, vararg alternatives: String) = Rule(
        label,
        alternatives.map { ProductMatcher.tokens(it).toSet() },
    )

    private val rules = listOf(
        rule("Sale lavastoviglie", "sale lavastoviglie"),
        rule("Detergente vetri", "detergente vetri", "pulitore vetri", "quasar vetri"),
        rule("Carta da cucina", "carta cucina", "rotolo cucina", "scottex"),
        rule("Carta igienica", "carta igienica"),
        rule("Cibo per gatti", "cibo gatti", "pappa gatti", "crocchette gatti"),
        rule("Cibo per cani", "cibo cani", "pappa cane", "crocchette cani"),
        rule("Passata di pomodoro", "passata pomodoro", "passata"),
        rule("Pomodori pelati", "pomodori pelati", "pelati"),
        rule("Olio", "olio"), rule("Latte", "latte"), rule("Yogurt", "yogurt"),
        rule("Formaggio", "formaggio", "parmigiano", "grana", "pecorino", "mozzarella", "ricotta"),
        rule("Uova", "uova"),
        rule("Pasta", "pasta", "spaghetti", "penne", "fusilli", "rigatoni", "tagliatelle"),
        rule("Riso", "riso"), rule("Lenticchie", "lenticchie"), rule("Ceci", "ceci"),
        rule("Fagioli", "fagioli"), rule("Piselli", "piselli"), rule("Legumi", "legumi"),
        rule("Zuppa", "zuppa", "minestrone"), rule("Pane", "pane"),
        rule("Bretzel", "bretzel", "pretzel"), rule("Croissant", "croissant", "cornetto"),
        rule("Farina", "farina"), rule("Biscotti", "biscotti"), rule("Cereali", "cereali"),
        rule("Caffè", "caffe"), rule("Tè", "te"), rule("Acqua", "acqua"),
        rule("Birra", "birra"), rule("Vino", "vino"), rule("Succo", "succo"),
        rule("Sale", "sale"), rule("Zucchero", "zucchero"), rule("Tonno", "tonno"),
        rule("Pesce", "pesce"), rule("Carne", "carne", "pollo", "manzo", "maiale", "tacchino"),
        rule("Mele", "mela", "mele"), rule("Pere", "pera", "pere"),
        rule("Banane", "banana", "banane"), rule("Arance", "arancia", "arance"),
        rule("Limoni", "limone", "limoni"), rule("Fragole", "fragola", "fragole"),
        rule("Uva", "uva"), rule("Pesche", "pesca", "pesche"), rule("Kiwi", "kiwi"),
        rule("Pomodori", "pomodoro", "pomodori"), rule("Patate", "patata", "patate"),
        rule("Carote", "carota", "carote"), rule("Zucchine", "zucchina", "zucchine"),
        rule("Melanzane", "melanzana", "melanzane"), rule("Peperoni", "peperone", "peperoni"),
        rule("Insalata", "insalata", "lattuga"), rule("Cipolle", "cipolla", "cipolle"),
        rule("Verdura", "verdura", "ortaggi"), rule("Frutta", "frutta"),
        rule("Detersivo lavatrice", "detersivo lavatrice"),
        rule("Detersivo piatti", "detersivo piatti"), rule("Ammorbidente", "ammorbidente"),
        rule("Integratore", "integratore"),
    )

    private val quantity = Regex("\\b\\d+(?:[.,]\\d+)?\\s*(?:kg|g|l|ml|cl|pz|pezzi)\\b", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("\\s+")
    private val marketingWords = ProductMatcher.tokens(
        "classic classico piccole piccolo grande premium speciale italiano italiana toscana bio biologico " +
            "naturale fresco fresca mercato legumeria selezione qualità gusto linea",
    ).toSet()

    fun from(productName: String, storeName: String, brand: String? = null): String {
        val productTokens = ProductMatcher.tokens(productName).toSet()
        rules.firstOrNull { rule -> rule.alternatives.any(productTokens::containsAll) }
            ?.let { return it.label }

        val excluded = buildSet {
            addAll(ProductMatcher.tokens(storeName).filter { it.length >= 3 })
            addAll(brand?.let(ProductMatcher::tokens).orEmpty())
            addAll(marketingWords)
        }
        val seen = mutableSetOf<String>()
        var result = productName.split(Regex("\\s+")).filter { word ->
            val tokens = ProductMatcher.tokens(word)
            tokens.isEmpty() || (tokens.none(excluded::contains) && tokens.all(seen::add))
        }.joinToString(" ")
        result = result.replace(quantity, " ")
            .replace(Regex("[|·_-]+"), " ")
            .replace(whitespace, " ")
            .trim(' ', ',', '.', ':', ';')
        return result.ifBlank { productName.trim() }
    }
}
