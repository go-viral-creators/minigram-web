package com.kafisir.wetalks.data

/** How careful the check is. The code is what `chat_settings/<cid>/nsfw/lvl` stores (0, 1, 2). */
enum class NsfwLevel(val code: Int, val label: String, val sub: String, val blurAt: Float, val flagAt: Float) {
    STRICT(0, "Strict", "Blurs more photos. More false alarms.", 0.70f, 0.45f),
    BALANCED(1, "Balanced", "Recommended.", 0.85f, 0.60f),
    LENIENT(2, "Lenient", "Blurs only very clear cases.", 0.95f, 0.80f);

    companion object {
        val DEFAULT = BALANCED
        fun of(code: Int?): NsfwLevel = values().firstOrNull { it.code == code } ?: DEFAULT
    }
}

enum class NsfwAction { NONE, FLAG, BLUR }

/** The model's answer (0..1 "looks sensitive") for the whole picture and for its centre. */
data class NsfwScores(val full: Float, val crop: Float)

/** What the scanner needs to know about a message BEFORE any picture is touched. */
data class NsfwMeta(val type: String, val mime: String, val w: Int, val h: Int, val mine: Boolean, val pending: Boolean = false, val deleted: Boolean = false)

/** Result of one scan request. [Refused] and [Skipped] mean the model was never called. */
sealed interface NsfwResult {
    data class Refused(val why: String) : NsfwResult
    data class Skipped(val why: String) : NsfwResult
    data class Done(val scores: NsfwScores, val action: NsfwAction, val bucket: Int) : NsfwResult
}

/** One model, two inputs. The inputs are prepared lazily, so a refused or skipped request never even decodes the picture. */
interface NsfwClassifier { fun probability(chw: FloatArray): Float }
interface NsfwInputs { fun full(): FloatArray; fun centre(): FloatArray }

/**
 * All decisions about sensitive pictures that do not need a phone: thresholds, the two cut-offs, exemptions, the chat-kind guard and the
 * alert rate limit. Private chats are refused here, at the single entry point of the scanner ([scan]).
 */
object NsfwLogic {
    /** Pictures smaller than this (either side) are never scanned: icons, stickers, emoji-like images. */
    const val MIN_SIDE = 96
    /** The centre must also look sensitive to blur, at least this share of the cut-off. Agreement of two views cuts false alarms. */
    const val CENTRE_SHARE = 0.85f
    const val MAX_ALERTS_PER_HOUR = 5
    private val SCANNED_TYPES = setOf("image", "video")        // a video is checked through its preview picture only

    /** Private chats (and Saved Messages, which is one) are never scanned. This is the only place that says so. */
    fun kindAllowed(kind: Kind): Boolean = kind == Kind.GROUP || kind == Kind.CHANNEL

    /** Groups and public channels protect by default; private channels (invite only) start off. The owner/admins can change it. */
    fun defaultOn(kind: Kind, privateChannel: Boolean): Boolean = kindAllowed(kind) && !(kind == Kind.CHANNEL && privateChannel)

    /** The effective on/off: the chat's stored choice, or the default when nothing is stored. */
    fun protectionOn(kind: Kind, privateChannel: Boolean, stored: Boolean?): Boolean = kindAllowed(kind) && (stored ?: defaultOn(kind, privateChannel))

    /** Null when the message may be scanned, otherwise the reason it is left alone. */
    fun exemption(m: NsfwMeta): String? = when {
        m.mine -> "own picture"
        m.pending -> "still sending"
        m.deleted -> "deleted"
        m.type !in SCANNED_TYPES -> "not a photo or video preview"      // stickers, GIFs, voice, text ...
        m.mime.contains("gif", true) || m.mime.contains("sticker", true) -> "animated image"
        m.w in 1 until MIN_SIDE || m.h in 1 until MIN_SIDE -> "too small"
        else -> null
    }

