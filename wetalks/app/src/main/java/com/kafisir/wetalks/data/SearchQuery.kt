package com.kafisir.wetalks.data

import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure search logic (no Android): query parsing, safe full-text (FTS) query building, matching and highlight ranges for one text,
 * snippets, result ordering and back-fill bookkeeping. Everything here has JVM unit tests.
 *
 * A "word" is a run of letters, digits and combining marks, so Hindi / Devanagari (with vowel signs) and Hinglish words stay in one piece.
 * Matching ignores case and Latin accents, like the database's unicode61 tokenizer does.
 */
object SearchQuery {
    /** One search unit: words that must follow each other. Every word but the last is whole; the last one is only a start when [prefixLast]. */
    class Phrase(val words: List<String>, val prefixLast: Boolean)

    /** [from] / [to] are epoch milliseconds (start of the first day, last millisecond of the last day), or null. */
    class Parsed(val phrases: List<Phrase>, val from: Long? = null, val to: Long? = null) {
        val hasText get() = phrases.isNotEmpty()
        val hasDates get() = from != null || to != null
        val isEmpty get() = !hasText && !hasDates
        fun inRange(ts: Long) = (from == null || ts >= from) && (to == null || ts <= to)
    }

    // ---- characters and words
    private fun isWordChar(c: Char): Boolean {
        if (Character.isLetterOrDigit(c)) return true
        return when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    }

    /** Lower case and, for Latin letters, without the accent. Always exactly one char per char, so positions stay valid. */
    fun foldChar(c: Char): Char {
        val l = Character.toLowerCase(c)
        if (l.code < 0x80) return l
        if (l.code in 0xC0..0x24F || l.code in 0x1E00..0x1EFF) {
            val d = Normalizer.normalize(l.toString(), Normalizer.Form.NFD)
            if (d.isNotEmpty() && d[0].code < 0x250) return d[0]
        }
        return l
    }

    fun fold(s: String): String { val sb = StringBuilder(s.length); for (c in s) sb.append(foldChar(c)); return sb.toString() }

    /** Folded words of [text] with the position of each in the original text. */
    class Word(val text: String, val start: Int, val end: Int)

