package com.kafisir.wetalks.voice

/**
 * How a text is cut up for reading aloud. Pure logic (no Android) so it is unit tested.
 * A text is a list of sentences; the player works in sentences (skip back / forward, resume, progress). A very long sentence is split at a comma or space.
 * The first sentence is kept short so the voice starts quickly.
 */
object ReadPlan {
    /** [sentence] is the index in the sentence list; [gapMs] is the pause after this piece (0 inside a sentence). */
    class Piece(val lang: String, val text: String, val sentence: Int, val gapMs: Int)

    private val END = Regex("(?<=[.!?।…])\\s+")

    fun sentences(s: String, max: Int = 200, first: Int = 110): List<String> {
        val out = ArrayList<String>()
        for (raw in s.trim().split(END)) {
            val t = raw.trim(); if (t.isEmpty()) continue
            val cap = if (out.isEmpty()) first else max
            if (t.length <= cap) out.add(t) else out.addAll(splitLong(t, cap, max))
        }
        return out
    }

    /** Cuts at the last comma-like mark, else the last space, before [cap]; later parts use [max]. */
    private fun splitLong(t: String, cap: Int, max: Int): List<String> {
        val out = ArrayList<String>(); var rest = t.trim(); var c = cap
        while (rest.length > c) {
            val window = rest.substring(0, c)
            var cut = maxOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(": "), window.lastIndexOf("، "))
            if (cut < c / 3) cut = window.lastIndexOf(' ')
            if (cut < c / 3) cut = c - 1
            out.add(rest.substring(0, cut + 1).trim()); rest = rest.substring(cut + 1).trim(); c = max
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /** Pause after a sentence, from how it ends. */
    fun gapAfter(sentence: String): Int = when (sentence.trimEnd().lastOrNull()) {
        '.', '!', '?', '।', '…' -> 300
        ',', ';', ':', '،' -> 140
        else -> 90
    }

    fun hasSpeech(t: String) = t.any { it.isLetterOrDigit() }

    /** Pieces for the sentences; [split] turns one sentence into (language, text) runs (mixed Hindi/English), otherwise it is one run in [lang]. */
    fun pieces(sentences: List<String>, lang: String, split: ((String) -> List<Pair<String, String>>)?): List<Piece> {
        val out = ArrayList<Piece>()
        sentences.forEachIndexed { si, s ->
            val runs = (split?.invoke(s) ?: listOf(lang to s)).filter { hasSpeech(it.second) }
            val gap = gapAfter(s)
            if (runs.isEmpty()) { if (out.isNotEmpty() && out.last().sentence == si - 1) { val p = out.removeAt(out.lastIndex); out.add(Piece(p.lang, p.text, p.sentence, maxOf(p.gapMs, gap))) }; return@forEachIndexed }
            runs.forEachIndexed { k, (l, t) -> out.add(Piece(l, t.trim(), si, if (k == runs.lastIndex) gap else 0)) }
        }
        return out
    }
}
