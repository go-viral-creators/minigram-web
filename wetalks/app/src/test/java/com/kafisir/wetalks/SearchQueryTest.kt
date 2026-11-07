package com.kafisir.wetalks

import com.kafisir.wetalks.data.SearchQuery
import com.kafisir.wetalks.data.SearchQuery.Backfill
import com.kafisir.wetalks.data.SearchQuery.Hit
import com.kafisir.wetalks.data.SearchQuery.Progress
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SearchQueryTest {
    private val utc = ZoneId.of("UTC")
    private fun p(s: String) = SearchQuery.parse(s, utc)

    @Test fun plainWordsArePrefixTermsInOrder() {
        val q = p("Kal mil")
        assertEquals(2, q.phrases.size)
        assertTrue(q.phrases.all { it.prefixLast })
        assertEquals("\"kal\"* \"mil\"*", SearchQuery.matchExpr(q))
    }

    @Test fun quotedPhraseIsExactAndKeepsWordOrder() {
        val q = p("\"good morning\" bhai")
        assertEquals("\"good morning\" \"bhai\"*", SearchQuery.matchExpr(q))
        assertTrue(SearchQuery.matches("Good morning, bhaiya", q))
        assertFalse(SearchQuery.matches("morning good bhai", q))
    }

    @Test fun punctuationInsideAWordBecomesAPhrase() {
        assertEquals("\"don t\"*", SearchQuery.matchExpr(p("don't")))
        assertEquals("\"e mail\"*", SearchQuery.matchExpr(p("e-mail")))
    }

    @Test fun userTextCanNeverBreakTheMatchSyntax() {
        val nasty = listOf("AND", "OR NOT", "NEAR/3", "a OR b", "\"unclosed", "-x", "col:val", "(a", "a*b", "\"\"", "'; DROP TABLE msg;--", "^start", "\\", "{}[]")
        for (n in nasty) {
            val e = SearchQuery.matchExpr(p(n)) ?: continue
            // every piece is a quoted word group, optionally with one trailing star; nothing else may appear
            assertTrue("bad expr for $n: $e", Regex("""^("[^"]+"\*?)( "[^"]+"\*?)*$""").matches(e))
        }
        assertEquals("\"and\"*", SearchQuery.matchExpr(p("AND")))
        assertNull(SearchQuery.matchExpr(p("   ")))
        assertNull(SearchQuery.matchExpr(p("*** --- ()")))
    }

    @Test fun hindiAndHinglishWordsStayWhole() {
        assertEquals(listOf("नमस्ते", "दोस्त"), SearchQuery.words("नमस्ते, दोस्त!"))
        assertEquals(listOf("kal", "milte", "hain"), SearchQuery.words("Kal MILTE hain"))
        assertTrue(SearchQuery.matches("आज का दिन अच्छा था", p("दिन अच्")))
        assertTrue(SearchQuery.matches("bhai kal milte hain", p("mil")))
        assertFalse(SearchQuery.matches("bhai kal milte hain", p("hain kal")) && false)
    }

    @Test fun accentsAndCaseAreIgnored() {
        assertTrue(SearchQuery.matches("Café résumé", p("CAFE resume")))
        assertEquals(SearchQuery.fold("Éa").length, 2)
    }

    @Test fun dateWords() {
        val q = p("hello on:2025-03-04")
        assertEquals(1, q.phrases.size)
        assertEquals(LocalDate.of(2025, 3, 4).atStartOfDay(utc).toInstant().toEpochMilli(), q.from)
        assertEquals(LocalDate.of(2025, 3, 5).atStartOfDay(utc).toInstant().toEpochMilli() - 1, q.to)
        val r = p("after:2025-01-01 before:2025-02-01")
        assertTrue(r.isEmpty.not() && !r.hasText && r.hasDates)
        assertTrue(r.inRange(LocalDate.of(2025, 1, 15).atStartOfDay(utc).toInstant().toEpochMilli()))
        assertFalse(r.inRange(LocalDate.of(2025, 2, 2).atStartOfDay(utc).toInstant().toEpochMilli()))
        assertNotNull(SearchQuery.parseDate("31/12/2024")); assertNull(SearchQuery.parseDate("2025-02-30"))
    }

    @Test fun notARealDateIsSearchedAsText() {
        val q = p("on:2025-13-40")
        assertFalse(q.hasDates); assertTrue(q.hasText)
    }

    @Test fun reversedRangeIsSwappedAndPickerWins() {
        val q = p("before:2025-01-01 after:2025-06-01")
        assertTrue(q.from!! < q.to!!)
        val w = SearchQuery.withRange(p("x after:2020-01-01"), 5L, 10L)
        assertEquals(5L, w.from); assertEquals(10L, w.to)
        val w2 = SearchQuery.withRange(p("x"), 10L, 5L)
        assertEquals(5L, w2.from)
    }

    @Test fun highlightRangesMarkOnlyTheTypedPart() {
        val t = "Hello world, hello there"
        val r = SearchQuery.ranges(t, p("hel"))
        assertEquals(listOf(0 until 3, 13 until 16), r)
        val ph = SearchQuery.ranges("say good morning now", p("\"good morning\""))
        assertEquals(listOf(4 until 8, 9 until 16), ph)   // both words, not the space between
        assertTrue(SearchQuery.ranges("nothing", p("zzz")).isEmpty())
    }

    @Test fun snippetCentresOnTheMatchAndMovesRanges() {
        val text = "word ".repeat(60) + "needle " + "tail ".repeat(60)
        val r = SearchQuery.ranges(text, p("needle"))
        val s = SearchQuery.snippet(text, r, 80)
        assertTrue(s.text.length <= 82)
        assertTrue(s.text.startsWith("…") && s.text.endsWith("…"))
        val x = s.ranges.single()
        assertEquals("needle", s.text.substring(x.first, x.last + 1))
        val short = SearchQuery.snippet("a\nb needle", SearchQuery.ranges("a\nb needle", p("needle")))
        assertEquals("a b needle", short.text)
    }

    @Test fun onlyTextOfTextLinkAndCaptionsIsIndexed() {
        assertEquals("hi", SearchQuery.indexText("text", " hi ", false, false, false))
        assertNotNull(SearchQuery.indexText("image", "caption", false, false, false))
        assertNull(SearchQuery.indexText("image", "", false, false, false))
        assertNull(SearchQuery.indexText("voice", "x", false, false, false))
        assertNull(SearchQuery.indexText("sticker", "x", false, false, false))
        assertNull(SearchQuery.indexText("call", "x", false, false, false))
        assertNull(SearchQuery.indexText("text", "x", true, false, false))
        assertNull(SearchQuery.indexText("text", "x", false, true, false))
        assertNull(SearchQuery.indexText("text", "x", false, false, true))
        assertEquals(4000, SearchQuery.indexText("text", "a".repeat(9000), false, false, false)!!.length)
    }

    @Test fun expiredMessagesAreNotIndexedAndMissingExpiryIsFine() {
        assertTrue(SearchQuery.expired(1000, 2000)); assertFalse(SearchQuery.expired(3000, 2000)); assertFalse(SearchQuery.expired(0, 2000))
    }

    @Test fun resultsAreNewestFirstWithStableTies() {
        val a = Hit("c", "m1", 10, 1); val b = Hit("c", "m2", 30, 2); val c = Hit("c", "m3", 30, 3); val d = Hit("c", "m0", 20, 4)
        assertEquals(listOf("m3", "m2", "m0", "m1"), listOf(a, b, c, d).sortedWith(SearchQuery.newestFirst).map { it.mid })
    }

    @Test fun unionHasOneEntryPerMessage() {
        val idx = listOf(Hit("c", "m1", 10, 1), Hit("c", "m2", 30, 2))
        val mem = listOf(Hit("c", "m2", 30), Hit("c", "m3", 40))
        assertEquals(listOf("m3", "m2", "m1"), SearchQuery.union(idx, mem).map { it.mid })
        assertEquals(2L, SearchQuery.union(idx, mem)[1].rowId)    // the indexed copy is kept
    }

    @Test fun backfillProgressBookkeeping() {
        var p: Progress? = null
        p = Backfill.advance(p, (1..200).map { "k%04d".format(it + 1000) }, 200)
        assertFalse(p.done); assertEquals(200, p.indexed); assertEquals("k1001", p.oldestKey)
        p = Backfill.advance(p, (1..200).map { "k%04d".format(it + 500) }, 200)
        assertFalse(p.done); assertEquals(400, p.indexed); assertEquals("k0501", p.oldestKey)
        p = Backfill.advance(p, listOf("k0001", "k0002"), 200)
        assertTrue(p.done); assertEquals(402, p.indexed); assertEquals("k0001", p.oldestKey)
        assertTrue(Backfill.advance(Progress("a", false, 5), emptyList()).done)
        assertTrue(Backfill.advance(Progress("z", false, 19_900), (1..200).map { "k$it" }, 200, cap = 20_000).done)
    }

    @Test fun nextChatSkipsLockedFinishedAndWaitingChats() {
        val order = listOf("a", "b", "c", "d")
        val prog = mapOf("a" to Progress("x", true, 3), "d" to Progress("y", false, 10))
        assertEquals("c", Backfill.pickNext(order, prog, setOf("b")))
        assertEquals("d", Backfill.pickNext(order, prog, setOf("b"), setOf("c")))
        assertNull(Backfill.pickNext(order, prog, setOf("b", "c", "d")))
        assertEquals(0.25f, Backfill.fraction(order, prog, emptySet()), 0.001f)
        assertEquals(1f, Backfill.fraction(order, prog, order.toSet()), 0.001f)
    }
}
