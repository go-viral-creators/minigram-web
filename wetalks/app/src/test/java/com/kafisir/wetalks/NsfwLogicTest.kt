package com.kafisir.wetalks

import com.kafisir.wetalks.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NsfwLogicTest {
    private val photo = NsfwMeta("image", "image/jpeg", 1024, 768, mine = false)
    private class Throwing : NsfwClassifier { var calls = 0; override fun probability(chw: FloatArray): Float { calls++; throw AssertionError("the model must not be called") } }
    private class Fixed(val full: Float, val crop: Float) : NsfwClassifier {
        var calls = 0
        override fun probability(chw: FloatArray): Float { calls++; return if (calls == 1) full else crop }
    }
    private class Inputs : NsfwInputs {
        var asked = 0
        override fun full(): FloatArray { asked++; return FloatArray(1) }
        override fun centre(): FloatArray { asked++; return FloatArray(1) }
    }

    // ------------------------------------------------------------------ private chats are refused before anything else happens
    @Test fun directMessagesAreRefusedAndTheModelIsNeverTouched() {
        val m = Throwing(); val inp = Inputs()
        val r = NsfwLogic.scan(Kind.DM, photo, NsfwLevel.STRICT, m, inp)
        assertTrue(r is NsfwResult.Refused)
        assertEquals(0, m.calls); assertEquals(0, inp.asked)
    }

    @Test fun savedMessagesIsADirectChatAndAlsoRefused() {
        // Saved Messages is the chat with yourself: it is Kind.DM like every other private chat
        val m = Throwing(); val inp = Inputs()
        assertTrue(NsfwLogic.scan(Kind.DM, photo.copy(mine = false), NsfwLevel.LENIENT, m, inp) is NsfwResult.Refused)
        assertEquals(0, m.calls); assertEquals(0, inp.asked)
    }

    @Test fun onlyGroupsAndChannelsAreAllowed() {
        assertFalse(NsfwLogic.kindAllowed(Kind.DM)); assertTrue(NsfwLogic.kindAllowed(Kind.GROUP)); assertTrue(NsfwLogic.kindAllowed(Kind.CHANNEL))
        // even a stored "on" for a private chat cannot turn it on
        assertFalse(NsfwLogic.protectionOn(Kind.DM, false, true))
        assertFalse(NsfwLogic.defaultOn(Kind.DM, false))
    }

    /** Structure test: the TFLite class is built in one file only, and that file is reached only through NsfwLogic.scan. */
    @Test fun nothingOutsideTheScannerFilesCanReachTheModel() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val allowed = setOf("NsfwTflite.kt", "NsfwRuntime.kt", "NsfwLogic.kt")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { f -> f.readText().let { "TfliteNsfw" in it || "org.tensorflow.lite" in it } }.map { it.name }.toList()
        assertEquals("only the scanner files may touch the model", emptyList<String>(), offenders)
        // and the chat-kind guard is the first statement of the entry point
        val src = File(root, "com/kafisir/wetalks/data/NsfwLogic.kt").readText()
        val body = src.substringAfter("fun scan(kind: Kind").substringAfter("{")
        assertTrue(body.trimStart().startsWith("if (!kindAllowed(kind))"))
        // the runtime calls the model only through NsfwLogic.scan
        val rt = File(root, "com/kafisir/wetalks/data/NsfwRuntime.kt").readText()
        assertFalse("runtime must not call probability() directly", Regex("""\.probability\(""").containsMatchIn(rt))
    }

    // ------------------------------------------------------------------ exemptions
    @Test fun exemptions() {
        assertNull(NsfwLogic.exemption(photo))
        assertNotNull(NsfwLogic.exemption(photo.copy(mine = true)))
        assertNotNull(NsfwLogic.exemption(photo.copy(pending = true)))
        assertNotNull(NsfwLogic.exemption(photo.copy(deleted = true)))
        for (t in listOf("sticker", "gif", "text", "voice", "audio", "file", "link", "call")) assertNotNull(t, NsfwLogic.exemption(photo.copy(type = t)))
        assertNotNull(NsfwLogic.exemption(photo.copy(mime = "image/gif")))
        assertNotNull(NsfwLogic.exemption(photo.copy(w = 95, h = 400)))
        assertNotNull(NsfwLogic.exemption(photo.copy(w = 400, h = 60)))
        assertNull(NsfwLogic.exemption(photo.copy(w = 96, h = 96)))
        assertNull("size unknown is allowed, the decoded picture is checked later", NsfwLogic.exemption(photo.copy(w = 0, h = 0)))
        assertNull(NsfwLogic.exemption(photo.copy(type = "video", mime = "video/mp4")))
        assertTrue(NsfwLogic.tooSmall(95, 500)); assertFalse(NsfwLogic.tooSmall(96, 96))
    }

    @Test fun exemptMessagesNeverReachTheModel() {
        val m = Throwing(); val inp = Inputs()
        assertTrue(NsfwLogic.scan(Kind.GROUP, photo.copy(mine = true), NsfwLevel.STRICT, m, inp) is NsfwResult.Skipped)
        assertTrue(NsfwLogic.scan(Kind.GROUP, photo.copy(type = "sticker"), NsfwLevel.STRICT, m, inp) is NsfwResult.Skipped)
        assertTrue(NsfwLogic.scan(Kind.CHANNEL, photo.copy(w = 50, h = 50), NsfwLevel.STRICT, m, inp) is NsfwResult.Skipped)
        assertTrue(NsfwLogic.scan(Kind.GROUP, photo, NsfwLevel.STRICT, null, inp) is NsfwResult.Skipped)   // no model file on this phone
        assertEquals(0, m.calls); assertEquals(0, inp.asked)
    }

    // ------------------------------------------------------------------ thresholds and the two cut-offs
    @Test fun levelsAreOrderedAndMapped() {
        val l = NsfwLevel.values()
        assertTrue(l[0].blurAt < l[1].blurAt && l[1].blurAt < l[2].blurAt)
        assertTrue(l.all { it.flagAt < it.blurAt })
        assertEquals(NsfwLevel.STRICT, NsfwLevel.of(0)); assertEquals(NsfwLevel.BALANCED, NsfwLevel.of(1)); assertEquals(NsfwLevel.LENIENT, NsfwLevel.of(2))
        assertEquals(NsfwLevel.BALANCED, NsfwLevel.of(null)); assertEquals(NsfwLevel.BALANCED, NsfwLevel.of(9))
    }

    @Test fun twoCutOffs() {
        val b = NsfwLevel.BALANCED
        assertEquals(NsfwAction.NONE, NsfwLogic.decide(NsfwScores(0.1f, 0.1f), b))
        assertEquals(NsfwAction.NONE, NsfwLogic.decide(NsfwScores(0.59f, 0.59f), b))
        assertEquals(NsfwAction.FLAG, NsfwLogic.decide(NsfwScores(0.65f, 0.62f), b))      // between the cut-offs: tell admins, do not blur
        assertEquals(NsfwAction.BLUR, NsfwLogic.decide(NsfwScores(0.90f, 0.88f), b))
    }

    @Test fun blurNeedsBothViewsToAgree() {
        val b = NsfwLevel.BALANCED
        assertEquals("whole picture high, centre low: not blurred", NsfwAction.FLAG, NsfwLogic.decide(NsfwScores(0.97f, 0.30f), b))
        assertEquals("centre high, whole picture low: not blurred", NsfwAction.FLAG, NsfwLogic.decide(NsfwScores(0.40f, 0.97f), b))
        assertEquals(NsfwAction.BLUR, NsfwLogic.decide(NsfwScores(0.86f, 0.75f), b))       // centre only slightly lower is still agreement
        assertEquals(NsfwAction.NONE, NsfwLogic.decide(NsfwScores(0.55f, 0.30f), b))
    }

    @Test fun strictBlursMoreLenientBlursLess() {
        val s = NsfwScores(0.80f, 0.80f)
        assertEquals(NsfwAction.BLUR, NsfwLogic.decide(s, NsfwLevel.STRICT))
        assertEquals(NsfwAction.FLAG, NsfwLogic.decide(s, NsfwLevel.BALANCED))
        assertEquals(NsfwAction.FLAG, NsfwLogic.decide(s, NsfwLevel.LENIENT))
        assertEquals(NsfwAction.NONE, NsfwLogic.decide(NsfwScores(0.79f, 0.79f), NsfwLevel.LENIENT))
        assertEquals(NsfwAction.BLUR, NsfwLogic.decide(NsfwScores(0.96f, 0.96f), NsfwLevel.LENIENT))
    }

    @Test fun scanReturnsDecisionAndBucket() {
        val m = Fixed(0.93f, 0.91f); val inp = Inputs()
        val r = NsfwLogic.scan(Kind.GROUP, photo, NsfwLevel.BALANCED, m, inp) as NsfwResult.Done
        assertEquals(NsfwAction.BLUR, r.action); assertEquals(95, r.bucket); assertEquals(2, m.calls); assertEquals(2, inp.asked)
    }

    @Test fun bucketIsCoarseAndInRange() {
        assertEquals(0, NsfwLogic.bucket(NsfwScores(0f, 0f)))
        assertEquals(100, NsfwLogic.bucket(NsfwScores(1f, 1f)))
        assertEquals(100, NsfwLogic.bucket(NsfwScores(5f, 0f)))
        assertEquals(0, NsfwLogic.bucket(NsfwScores(-3f, -1f)))
        for (i in 0..100) { val b = NsfwLogic.bucket(NsfwScores(i / 100f, 0f)); assertTrue(b in 0..100 && b % 5 == 0) }
    }

    // ------------------------------------------------------------------ defaults
    @Test fun defaults() {
        assertTrue(NsfwLogic.protectionOn(Kind.GROUP, false, null))
        assertTrue(NsfwLogic.protectionOn(Kind.CHANNEL, false, null))
        assertFalse("private channels start off", NsfwLogic.protectionOn(Kind.CHANNEL, true, null))
        assertTrue(NsfwLogic.protectionOn(Kind.CHANNEL, true, true))
        assertFalse(NsfwLogic.protectionOn(Kind.GROUP, false, false))
    }

    // ------------------------------------------------------------------ picture preparation
    @Test fun centreRect() {
        val r = NsfwLogic.centreRect(1000, 500)
        assertArrayEquals(intArrayOf(350, 100, 650, 400), r)
        val sq = NsfwLogic.centreRect(100, 100)
        assertEquals(60, sq[2] - sq[0]); assertEquals(60, sq[3] - sq[1])
        val tiny = NsfwLogic.centreRect(1, 1); assertEquals(1, tiny[2] - tiny[0])
    }

    @Test fun chwLayoutAndRange() {
        val px = IntArray(4) { 0xFF000000.toInt() }
        px[0] = 0xFFFF0000.toInt(); px[1] = 0xFF00FF00.toInt(); px[2] = 0xFF0000FF.toInt(); px[3] = 0xFFFFFFFF.toInt()
        val f = NsfwLogic.toChw(px, 2)
        assertEquals(12, f.size)
        assertEquals(1f, f[0], 1e-6f); assertEquals(-1f, f[1], 1e-6f)         // red plane: pixel 0 red, pixel 1 green
        assertEquals(-1f, f[4], 1e-6f); assertEquals(1f, f[5], 1e-6f)         // green plane
        assertEquals(1f, f[10], 1e-6f); assertEquals(1f, f[11], 1e-6f)        // blue plane: pixel 2 blue, pixel 3 white
        assertTrue(f.all { it in -1f..1f })
    }

    @Test fun modelOutputIsReadAsNsfwFirst() {
        assertEquals(0.9f, NsfwLogic.nsfwProbability(floatArrayOf(0.9f, 0.1f)), 1e-6f)
        assertEquals(0.1f, NsfwLogic.nsfwProbability(floatArrayOf(0.1f, 0.9f)), 1e-6f)
        val p = NsfwLogic.nsfwProbability(floatArrayOf(3f, -3f)); assertTrue(p > 0.99f)       // raw logits
        assertEquals(0f, NsfwLogic.nsfwProbability(floatArrayOf()), 0f)
    }

    // ------------------------------------------------------------------ alert limit bookkeeping (same numbers as the database rule)
    @Test fun alertLimit() {
        val t0 = 1_700_000_000_000L; val h = NsfwLogic.hourBucket(t0)
        var q: Pair<Long, Long>? = NsfwLogic.nextQuota(null, null, t0)
        assertEquals(h to 1L, q)
        for (i in 2..NsfwLogic.MAX_ALERTS_PER_HOUR) { q = NsfwLogic.nextQuota(q!!.first, q.second, t0 + i * 1000L); assertEquals(i.toLong(), q!!.second) }
        assertNull("sixth in the same hour", NsfwLogic.nextQuota(q!!.first, q.second, t0 + 10_000L))
        val later = NsfwLogic.nextQuota(q.first, q.second, t0 + 3_600_000L)
        assertEquals(1L, later!!.second); assertEquals(h + 1, later.first)
        assertEquals(h, NsfwLogic.hourBucket(t0 + 3_599_999L - (t0 % 3_600_000L)))
    }
}
