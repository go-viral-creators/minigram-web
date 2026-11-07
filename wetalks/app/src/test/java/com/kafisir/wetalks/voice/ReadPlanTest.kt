package com.kafisir.wetalks.voice

import org.junit.Assert.*
import org.junit.Test

class ReadPlanTest {
    @Test fun splitsAtSentenceEnds() {
        val s = ReadPlan.sentences("Hello there. How are you? I am fine! नमस्ते। आप कैसे हैं")
        assertEquals(listOf("Hello there.", "How are you?", "I am fine!", "नमस्ते।", "आप कैसे हैं"), s)
    }

    @Test fun longSentencesAreCutAtCommasAndNothingIsLost() {
        val long = (1..120).joinToString(", ") { "word$it" } + "."
        val s = ReadPlan.sentences(long)
        assertTrue(s.size > 3)
        assertTrue(s.first().length <= 110); assertTrue(s.all { it.length <= 200 })
        assertEquals(long.replace(Regex("\\s+"), ""), s.joinToString("").replace(Regex("\\s+"), ""))
    }

    @Test fun aSpaceLessWallOfTextStillGetsCut() {
        val s = ReadPlan.sentences("a".repeat(900))
        assertTrue(s.all { it.length <= 200 }); assertEquals(900, s.sumOf { it.length })
    }

    @Test fun pausesFollowPunctuation() {
        assertEquals(300, ReadPlan.gapAfter("Done.")); assertEquals(300, ReadPlan.gapAfter("क्या?"))
        assertEquals(140, ReadPlan.gapAfter("first part,")); assertEquals(90, ReadPlan.gapAfter("no mark"))
    }

    @Test fun mixedRunsStayInsideTheirSentenceAndOnlyTheLastOneGetsThePause() {
        val sents = listOf("bhai problem hai.", "ok")
        val p = ReadPlan.pieces(sents, "hi") { listOf("hi" to "भाई ", "en" to "problem", "hi" to " है.") }
        assertEquals(listOf(0, 0, 0, 1, 1, 1).size, p.size)       // 3 runs per sentence
        assertEquals(listOf(0, 0, 300), p.take(3).map { it.gapMs })
        assertEquals(listOf("hi", "en", "hi"), p.take(3).map { it.lang })
        assertEquals(listOf(0, 0, 0, 1, 1, 1), p.map { it.sentence })
    }

    @Test fun piecesWithoutSpeechAreDropped() {
        val p = ReadPlan.pieces(listOf("Hi.", "..."), "en", null)
        assertEquals(1, p.size); assertEquals("Hi.", p[0].text)
    }
}
