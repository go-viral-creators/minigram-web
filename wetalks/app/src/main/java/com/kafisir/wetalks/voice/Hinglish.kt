package com.kafisir.wetalks.voice

import android.content.Context

/**
 * Romanised Hindi ("Hinglish") is read far better by the voice when it is written in Devanagari.
 * Common Hindi words come from a small dictionary, English words stay as they are, and everything else is converted by letter rules.
 * Words: assets/hinglish/hi_lex.tsv (Hindi) and en_words.txt (the 30,000 commonest English words).
 */
object Hinglish {
    private var lex: Map<String, String> = emptyMap()
    /** Words from the hand-made base list only: used to decide whether a text is Hinglish at all (the generated verb forms would make plain English look like Hindi). */
    private var strong: Set<String> = emptySet()
    private var en: Set<String> = emptySet()
    @Volatile private var ready = false

    fun load(c: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            try {
                val l = c.assets.open("hinglish/hi_lex.tsv").bufferedReader(Charsets.UTF_8).readLines()
                val e = c.assets.open("hinglish/en_words.txt").bufferedReader(Charsets.UTF_8).readLines()
                val x = try { c.assets.open("hinglish/hi_lex_extra.tsv").bufferedReader(Charsets.UTF_8).readLines() } catch (_: Exception) { emptyList() }
                set(l, e, x)
            } catch (_: Exception) { /* without the lists the text is simply read as it is */ }
        }
    }

    fun set(lexLines: List<String>, enWords: Collection<String>, extraLines: List<String> = emptyList()) {
        val m = HashMap<String, String>(4096)
        for (ln in extraLines) { val i = ln.indexOf('\t'); if (i > 0) m[ln.substring(0, i)] = ln.substring(i + 1).trim() }
        val base = HashSet<String>(1024)
        for (ln in lexLines) { val i = ln.indexOf('\t'); if (i > 0) { m[ln.substring(0, i)] = ln.substring(i + 1).trim(); base.add(ln.substring(0, i)) } }   // the base list wins over generated forms
        strong = base
        lex = m; en = enWords.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet(); ready = true
    }

    private val CONS = listOf("chh" to "छ", "ksh" to "क्ष", "kh" to "ख", "gh" to "घ", "ch" to "च", "jh" to "झ", "th" to "थ", "dh" to "ध", "ph" to "फ", "bh" to "भ", "sh" to "श",
        "k" to "क", "g" to "ग", "c" to "क", "j" to "ज", "t" to "त", "d" to "द", "n" to "न", "p" to "प", "f" to "फ", "b" to "ब", "m" to "म", "y" to "य", "r" to "र",
        "l" to "ल", "v" to "व", "w" to "व", "s" to "स", "h" to "ह", "z" to "ज़", "x" to "क्स", "q" to "क")
    private class V(val key: String, val ind: String, val mat: String)
    private val VOW = listOf(V("aa", "आ", "ा"), V("ee", "ई", "ी"), V("ii", "ई", "ी"), V("oo", "ऊ", "ू"), V("uu", "ऊ", "ू"), V("ai", "ऐ", "ै"), V("au", "औ", "ौ"),
        V("a", "अ", ""), V("i", "इ", "ि"), V("u", "उ", "ु"), V("e", "ए", "े"), V("o", "ओ", "ो"))
    private const val HALANT = "्"
    private val NO_NASAL_BEFORE = setOf("न", "म", "य", "ह")
    private val LABIAL = setOf("प", "ब", "भ", "फ")
    private const val VOWELS = "aeiou"

    private class Tok(val cons: Boolean, val text: String, val v: V? = null)

    /** Letter-by-letter conversion of one lower-case word. */
    fun translit(w0: String): String {
        var w = w0
        // milkar, dekhkar, sochkar: the verb stem plus कर, with the short a kept (no conjunct like मिल्कर)
        if (w.length >= 6 && w.endsWith("kar") && w[w.length - 4] !in VOWELS) return translit(w.dropLast(3)) + "कर"
        // karun, chalun / karoon: a final -un or -oon is the nasal long u (करूँ)
        if (w.length >= 5 && w.endsWith("oon")) return translit(w.dropLast(3)) + "ूँ"
        if (w.length >= 4 && w.endsWith("un") && w[w.length - 3] !in VOWELS) return translit(w.dropLast(2)) + "ूँ"
        // banaya, dikhaya, banayi, banaye, banayega: the vowel before -ya / -yi / -ye is long (बनाया, not बनया)
        if (w.length >= 5 && Regex("[^aeiou]a(ya|yi|ye)(g[ai]|nge)?$").containsMatchIn(w)) w = Regex("([^aeiou])a(y)").replace(w) { it.groupValues[1] + "aa" + it.groupValues[2] }
        val toks = ArrayList<Tok>(); var i = 0
        while (i < w.length) {
            val c = CONS.firstOrNull { w.startsWith(it.first, i) }
            if (c != null) { toks.add(Tok(true, c.second)); i += c.first.length; continue }
            val v = VOW.firstOrNull { w.startsWith(it.key, i) }
            if (v != null) { toks.add(Tok(false, v.ind, v)); i += v.key.length } else i++
        }
        val out = StringBuilder(); val n = toks.size; var j = 0
        while (j < n) {
            val t = toks[j]
            if (!t.cons) { out.append(t.text); j++; continue }
            val nxt = if (j + 1 < n) toks[j + 1] else null
            // n / m inside a word before another consonant is the nasal dot (pahunch -> पहुंच)
            if (j > 0 && nxt != null && nxt.cons && ((t.text == "न" && nxt.text !in NO_NASAL_BEFORE) || (t.text == "म" && nxt.text in LABIAL))) { out.append("ं"); j++; continue }
            out.append(t.text)
            if (nxt == null) { /* the word ends on a consonant */ }
            else if (!nxt.cons) {
                val v = nxt.v!!
                var mat = v.mat; val last = (j + 2 == n)
                if (v.key == "a" && last && (j > 0 || n == 2)) mat = "ा"          // final -a is long
                else if (v.key == "i" && last) mat = "ी"              // final -i is long
                out.append(mat); j++
            } else {
                // verb endings (kar-na, chal-ti, reh-te): the sound after the stem is kept, so no conjunct
                val ve = j + 3 == n && !toks[j + 2].cons && toks[j + 2].v!!.key in setOf("a", "i", "e") && nxt.text in setOf("त", "न", "ग")
                if (!ve) out.append(HALANT)
            }
            j++
        }
        return out.toString()
    }

    fun convertWord(w: String): String {
        val lw = w.lowercase()
        if (lw.length < 2) return w
        lex[lw]?.let { return it }
        if (lw in en) return w
        return translit(lw)
    }

    private val WORD = Regex("[A-Za-z']+")
    fun toDevanagari(text: String): String = WORD.replace(text) { convertWord(it.value.replace("'", "")) }

    /**
     * Mixed text, split into pieces that each use the right voice: Hindi words (romanised ones are converted to Devanagari) for the Hindi voice,
     * real English words for the English voice. "Bhai ek problem ho gayi" -> [hi "भाई एक"] [en "problem"] [hi "हो गयी"]. Spaces and punctuation follow the piece before them.
     */
    fun segments(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun add(l: String, t: String) { if (t.isEmpty()) return; if (out.isNotEmpty() && out.last().first == l) out[out.lastIndex] = l to (out.last().second + t) else out.add(l to t) }
        for (m in Regex("[A-Za-z']+|[^A-Za-z']+").findAll(text)) {
            val tok = m.value
            val cur = out.lastOrNull()?.first ?: "hi"
            if (tok[0].code < 128 && (tok[0].isLetter() || tok[0] == '\'')) {
                val lw = tok.lowercase().replace("'", "")
                when {
                    lw.length < 2 -> add(cur, tok)
                    lex.containsKey(lw) -> add("hi", lex.getValue(lw))
                    lw in en -> add("en", tok)
                    else -> add("hi", translit(lw))
                }
            } else add(if (tok.any { it.code in 0x0900..0x097F }) "hi" else cur, tok)
        }
        return out.filter { it.second.isNotBlank() }
    }

    private val SOFT = setOf("to", "do", "ho", "par", "kar", "ab", "main", "se", "ke", "ki", "ka", "ko", "ne", "pe", "na", "ya", "mat", "bas", "tab", "sab", "din", "kam", "bhi", "toh", "aur", "ek")
    /** True when the text is romanised Hindi (several Hindi words), never plain English. */
    fun looksHinglish(text: String): Boolean {
        val ws = Regex("[A-Za-z]+").findAll(text).map { it.value.lowercase() }.toList()
        if (ws.size < 3) return false
        val hits = ws.count { it in strong && it !in SOFT }
        val soft = ws.count { it in strong } - hits
        return hits >= 2 || (hits >= 1 && hits + soft >= 4) || (hits + soft) * 100 / ws.size >= 45
    }
}
