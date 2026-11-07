package com.kafisir.wetalks.data

/**
 * Auto-download rules, as plain Kotlin (no Android classes) so every edge case can be unit tested.
 *
 * The question this answers: "this media message just came into view: do we fetch it by ourselves, or show a tap-to-download tile?"
 * Inputs: what kind of media, how big it is (or unknown), which network we are on, the person's settings, and whether
 * the file is already on the phone / was sent by the person. An explicit tap is never decided here: it always downloads.
 */
object MediaPolicy {
    enum class Type(val label: String) { PHOTO("Photos"), VOICE("Voice messages"), AUDIO("Audio"), VIDEO("Video"), DOC("Documents"), GIF("GIFs") }
    enum class Net(val label: String) { WIFI("When connected to Wi-Fi"), MOBILE("When using mobile data"), ROAMING("When roaming"), OFFLINE("Offline") }

    /** What the phone says about its connection. [roaming] only matters on mobile data; [metered] marks hotspots and capped plans. */
    data class NetState(val net: Net, val metered: Boolean = false, val systemDataSaver: Boolean = false)

    /** Raw facts from the Android connectivity manager, turned into a [NetState] by [classify]. */
    fun classify(wifiOrEthernet: Boolean, cellular: Boolean, notRoaming: Boolean, notMetered: Boolean, systemDataSaver: Boolean = false): NetState = when {
        !wifiOrEthernet && !cellular -> NetState(Net.OFFLINE, !notMetered, systemDataSaver)
        // a Wi-Fi network that is flagged metered (a phone hotspot, a capped plan) is paid for like mobile data
        wifiOrEthernet && notMetered -> NetState(Net.WIFI, false, systemDataSaver)
        wifiOrEthernet -> NetState(Net.MOBILE, true, systemDataSaver)
        !notRoaming -> NetState(Net.ROAMING, !notMetered, systemDataSaver)
        else -> NetState(Net.MOBILE, !notMetered, systemDataSaver)
    }

    /** What one network allows. [maxMb] 0 means "no size limit"; it applies to video, audio and documents. */
    data class Rule(val types: Set<Type>, val maxMb: Int) {
        fun has(t: Type) = t in types
        fun with(t: Type, on: Boolean) = copy(types = if (on) types + t else types - t)
    }

    data class Settings(val wifi: Rule, val mobile: Rule, val roaming: Rule, val useLessData: Boolean = false) {
        fun rule(n: Net): Rule = when (n) { Net.WIFI -> wifi; Net.MOBILE -> mobile; Net.ROAMING -> roaming; Net.OFFLINE -> Rule(emptySet(), 0) }
        fun withRule(n: Net, r: Rule) = when (n) { Net.WIFI -> copy(wifi = r); Net.MOBILE -> copy(mobile = r); Net.ROAMING -> copy(roaming = r); Net.OFFLINE -> this }
    }

    /** Wi-Fi: everything up to 25 MB. Mobile data: photos and voice only. Roaming: nothing. */
    fun defaults() = Settings(
        wifi = Rule(Type.values().toSet(), 25),
        mobile = Rule(setOf(Type.PHOTO, Type.VOICE), 5),
        roaming = Rule(emptySet(), 5))

    /** "Use less data" on a paid connection: only voice messages still come by themselves. On Wi-Fi it just caps big files. */
    const val LESS_DATA_WIFI_MAX_MB = 5

    enum class Why { CACHED, SENT_BY_ME, ALLOWED, TYPE_OFF, TOO_BIG, SIZE_UNKNOWN, LESS_DATA, DATA_SAVER, OFFLINE }
    data class Verdict(val auto: Boolean, val why: Why)

    private val LIMITED = setOf(Type.VIDEO, Type.AUDIO, Type.DOC)

