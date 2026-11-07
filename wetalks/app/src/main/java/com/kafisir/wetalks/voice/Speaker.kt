package com.kafisir.wetalks.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kafisir.wetalks.ui.Markdown
import java.util.Locale

/**
 * Read a message aloud with the phone's text-to-speech engine (Google's engine has natural, neural voices in 40+ languages).
 * The language is worked out from the text itself, and the best installed voice for it is chosen: highest quality first,
 * network (neural) voices before the small offline ones.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    @Volatile var speakingId: String? = null
    var onState: (() -> Unit)? = null

    // what the player bar and the media notification show
    var reading by mutableStateOf(false); private set
    var paused by mutableStateOf(false); private set
    var label by mutableStateOf("Reading aloud"); private set
    /** Reading speed (1.0 = normal), kept between readings. */
    var speed by mutableFloatStateOf(1f); private set
    /** Sentence being read (0-based) and how many there are. */
    var sentence by mutableIntStateOf(0); private set
    var total by mutableIntStateOf(0); private set
    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private var neural = false
    private var sysChunks: List<String> = emptyList()
    @Volatile private var sysIdx = 0
    @Volatile private var sysPos = 0            // character the voice had reached inside chunk [sysIdx]
    @Volatile private var sysGen = 0            // every (re)start gets a number: answers from an older start are ignored
    private var sysId = ""
    private var appCtx: Context? = null
    private fun notifyService() { appCtx?.let { PlaybackService.sync(it) } }
    private fun prefs(c: Context) = c.getSharedPreferences("wt_listen", Context.MODE_PRIVATE)

    private fun finish() { speakingId = null; reading = false; paused = false; neural = false; sentence = 0; total = 0; NeuralTts.onProgress = null; notifyService(); onState?.invoke() }

    private fun engine(ctx: Context, then: () -> Unit) {
        val t = tts
        if (t != null && ready) { then(); return }
        pending = then
        if (t != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    // utterance ids look like "<message>#g<start number>i<chunk>b<offset into the chunk>"; answers of an older start are dropped
                    private fun parse(id: String?): IntArray? { val m = id?.let { Regex("#g(\\d+)i(\\d+)b(\\d+)$").find(it) } ?: return null; return intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
                    override fun onStart(id: String?) { val p = parse(id) ?: return; if (p[0] != sysGen) return; sysIdx = p[1]; sysPos = p[2]; sentence = p[1] }
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) { val p = parse(utteranceId) ?: return; if (p[0] != sysGen) return; sysPos = p[2] + start }
                    override fun onDone(id: String?) { val p = parse(id) ?: return; if (p[0] == sysGen && p[1] >= sysChunks.lastIndex && !paused) finish() }
                    @Deprecated("") override fun onError(id: String?) { val p = parse(id); if ((p == null || p[0] == sysGen) && !paused) finish() }
                })
                pending?.invoke()
            } else { tts = null; finish() }
            pending = null
        }
    }

    fun stop() {
        sysGen++
        try { tts?.stop() } catch (_: Exception) {}; NeuralTts.stop()
        if (reading || speakingId != null) finish()
    }

    fun pause() {
        if (!reading || paused) return
        paused = true
        // the natural voice pauses its audio exactly where it is; the phone's engine has no pause, so it is stopped and continues from the word it had reached
        if (neural) NeuralTts.pause() else { sysGen++; try { tts?.stop() } catch (_: Exception) {} }
        notifyService()
    }
    fun resume() {
        if (!reading || !paused) return
        paused = false
        if (neural) NeuralTts.resume() else speakSys(sysIdx, sysPos)
        notifyService()
    }
    fun toggle() { if (paused) resume() else pause() }

    /** One sentence back (-1) or forward (+1). */
    fun skip(delta: Int) {
        if (!reading || total <= 0) return
        val n = (sentence + delta).coerceIn(0, total - 1)
        sentence = n; paused = false
        if (neural) NeuralTts.restart(n, speed) else speakSys(n, 0)
        notifyService()
    }

    fun changeSpeed(v: Float) {
        speed = v.coerceIn(0.5f, 2f)
        appCtx?.let { prefs(it).edit().putFloat("speed", speed).apply() }
        if (!reading) return
        if (neural) { paused = false; NeuralTts.restart(sentence, speed) }
        else { try { tts?.setSpeechRate(speed) } catch (_: Exception) {}; if (!paused) speakSys(sysIdx, sysPos) }
        notifyService()
    }
    fun cycleSpeed() { val i = speeds.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }; changeSpeed(speeds[(i + 1).mod(speeds.size)]) }

    /** Speaks the chunks from [from], starting at character [offset] of the first one (that is how a pause continues from the same word). */
    private fun speakSys(from: Int, offset: Int) {
        val t = tts ?: return
        val parts = sysChunks; if (parts.isEmpty()) return
        val gen = ++sysGen
        val first = from.coerceIn(0, parts.lastIndex)
        t.stop()
        for (i in first..parts.lastIndex) {
            val base = if (i == first) offset.coerceIn(0, maxOf(0, parts[i].length - 1)) else 0
            val text = parts[i].substring(base)
            if (text.isBlank()) continue
            t.speak(text, if (i == first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$sysId#g${gen}i${i}b$base")
        }
    }

    /** Reads [text] aloud. A voice note and the reader never play at the same time. */
    fun speak(ctx: Context, id: String, text: String, appLang: String, say: (String) -> Unit, title: String = "") {
        if (speakingId == id) { stop(); return }
        appCtx = ctx.applicationContext
        speed = prefs(ctx).getFloat("speed", 1f).coerceIn(0.5f, 2f)
        val clean = Markdown.plain(text).replace(Regex("https?://\\S+"), " link ").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        VoicePlayer.stop(); stop()
        label = title.ifEmpty { "Reading aloud" }
        Hinglish.load(ctx)
        val locEarly = detect(clean, appLang)
        val hing = Hinglish.looksHinglish(clean)
        // natural (downloaded) voice when the person turned it on and it covers this language; otherwise the phone's own voice
        if (NeuralTts.usable() && (hing || NeuralTts.supports(locEarly.language))) {
            neural = true; speakingId = id; reading = true; paused = false; sentence = 0
            NeuralTts.onProgress = { sentence = it }
            NeuralTts.speak(ctx, clean, locEarly.language, hing, say, speed) { if (speakingId == id) finish() }
            total = NeuralTts.sentenceTotal
            notifyService(); onState?.invoke()
            return
        }
        engine(ctx) {
            val t = tts ?: return@engine
            // romanised Hindi is read far better by a Hindi voice from Devanagari; English words in it keep their letters
            var spoken = clean
            var loc = Locale(locEarly.language, locEarly.country)
            if (hing) {
                val hi = Locale("hi", "IN")
                if (best(t, hi) != null || t.isLanguageAvailable(hi) >= TextToSpeech.LANG_AVAILABLE) { loc = hi; spoken = Hinglish.toDevanagari(clean) }
                else loc = Locale("en", "IN")
            }
            val voice = best(t, loc)
            if (voice == null) {
                val r = t.isLanguageAvailable(loc)
                if (r < TextToSpeech.LANG_AVAILABLE) {
                    say("No " + loc.displayLanguage + " voice installed. Opening voice settings…")
                    try { ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    return@engine
                }
                t.language = loc
            } else t.voice = voice
            t.setSpeechRate(speed); t.setPitch(1.0f)
            neural = false; speakingId = id; reading = true; paused = false; sysId = id; sysIdx = 0; sysPos = 0; sentence = 0
            sysChunks = ReadPlan.sentences(spoken, 220, 160)          // one sentence at a time: the engine refuses very long text, and a pause continues from the same sentence
            if (sysChunks.isEmpty()) sysChunks = listOf(spoken.take(220))
            total = sysChunks.size
            notifyService(); onState?.invoke()
            speakSys(0, 0)
        }
    }

    private fun best(t: TextToSpeech, loc: Locale): Voice? {
        val all = try { t.voices } catch (_: Exception) { null } ?: return null
        val sameLang = all.filter { it.locale.language == loc.language && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        if (sameLang.isEmpty()) return null
        // exact country first (en-IN for Hinglish), then quality, then neural/network voices over compact offline ones
        return sameLang.sortedWith(compareByDescending<Voice> { if (it.locale.country == loc.country) 1 else 0 }
            .thenByDescending { it.quality }.thenByDescending { if (it.isNetworkConnectionRequired) 1 else 0 }.thenByDescending { it.latency.let { l -> -l } }).first()
    }

    private val hinglish = setOf("hai", "hain", "nahi", "kya", "tum", "aap", "bhai", "karo", "kar", "hoga", "tha", "thi", "aur", "lekin", "abhi", "bahut", "accha", "theek", "dekho", "kuch", "mujhe", "apne", "raha", "rahi", "wala", "kyunki", "matlab", "kaise", "kyu", "padega", "mera", "meri", "tera", "teri", "hum", "yaar", "chalo", "bolo", "sunn", "kaha", "kahan", "kal", "aaj")
    private val es = setOf("el", "la", "los", "las", "que", "de", "por", "para", "con", "una", "pero", "como", "está", "muy", "hola", "gracias", "y", "es")
    private val fr = setOf("le", "les", "des", "est", "une", "pas", "pour", "avec", "bonjour", "merci", "je", "vous", "nous", "mais", "très", "et", "que", "qui")
    private val de = setOf("der", "die", "das", "und", "ist", "nicht", "ich", "du", "mit", "für", "aber", "ein", "eine", "wir", "sie", "auch")
    private val pt = setOf("não", "você", "uma", "para", "com", "muito", "obrigado", "olá", "mas", "está", "isso", "então")
    private val it = setOf("il", "che", "per", "con", "non", "sono", "una", "ciao", "grazie", "molto", "anche", "questo", "come")

    fun detect(s: String, fallback: String): Locale {
        var dev = 0; var tamil = 0; var tel = 0; var ben = 0; var guj = 0; var kan = 0; var mal = 0; var pun = 0; var ara = 0; var cyr = 0; var han = 0; var kana = 0; var hang = 0; var thai = 0; var greek = 0
        for (c in s) when (c.code) {
            in 0x0900..0x097F -> dev++; in 0x0B80..0x0BFF -> tamil++; in 0x0C00..0x0C7F -> tel++; in 0x0980..0x09FF -> ben++; in 0x0A80..0x0AFF -> guj++
            in 0x0C80..0x0CFF -> kan++; in 0x0D00..0x0D7F -> mal++; in 0x0A00..0x0A7F -> pun++; in 0x0600..0x06FF -> ara++; in 0x0400..0x04FF -> cyr++
            in 0x4E00..0x9FFF -> han++; in 0x3040..0x30FF -> kana++; in 0xAC00..0xD7AF -> hang++; in 0x0E00..0x0E7F -> thai++; in 0x0370..0x03FF -> greek++
        }
        val top = listOf(dev to "hi-IN", tamil to "ta-IN", tel to "te-IN", ben to "bn-IN", guj to "gu-IN", kan to "kn-IN", mal to "ml-IN", pun to "pa-IN", ara to "ar", cyr to "ru-RU",
            kana to "ja-JP", hang to "ko-KR", han to "zh-CN", thai to "th-TH", greek to "el-GR").maxByOrNull { it.first }!!
        if (top.first > 0) return Locale.forLanguageTag(top.second)
        val w = s.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        fun score(set: Set<String>) = w.count { it in set }
        val scores = listOf("hi" to score(hinglish), "es" to score(es), "fr" to score(fr), "de" to score(de), "pt" to score(pt), "it" to score(it))
        val best = scores.maxByOrNull { it.second }!!
        if (best.second >= 2 && best.second * 100 / maxOf(1, w.size) >= 15) return when (best.first) {
            "hi" -> Locale.Builder().setLanguage("en").setRegion("IN").setVariant("hinglish").build()   // romanised Hindi: the natural Hindi voice, or an Indian-English phone voice
            "es" -> Locale.forLanguageTag("es-ES"); "fr" -> Locale.FRANCE; "de" -> Locale.GERMANY; "pt" -> Locale.forLanguageTag("pt-BR"); else -> Locale.ITALY
        }
        val fb = fallback.ifEmpty { "en" }
        return if (fb.startsWith("en")) Locale.forLanguageTag("en-IN".takeIf { Locale.getDefault().country == "IN" } ?: "en-US") else Locale.forLanguageTag(fb)
    }
}