    /** The decoded picture itself can be smaller than the message says. */
    fun tooSmall(w: Int, h: Int) = w < MIN_SIDE || h < MIN_SIDE

    /**
     * Two cut-offs. BLUR needs the whole picture AND its centre to agree (centre may be a little lower). FLAG (admins are told, nothing is blurred)
     * is the lower cut-off with both views, or one view that is above the blur cut-off alone.
     */
    fun decide(s: NsfwScores, level: NsfwLevel): NsfwAction {
        val lo = minOf(s.full, s.crop); val hi = maxOf(s.full, s.crop)
        return when {
            s.full >= level.blurAt && s.crop >= level.blurAt * CENTRE_SHARE -> NsfwAction.BLUR
            lo >= level.flagAt || hi >= level.blurAt -> NsfwAction.FLAG
            else -> NsfwAction.NONE
        }
    }

    /** 0..100 in steps of 5. A coarse "how strong" hint for admins, not a probability claim. */
    fun bucket(s: NsfwScores): Int = (Math.round(maxOf(s.full, s.crop).coerceIn(0f, 1f) * 20f) * 5)

    /**
     * The only entry point to the model. [kind] is checked first: a private chat gets [NsfwResult.Refused] and the classifier and the inputs
     * are never touched.
     */
    fun scan(kind: Kind, meta: NsfwMeta, level: NsfwLevel, model: NsfwClassifier?, inputs: NsfwInputs): NsfwResult {
        if (!kindAllowed(kind)) return NsfwResult.Refused("private chats are never scanned")
        exemption(meta)?.let { return NsfwResult.Skipped(it) }
        if (model == null) return NsfwResult.Skipped("no model on this phone")
        val s = NsfwScores(model.probability(inputs.full()), model.probability(inputs.centre()))
        return NsfwResult.Done(s, decide(s, level), bucket(s))
    }

    // ---------------------------------------------------------------------------------------------- picture preparation (pure)
    const val SIZE = 384
    /** Left, top, right, bottom of the centre square: [share] of the shorter side. */
    fun centreRect(w: Int, h: Int, share: Float = 0.6f): IntArray {
        val side = (minOf(w, h) * share).toInt().coerceAtLeast(1)
        val l = (w - side) / 2; val t = (h - side) / 2
        return intArrayOf(l, t, l + side, t + side)
    }

    /** ARGB pixels (SIZE x SIZE) -> channel-first floats scaled to -1..1, which is what the model expects (mean 0.5, std 0.5). */
    fun toChw(argb: IntArray, size: Int = SIZE, out: FloatArray = FloatArray(3 * size * size)): FloatArray {
        val n = size * size
        for (i in 0 until n) {
            val p = argb[i]
            out[i] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            out[n + i] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            out[2 * n + i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
        }
        return out
    }

    /** The model returns probabilities already; if a build returns raw logits, turn them into probabilities. Index 0 is "NSFW". */
    fun nsfwProbability(out: FloatArray): Float {
        if (out.size < 2) return 0f
        val a = out[0]; val b = out[1]
        if (a in 0f..1f && b in 0f..1f && Math.abs(a + b - 1f) < 0.01f) return a
        val m = maxOf(a, b); val ea = Math.exp((a - m).toDouble()); val eb = Math.exp((b - m).toDouble())
        return (ea / (ea + eb)).toFloat()
    }

    // ---------------------------------------------------------------------------------------------- alert limit (mirrors the database rule)
    fun hourBucket(now: Long) = (now - now % 3_600_000L) / 3_600_000L

    /** The next value of `admin_alert_quota/<cid>/<me>`: (hour, count) or null when this person used up the hour. */
    fun nextQuota(oldHour: Long?, oldCount: Long?, now: Long): Pair<Long, Long>? {
        val h = hourBucket(now)
        val n = if (oldHour == h) (oldCount ?: 0L) + 1 else 1L
        return if (n > MAX_ALERTS_PER_HOUR) null else h to n
    }
}