    fun wordsOf(text: String): List<Word> {
        val out = ArrayList<Word>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (!isWordChar(text[i])) { i++; continue }
            val s = i
            while (i < n && isWordChar(text[i])) i++
            out.add(Word(fold(text.substring(s, i)), s, i))
        }
        return out
    }

    /** Folded words only. */
    fun words(text: String): List<String> = wordsOf(text).map { it.text }

    // ---- parsing
    private val DATE_ISO = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
    private val DATE_DMY = Regex("""^(\d{1,2})[/.](\d{1,2})[/.](\d{4})$""")

    /** yyyy-mm-dd or dd/mm/yyyy (also dd.mm.yyyy). Null when it is not a real date. */
    fun parseDate(s: String): LocalDate? = try {
        DATE_ISO.matchEntire(s)?.let { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            ?: DATE_DMY.matchEntire(s)?.let { LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
    } catch (_: Exception) { null }

    fun dayStart(d: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    fun dayEnd(d: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    /**
     * What the person typed. "quoted words" must follow each other exactly; any other word matches words that START with it
     * ("mil" finds "milte"); a word with punctuation inside ("e-mail", "don't") is kept as a phrase.
     * Date words: after:2024-01-31  before:2024-02-15  on:2024-02-01  (also dd/mm/yyyy). A date word that is not a real date is searched as text.
     */
    fun parse(raw: String, zone: ZoneId = ZoneId.systemDefault()): Parsed {
        val phrases = ArrayList<Phrase>()
        var from: Long? = null; var to: Long? = null
        val s = raw
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c.isWhitespace()) { i++; continue }
            if (c == '"' || c == '“' || c == '”') {
                var j = i + 1
                while (j < n && s[j] != '"' && s[j] != '“' && s[j] != '”') j++
                val w = words(s.substring(i + 1, j))
                if (w.isNotEmpty()) phrases.add(Phrase(w, false))
                i = j + 1
                continue
            }
            var j = i
            while (j < n && !s[j].isWhitespace() && s[j] != '"') j++
            val tok = s.substring(i, j)
            i = j
            val colon = tok.indexOf(':')
            if (colon > 0) {
                val key = tok.substring(0, colon).lowercase()
                val d = parseDate(tok.substring(colon + 1))
                if (d != null && key in DATE_KEYS) {
                    when (key) {
                        "after", "from", "since" -> from = dayStart(d, zone)
                        "before", "to", "until" -> to = dayEnd(d, zone)
                        else -> { from = dayStart(d, zone); to = dayEnd(d, zone) }
                    }
                    continue
                }
            }
            val w = words(tok)
            if (w.isNotEmpty()) phrases.add(Phrase(w, true))
        }
        if (from != null && to != null && from > to) { val t = from; from = to; to = t }
        return Parsed(phrases, from, to)
    }
    private val DATE_KEYS = setOf("after", "from", "since", "before", "to", "until", "on")

    /** The dates from the date picker win over date words typed in the box. */
    fun withRange(p: Parsed, from: Long?, to: Long?): Parsed {
        var f = from ?: p.from; var t = to ?: p.to
        if (f != null && t != null && f > t) { val x = f; f = t; t = x }
        return Parsed(p.phrases, f, t)
    }

    // ---- full-text query
    /**
     * The text for FTS "MATCH". Every word is wrapped in quotes (quotes inside are doubled), so nothing the person types
     * (AND, OR, NOT, NEAR, -, *, :, parentheses, quotes) can change the meaning of the query or make it fail. Null when there is nothing to find.
     */
    fun matchExpr(p: Parsed): String? {
        if (p.phrases.isEmpty()) return null
        return p.phrases.joinToString(" ") { ph ->
            "\"" + ph.words.joinToString(" ") { it.replace("\"", "\"\"") } + "\"" + if (ph.prefixLast) "*" else ""
        }
    }

    // ---- matching one text
    private fun phraseAt(ph: Phrase, w: List<Word>, at: Int): Boolean {
        if (at + ph.words.size > w.size) return false
        for (k in ph.words.indices) {
            val t = w[at + k].text
            val q = ph.words[k]
            val last = k == ph.words.size - 1
            if (if (last && ph.prefixLast) !t.startsWith(q) else t != q) return false
        }
        return true
    }

    /** True when every phrase occurs in [text] (same rule the database applies). A query with no text matches everything. */
    fun matches(text: String, p: Parsed): Boolean {
        if (p.phrases.isEmpty()) return true
        val w = wordsOf(text)
        return p.phrases.all { ph -> w.indices.any { phraseAt(ph, w, it) } }
    }

    /** Character ranges (start inclusive, end exclusive) of the matches in [text], sorted and merged. A start-of-word match marks only the typed part. */
    fun ranges(text: String, p: Parsed): List<IntRange> {
        if (p.phrases.isEmpty()) return emptyList()
        val w = wordsOf(text)
        val raw = ArrayList<IntRange>()
        for (ph in p.phrases) for (at in w.indices) if (phraseAt(ph, w, at)) {
            for (k in ph.words.indices) {
                val word = w[at + k]
                val end = if (k == ph.words.size - 1 && ph.prefixLast) word.start + ph.words[k].length else word.end
                raw.add(word.start until minOf(end, word.end))
            }
        }
        raw.sortBy { it.first }
        val out = ArrayList<IntRange>()
        for (r in raw) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) { if (r.last > last.last) out[out.lastIndex] = last.first..r.last } else out.add(r)
        }
        return out
    }

    // ---- snippets
    class Snippet(val text: String, val ranges: List<IntRange>)

    /** A short piece of [text] around the first match, with "…" where it was cut and the highlight ranges moved to fit. Line breaks become spaces. */
    fun snippet(text: String, ranges: List<IntRange>, max: Int = 140): Snippet {
        val flat = text.replace('\n', ' ').replace('\r', ' ')
        if (flat.length <= max) return Snippet(flat, ranges)
        val first = ranges.firstOrNull()?.first ?: 0
        var start = (first - max / 4).coerceAtLeast(0)
        if (start > 0) { val back = flat.lastIndexOf(' ', start); if (back >= 0 && start - back <= 12) start = back + 1 }
        var end = (start + max).coerceAtMost(flat.length)
        if (end < flat.length) { val sp = flat.lastIndexOf(' ', end); if (sp > start + max / 2) end = sp }
        val pre = if (start > 0) "…" else ""
        val post = if (end < flat.length) "…" else ""
        val shifted = ranges.mapNotNull { r ->
            val a = maxOf(r.first, start); val b = minOf(r.last + 1, end)
            if (a < b) (a - start + pre.length) until (b - start + pre.length) else null
        }
        return Snippet(pre + flat.substring(start, end) + post, shifted)
    }

    // ---- what goes into the index
    /** The text of a message that may be indexed, or null. Only the words of text / link messages and captions; never media, calls, stickers. */
    fun indexText(type: String, text: String, deleted: Boolean, unreadable: Boolean, pending: Boolean): String? {
        if (deleted || unreadable || pending) return null
        if (type != "text" && type != "link" && type != "image" && type != "video" && type != "audio" && type != "file") return null
        val t = text.trim()
        return if (t.isEmpty()) null else if (t.length > 4000) t.substring(0, 4000) else t
    }

    /** A message with an expiry time in the past is never indexed. [exp] is epoch ms, 0 or missing means "never expires". */
    fun expired(exp: Long, now: Long) = exp in 1..now

    // ---- result order
    class Hit(val chat: String, val mid: String, val ts: Long, val rowId: Long = 0, val from: String = "")

    /** Newest first; messages with the same time keep a stable order. */
    val newestFirst: Comparator<Hit> = compareByDescending<Hit> { it.ts }.thenByDescending { it.rowId }.thenByDescending { it.mid }

    /** Merges hits from the index and from the messages on screen: one entry per message, newest first. */
    fun union(a: List<Hit>, b: List<Hit>): List<Hit> {
        val m = LinkedHashMap<String, Hit>()
        for (h in a) m[h.chat + "/" + h.mid] = h
        for (h in b) m.putIfAbsent(h.chat + "/" + h.mid, h)
        return m.values.sortedWith(newestFirst)
    }

    /** Keyset paging: where the next page starts (after the last row shown). */
    class Cursor(val ts: Long, val rowId: Long)

    // ---- back-fill bookkeeping
    /** How far one chat's history has been read into the index. [oldestKey] = oldest message key read so far. */
    class Progress(val oldestKey: String?, val done: Boolean, val indexed: Int)

    object Backfill {
        const val PAGE = 200
        const val CAP_PER_CHAT = 20_000

        /** The state after reading one page ([keys] = message keys of the page, any order). */
        fun advance(p: Progress?, keys: List<String>, requested: Int = PAGE, cap: Int = CAP_PER_CHAT): Progress {
            val before = p ?: Progress(null, false, 0)
            if (keys.isEmpty()) return Progress(before.oldestKey, true, before.indexed)
            val oldest = (keys + listOfNotNull(before.oldestKey)).min()
            val total = before.indexed + keys.size
            return Progress(oldest, keys.size < requested || total >= cap, total)
        }

        /** The next chat to read: first in the given order that is not locked, not finished and not waiting out an error. */
        fun pickNext(order: List<String>, progress: Map<String, Progress>, locked: Set<String>, skip: Set<String> = emptySet()): String? =
            order.firstOrNull { it !in locked && it !in skip && progress[it]?.done != true }

        /** 0..1 over all given chats (finished chats count as complete). */
        fun fraction(order: List<String>, progress: Map<String, Progress>, locked: Set<String>): Float {
            val ids = order.filter { it !in locked }
            if (ids.isEmpty()) return 1f
            return ids.count { progress[it]?.done == true }.toFloat() / ids.size
        }
    }
}
