package com.kafisir.wetalks

import com.kafisir.wetalks.data.ScamDetector
import com.kafisir.wetalks.data.ScamLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamDetectorTest {
    private fun lines(name: String): List<String> =
        javaClass.getResourceAsStream("/scam/$name")!!.bufferedReader(Charsets.UTF_8).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun report(set: String, scam: List<String>, benign: List<String>): Pair<Int, Int> {
        val missed = scam.filter { !ScamDetector.scan(it).show }
        val fp = benign.filter { ScamDetector.scan(it).show }
        val tp = scam.size - missed.size
        val precision = if (tp + fp.size == 0) 1.0 else tp.toDouble() / (tp + fp.size)
        println("=== $set: scam=${scam.size} benign=${benign.size}")
        println("recall=%.1f%% (%d/%d)  precision=%.1f%%  false-positive rate=%.2f%% (%d/%d)".format(100.0 * tp / scam.size, tp, scam.size, 100 * precision, 100.0 * fp.size / benign.size, fp.size, benign.size))
        fp.forEach { println("FALSE POSITIVE [${ScamDetector.scan(it).score}] $it") }
        missed.forEach { println("MISSED [${ScamDetector.scan(it).score}] $it") }
        return missed.size to fp.size
    }

    @Test fun evaluationSet() {
        for (lang in listOf("en", "hinglish", "hindi")) report(lang, lines("scam_$lang.txt"), lines("benign_$lang.txt"))
        report("benign_more only (hold-out)", listOf("x".repeat(20) + " dummy"), lines("benign_more.txt"))
        val scam = listOf("en", "hinglish", "hindi").flatMap { lines("scam_$it.txt") }
        val benign = listOf("en", "hinglish", "hindi", "more").flatMap { lines("benign_$it.txt") }
        val (missed, fp) = report("ALL", scam, benign)
        assertTrue("need at least 250 scam examples", scam.size >= 250)
        assertTrue("need at least 600 benign examples", benign.size >= 600)
        assertTrue("false positive rate must stay under 1%: $fp of ${benign.size}", fp * 100.0 / benign.size < 1.0)
        assertTrue("recall must stay above 85%: missed $missed of ${scam.size}", missed * 100.0 / scam.size < 15.0)
    }

    @Test fun obfuscation() {
        assertTrue(ScamDetector.scan("Sir please send me your O T P now for the refund process").show)
        assertTrue(ScamDetector.scan("send me ur 0tp and p1n quickly for kyc").show)
        assertEquals("share the otp 123456", com.kafisir.wetalks.data.ScamRules.clean("Share\u200B the OTP \u0967\u0968\u0969\u096A\u096B\u096C"))
        assertTrue(ScamDetector.scan("Share\u200B the OTP immediately or your account is blocked").level != ScamLevel.NONE)
        assertFalse(ScamDetector.scan("Did you get the OTP? Mine has not come yet.").show)
        assertFalse(ScamDetector.scan("SBI reminder: never share your OTP with anyone, not even bank staff.").show)
    }

    @Test fun neverCrashesOnOddInput() {
        for (s in listOf("", " ", "a", "\u0000\u0000\u0000", "http://", "https://@@@", "x".repeat(50000), "http://" + "a.".repeat(500) + "com", "😀".repeat(300))) ScamDetector.scan(s)
    }

    @Test fun fast() {
        val msg = "Dear customer, your SBI account will be blocked today. Update your KYC now at http://sbi-kyc-update.xyz/login and share the OTP"
        repeat(200) { ScamDetector.scan(msg) }
        val t0 = System.nanoTime()
        repeat(500) { ScamDetector.scan(msg); ScamDetector.scan("Hey, are you coming to dinner tonight with everyone?") }
        val perScanMs = (System.nanoTime() - t0) / 1e6 / 1000
        println("average scan time on this JVM: %.3f ms".format(perScanMs))
        assertTrue(perScanMs < 5.0)
    }

    @Test fun language() {
        assertEquals(com.kafisir.wetalks.data.ScamLang.HINDI, ScamDetector.language("आपका खाता आज बंद हो जाएगा तुरंत क्लिक करें"))
        assertEquals(com.kafisir.wetalks.data.ScamLang.HINGLISH, ScamDetector.language("aapka account aaj band ho jayega abhi link par click karein"))
        assertEquals(com.kafisir.wetalks.data.ScamLang.EN, ScamDetector.language("your account will be blocked today"))
    }
}
