package com.prism.tva.core

import java.text.Normalizer

/** Text normalisation and fuzzy matching shared by the recorder, generaliser and resolver. */
object Text {
    private val PUA = Regex("[\\uE000-\\uF8FF]") // icon-font glyphs such as Zomato's ""
    private val QUOTES = Regex("[’‘'`´]")
    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}₹ ]+") // keeps Devanagari vowel signs (\p{M})
    private val SPACES = Regex("\\s+")
    private val NUMBERS = Regex("₹\\s*[0-9][0-9.,]*|[0-9][0-9.,]*")

    /** Human-readable label: icon glyphs removed, whitespace collapsed. */
    fun clean(s: String?): String =
        s?.replace(PUA, " ")?.replace(SPACES, " ")?.trim().orEmpty()

    /** Comparison form: "Domino's Pizza" -> "dominos pizza". */
    fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val t = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
            .replace(PUA, " ").replace(QUOTES, "").replace(NON_WORD, " ")
        return t.replace(SPACES, " ").trim()
    }

    /** norm() without numbers and prices, for labels like "Add item ₹112" or "1 item added". */
    fun stable(s: String?): String =
        norm(s).replace(NUMBERS, " ").replace("₹", " ").replace(SPACES, " ").trim()

    fun tokens(s: String?): List<String> = norm(s).split(' ').filter { it.isNotEmpty() }

    /** stable() as a set of crudely stemmed words: "Cart 2 items Tab 4 of 6" ~ "Cart 1 item Tab 4 of 6". */
    fun looseTokens(s: String?): Set<String> =
        stable(s).split(' ').filter { it.isNotEmpty() }
            .map { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }.toSet()

    /** Share of words two labels have in common (0..1), ignoring numbers and plurals. */
    fun overlap(a: String?, b: String?): Double {
        val x = looseTokens(a)
        val y = looseTokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / maxOf(x.size, y.size)
    }

    fun lev(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[n]
    }

    /** 0..1 similarity of the normalised strings. */
    fun sim(a: String?, b: String?): Double {
        val x = norm(a).take(120)
        val y = norm(b).take(120)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        return 1.0 - lev(x, y).toDouble() / maxOf(x.length, y.length)
    }

    /**
     * True when [needle] appears in [hay]: as a substring, with spaces squashed ("domino s" / "dominos"),
     * or token by token allowing small typos and prefixes ("domino" ~ "dominos").
     */
    fun fuzzyContains(hay: String?, needle: String?): Boolean {
        val h = norm(hay)
        val n = norm(needle)
        if (h.isEmpty() || n.isEmpty()) return false
        if (h.contains(n)) return true
        val hs = h.replace(" ", "")
        val ns = n.replace(" ", "")
        if (ns.length >= 4 && hs.contains(ns)) return true
        val ht = h.split(' ')
        return n.split(' ').all { t ->
            ht.any { w ->
                w == t ||
                    (t.length >= 4 && w.length >= 4 && (w.startsWith(t) || t.startsWith(w) || sim(w, t) >= 0.8))
            }
        }
    }
}
