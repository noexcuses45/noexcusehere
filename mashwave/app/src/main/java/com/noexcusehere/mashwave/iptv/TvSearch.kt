package com.noexcusehere.mashwave.iptv

import java.util.Locale

/** Finds channels, movies and shows by name. */
object TvSearch {
    /**
     * Every word typed has to appear in the name, in any order ("sky sport" finds "UK: Sky Sports
     * Main Event"). Names that start with what was typed come first.
     */
    fun find(items: List<TvItem>, query: String, limit: Int = 150): List<TvItem> {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val whole = words.joinToString(" ")
        val first = ArrayList<TvItem>()
        val second = ArrayList<TvItem>()
        val rest = ArrayList<TvItem>()
        for (item in items) {
            val name = item.name
            if (!words.all { name.contains(it, ignoreCase = true) }) continue
            val plain = clean(name)
            when {
                plain.startsWith(whole) -> first += item
                wordStart(plain, words[0]) -> second += item
                else -> rest += item
            }
            // Enough exact starts to fill the list: no need to walk the other hundred thousand.
            if (first.size >= limit) break
        }
        return (first + second + rest).take(limit)
    }

    /** Lower case, without a leading tag such as "UK: " or "[US] " so "bbc" ranks "UK: BBC One" first. */
    private fun clean(name: String): String {
        val lower = name.lowercase(Locale.ROOT).trimStart()
        val cut = lower.indexOfFirst { it == ':' || it == '|' || it == ']' }
        return if (cut in 1..8 && cut < lower.length - 1) lower.substring(cut + 1).trimStart(' ', '-', '|', ':') else lower
    }

    private fun wordStart(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(word, from)
            if (at < 0) return false
            if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
            from = at + 1
        }
    }
}
