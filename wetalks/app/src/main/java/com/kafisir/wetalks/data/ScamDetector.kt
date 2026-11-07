package com.kafisir.wetalks.data

enum class ScamLevel { NONE, MEDIUM, HIGH }
enum class ScamLang { EN, HINGLISH, HINDI }

/** [show] is the only thing the UI looks at: true means "put the warning banner under this message". */
class ScamVerdict(val level: ScamLevel, val score: Int, val reasons: List<ScamReason>, val lang: ScamLang, val show: Boolean) {
    companion object { val NONE = ScamVerdict(ScamLevel.NONE, 0, emptyList(), ScamLang.EN, false) }
}

/** Combines the weak signals from [ScamRules] into one score. Pure Kotlin, no logging, nothing is stored. */
object ScamDetector {
    const val HIGH_AT = 6
    const val MEDIUM_AT = 4

    private val HINGLISH_WORDS = hashSetOf("hai", "hain", "ho", "karo", "karein", "kar", "aapka", "aap", "apna", "apne", "bhej", "bata", "ka", "ke", "ki", "me", "par", "se", "ko", "nahi", "abhi", "aaj", "turant", "jayega", "hoga", "rupaye", "pao", "kamao", "mat", "hum", "aapke", "tum", "wo", "ye", "yeh", "raha", "rahi", "diya", "lo", "do", "de")

    fun language(raw: String): ScamLang {
        var dev = 0; var lat = 0
        for (c in raw) { if (c in 'ऀ'..'ॿ') dev++ else if (c in 'a'..'z' || c in 'A'..'Z') lat++ }
        if (dev > 8 && dev * 3 > lat) return ScamLang.HINDI
        val words = raw.lowercase().split(Regex("[^a-z]+"))
        val hits = words.count { it in HINGLISH_WORDS }
        return if (hits >= 3 && hits * 5 >= words.size) ScamLang.HINGLISH else ScamLang.EN
    }

    fun scan(raw: String, classifier: ScamClassifier? = null): ScamVerdict {
        if (raw.length < 10) return ScamVerdict.NONE
        val cleaned = ScamRules.clean(raw)
        val n = ScamRules.normalise(raw)
        val hits = ScamHits()
        ScamRules.scanText(n, hits)

        // links
        val urls = ScamRules.extractUrls(cleaned)
        var linkScore = 0
        val linkReasons = LinkedHashSet<ScamReason>()
        for (u in urls) {
            hits.hasLink = true
            val c = ScamRules.checkUrl(u)
            if (c.telegram) hits.telegram = true
            if (c.score > linkScore) linkScore = c.score
            linkReasons.addAll(c.reasons)
        }
        linkScore = minOf(linkScore, 8)
        if (linkScore >= 3) hits.suspiciousLink = true

        var total = linkScore
        val reasons = LinkedHashSet<ScamReason>()
        reasons.addAll(linkReasons)
        for ((r, w) in hits.weights) { total += w; reasons.add(r) }
        if (hits.timing && (hits.weights.containsKey(ScamReason.THREAT) || hits.hasLink || hits.weights.containsKey(ScamReason.KYC) || hits.weights.containsKey(ScamReason.PRIZE))) total += 1
        if (hits.weakPrize && (hits.hasLink || hits.weights.containsKey(ScamReason.FEE) || hits.weights.containsKey(ScamReason.ASKS_DETAILS))) { total += 3; reasons.add(ScamReason.PRIZE) }
        if (hits.weakBrand && (hits.suspiciousLink || hits.weights.containsKey(ScamReason.THREAT) || hits.weights.containsKey(ScamReason.KYC) || hits.weights.containsKey(ScamReason.PRIZE))) total += 1
        if (hits.weakRefund && (hits.weights.containsKey(ScamReason.SCREEN_SHARE) || hits.weights.containsKey(ScamReason.REMOTE_ACCESS) || hits.upiTrick || hits.secretRequest)) total += 2
        if (hits.weights.containsKey(ScamReason.THREAT) && ScamRules.callNow(n)) total += 1
        if (hits.hasLink && ScamRules.hasClickWord(n) && total >= 2) total += 1
        // combinations
        if (hits.weights.containsKey(ScamReason.SCREEN_SHARE) && (hits.weakRefund || hits.weights.containsKey(ScamReason.KYC) || hits.weights.containsKey(ScamReason.AUTHORITY) || hits.weights.containsKey(ScamReason.THREAT))) total += 3
        if (hits.telegram && (hits.weights.containsKey(ScamReason.JOB) || hits.weights.containsKey(ScamReason.INVEST))) { total += 3; reasons.add(ScamReason.LINK_RISKY) }
        if (hits.secretRequest && (hits.weights.containsKey(ScamReason.THREAT) || hits.weights.containsKey(ScamReason.KYC) || hits.weights.containsKey(ScamReason.PRIZE) || hits.weakRefund || hits.hasLink)) total += 2
        if (hits.weights.containsKey(ScamReason.ASKS_DETAILS) && (hits.weights.containsKey(ScamReason.PRIZE) || hits.weights.containsKey(ScamReason.LOAN) || hits.weights.containsKey(ScamReason.THREAT) || hits.weights.containsKey(ScamReason.KYC))) total += 2
        if (hits.aware) total -= 4
        else if (hits.psa) total -= 1

        // optional model: it can only strengthen what the rules already found
        if (classifier != null && total >= 2) {
            val p = try { classifier.score(raw) } catch (_: Throwable) { null }
            if (p != null && p >= 0.9f) total += 2
        }

        val level = if (total >= HIGH_AT) ScamLevel.HIGH else if (total >= MEDIUM_AT) ScamLevel.MEDIUM else ScamLevel.NONE
        val show = level == ScamLevel.HIGH ||
            (level == ScamLevel.MEDIUM && (hits.suspiciousLink || hits.secretRequest || hits.remoteInstall || hits.upiTrick))
        if (level == ScamLevel.NONE) return ScamVerdict(level, total, emptyList(), ScamLang.EN, false)
        // the most useful reasons first, at most three
        val order = listOf(ScamReason.ASKS_SECRET, ScamReason.REMOTE_ACCESS, ScamReason.UPI_TRICK, ScamReason.LINK_APK, ScamReason.LINK_FAKE, ScamReason.LINK_SHORT, ScamReason.LINK_RISKY,
            ScamReason.AUTHORITY, ScamReason.THREAT, ScamReason.KYC, ScamReason.FEE, ScamReason.PRIZE, ScamReason.JOB, ScamReason.INVEST, ScamReason.LOAN, ScamReason.GIFT_CARDS, ScamReason.ASKS_DETAILS, ScamReason.SCREEN_SHARE)
        val top = order.filter { it in reasons }.take(3)
        return ScamVerdict(level, total, top, language(raw), show)
    }
}