    /**
     * [sizeBytes] <= 0 means the sender did not say how big it is.
     * Cached and own files are never blocked. Everything else needs the network rule to allow its type and size.
     */
    fun decide(type: Type, sizeBytes: Long, net: NetState, s: Settings, cached: Boolean = false, mine: Boolean = false): Verdict {
        if (cached) return Verdict(true, Why.CACHED)
        if (mine) return Verdict(true, Why.SENT_BY_ME)
        if (net.net == Net.OFFLINE) return Verdict(false, Why.OFFLINE)
        val paid = net.net != Net.WIFI
        if (paid && !s.rule(net.net).has(type)) return Verdict(false, Why.TYPE_OFF)
        if (!paid && !s.wifi.has(type)) return Verdict(false, Why.TYPE_OFF)
        // the two "save data" switches: ours, and Android's Data Saver (which only restricts paid connections)
        if (paid && net.systemDataSaver && type != Type.VOICE) return Verdict(false, Why.DATA_SAVER)
        if (s.useLessData && paid && type != Type.VOICE) return Verdict(false, Why.LESS_DATA)
        if (type in LIMITED) {
            var limit = s.rule(net.net).maxMb
            if (s.useLessData && !paid) limit = if (limit <= 0) LESS_DATA_WIFI_MAX_MB else minOf(limit, LESS_DATA_WIFI_MAX_MB)
            if (limit > 0) {
                // a file whose size we do not know cannot be shown to fit under a limit, so it waits for a tap
                if (sizeBytes <= 0) return Verdict(false, Why.SIZE_UNKNOWN)
                if (sizeBytes > limit * 1048576L) return Verdict(false, Why.TOO_BIG)
            }
        }
        return Verdict(true, Why.ALLOWED)
    }

    /**
     * A download that started by itself is checked again when the network or the settings change.
     * One the person started with a tap is theirs: it carries on whatever changes.
     */
    fun keepGoing(explicit: Boolean, type: Type, sizeBytes: Long, net: NetState, s: Settings, mine: Boolean = false): Boolean =
        explicit || decide(type, sizeBytes, net, s, cached = false, mine = mine).auto

    /** Tell the person before a tap spends roaming data. Wi-Fi and home mobile data get no warning. */
    fun warnOnTap(net: NetState) = net.net == Net.ROAMING

    fun reasonText(w: Why): String = when (w) {
        Why.CACHED, Why.SENT_BY_ME, Why.ALLOWED -> ""
        Why.TYPE_OFF -> "Auto-download is off for this on your current network"
        Why.TOO_BIG -> "Bigger than your auto-download limit"
        Why.SIZE_UNKNOWN -> "Size unknown"
        Why.LESS_DATA -> "Use less data is on"
        Why.DATA_SAVER -> "Data Saver is on"
        Why.OFFLINE -> "No connection"
    }

    // ---- saving the settings as one short text (kept in SharedPreferences)
    private fun encodeRule(r: Rule) = r.types.map { it.name }.sorted().joinToString(",") + "|" + r.maxMb
    private fun decodeRule(s: String, fallback: Rule): Rule {
        val p = s.split('|'); if (p.size != 2) return fallback
        val types = p[0].split(',').filter { it.isNotEmpty() }.mapNotNull { n -> Type.values().firstOrNull { it.name == n } }.toSet()
        return Rule(types, (p[1].toIntOrNull() ?: fallback.maxMb).coerceIn(0, 30))
    }
    fun encode(s: Settings) = "v2;w=" + encodeRule(s.wifi) + ";m=" + encodeRule(s.mobile) + ";r=" + encodeRule(s.roaming) + ";l=" + (if (s.useLessData) 1 else 0)
    fun decode(text: String?): Settings {
        val d = defaults()
        if (text.isNullOrEmpty() || !text.startsWith("v2;")) return d
        val m = text.split(';').drop(1).mapNotNull { val i = it.indexOf('='); if (i > 0) it.substring(0, i) to it.substring(i + 1) else null }.toMap()
        return Settings(m["w"]?.let { decodeRule(it, d.wifi) } ?: d.wifi, m["m"]?.let { decodeRule(it, d.mobile) } ?: d.mobile, m["r"]?.let { decodeRule(it, d.roaming) } ?: d.roaming, m["l"] == "1")
    }

    /** The old single "photos: always / wifi / never" choice becomes the photo switch of the new rules. */
    fun migrateLegacy(photos: String?): Settings {
        val d = defaults()
        return when (photos) {
            "never" -> Settings(d.wifi.with(Type.PHOTO, false).with(Type.GIF, false), d.mobile.with(Type.PHOTO, false), d.roaming)
            "wifi" -> Settings(d.wifi, d.mobile.with(Type.PHOTO, false), d.roaming)
            else -> d
        }
    }

    val SIZE_CHOICES = listOf(1, 5, 10, 25, 30, 0)
    fun sizeLabel(mb: Int) = if (mb <= 0) "No limit" else "$mb MB"

    fun typeOf(msgType: String): Type? = when (msgType) {
        "image" -> Type.PHOTO; "voice" -> Type.VOICE; "audio" -> Type.AUDIO; "video" -> Type.VIDEO; "file" -> Type.DOC; "gif" -> Type.GIF; else -> null
    }
}
