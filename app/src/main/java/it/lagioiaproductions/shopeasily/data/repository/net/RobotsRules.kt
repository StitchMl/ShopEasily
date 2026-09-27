package it.lagioiaproductions.shopeasily.data.repository.net

import java.util.Locale

/**
 * Minimal robots.txt interpreter (RFC 9309).
 *
 * The previous check collected every `Disallow:` line of the file regardless of
 * the `User-agent` group. Many small shop sites contain `Disallow: /` only for
 * specific bots (GPTBot, AhrefsBot…), so ShopEasily believed it was blocked
 * everywhere and never read their prices. Now only the group matching our
 * agent (or `*`) applies, and `Allow` rules win when more specific.
 */
class RobotsRules private constructor(private val rules: List<Rule>) {
    private data class Rule(val allow: Boolean, val path: String)

    fun allows(path: String): Boolean {
        val target = path.ifBlank { "/" }
        val match = rules.filter { it.path.isNotEmpty() && matches(it.path, target) }
            .maxWithOrNull(compareBy<Rule> { it.path.length }.thenBy { it.allow })
        return match?.allow ?: true
    }

    private fun matches(pattern: String, path: String): Boolean {
        if (!pattern.contains('*') && !pattern.endsWith('$')) return path.startsWith(pattern)
        val regex = buildString {
            append('^')
            pattern.forEachIndexed { index, char ->
                when {
                    char == '*' -> append(".*")
                    char == '$' && index == pattern.lastIndex -> append('$')
                    else -> append(Regex.escape(char.toString()))
                }
            }
        }
        return runCatching { Regex(regex).containsMatchIn(path) }.getOrDefault(true)
    }

    companion object {
        val ALLOW_ALL = RobotsRules(emptyList())

        fun parse(content: String, agent: String): RobotsRules {
            val ourAgent = agent.lowercase(Locale.ROOT)
            val specific = mutableListOf<Rule>()
            val generic = mutableListOf<Rule>()
            var groupAgents = mutableListOf<String>()
            var lastWasAgent = false
            content.lineSequence().forEach { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEach
                val key = line.substringBefore(':').trim().lowercase(Locale.ROOT)
                val value = line.substringAfter(':', "").trim()
                when (key) {
                    "user-agent" -> {
                        if (!lastWasAgent) groupAgents = mutableListOf()
                        groupAgents += value.lowercase(Locale.ROOT)
                        lastWasAgent = true
                    }
                    "allow", "disallow" -> {
                        lastWasAgent = false
                        val rule = Rule(allow = key == "allow", path = value)
                        if (groupAgents.any { it != "*" && ourAgent.contains(it) }) specific += rule
                        else if ("*" in groupAgents) generic += rule
                    }
                    else -> lastWasAgent = false
                }
            }
            return RobotsRules(if (specific.isNotEmpty()) specific else generic)
        }
    }
}
