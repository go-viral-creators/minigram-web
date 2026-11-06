package com.kafisir.wetalks.ui

import android.content.Context
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.kafisir.wetalks.data.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

class Lang(val code: String, val native: String, val english: String, val rtl: Boolean = false)

/**
 * App-language support. Every fixed label in the app is collected in assets/i18n/strings.json. When the user picks a language the
 * labels are translated once (on their own phone, with Google's translation service) and cached, so switching is instant afterwards.
 * Text that people type, names and messages are never in the table, so they are never touched.
 */
object I18n {
    val languages = listOf(
        Lang("en", "English", "English"), Lang("hi", "हिन्दी", "Hindi"), Lang("hi-Latn", "Hinglish", "Hindi (Latin script)"), Lang("ta", "தமிழ்", "Tamil"), Lang("te", "తెలుగు", "Telugu"),
        Lang("mr", "मराठी", "Marathi"), Lang("bn", "বাংলা", "Bengali"), Lang("gu", "ગુજરાતી", "Gujarati"), Lang("kn", "ಕನ್ನಡ", "Kannada"), Lang("ml", "മലയാളം", "Malayalam"),
        Lang("pa", "ਪੰਜਾਬੀ", "Punjabi"), Lang("or", "ଓଡ଼ିଆ", "Odia"), Lang("ur", "اردو", "Urdu", true), Lang("ne", "नेपाली", "Nepali"), Lang("si", "සිංහල", "Sinhala"),
        Lang("es", "Español", "Spanish"), Lang("fr", "Français", "French"), Lang("ru", "Русский", "Russian"), Lang("ar", "العربية", "Arabic", true), Lang("pt", "Português", "Portuguese"),
        Lang("de", "Deutsch", "German"), Lang("it", "Italiano", "Italian"), Lang("tr", "Türkçe", "Turkish"), Lang("id", "Bahasa Indonesia", "Indonesian"), Lang("ms", "Bahasa Melayu", "Malay"),
        Lang("vi", "Tiếng Việt", "Vietnamese"), Lang("th", "ไทย", "Thai"), Lang("ja", "日本語", "Japanese"), Lang("ko", "한국어", "Korean"), Lang("zh-CN", "中文 (简体)", "Chinese (Simplified)"),
        Lang("zh-TW", "中文 (繁體)", "Chinese (Traditional)"), Lang("nl", "Nederlands", "Dutch"), Lang("pl", "Polski", "Polish"), Lang("uk", "Українська", "Ukrainian"), Lang("fa", "فارسی", "Persian", true),
        Lang("sw", "Kiswahili", "Swahili"), Lang("tl", "Filipino", "Filipino"), Lang("el", "Ελληνικά", "Greek"), Lang("iw", "עברית", "Hebrew", true), Lang("cs", "Čeština", "Czech"),
        Lang("ro", "Română", "Romanian"), Lang("hu", "Magyar", "Hungarian"), Lang("sv", "Svenska", "Swedish"), Lang("af", "Afrikaans", "Afrikaans"), Lang("am", "አማርኛ", "Amharic"),
        Lang("my", "မြန်မာ", "Burmese"), Lang("km", "ខ្មែរ", "Khmer"), Lang("ha", "Hausa", "Hausa"), Lang("so", "Soomaali", "Somali"), Lang("uz", "Oʻzbek", "Uzbek"),
    )
    fun byCode(c: String) = languages.firstOrNull { it.code == c } ?: languages[0]

    var translateTo by mutableStateOf("en"); private set
    var speechLang by mutableStateOf(""); private set          // language spoken in voice notes ("" = phone default)
    fun setTranslateTo(c: Context, code: String) { translateTo = code; sp(c).edit().putString("translate_to", code).apply() }
    fun setSpeechLang(c: Context, code: String) { speechLang = code; sp(c).edit().putString("speech_lang", code).apply() }
    var lang by mutableStateOf("en"); private set
    var version by mutableIntStateOf(0); private set
    var progress by mutableStateOf<Float?>(null); private set
    private var registry: List<String> = emptyList()
    private var table: Map<String, String> = emptyMap()
    private var job: Job? = null
    private fun sp(c: Context) = c.getSharedPreferences("wt_i18n", Context.MODE_PRIVATE)

    fun load(c: Context) {
        registry = try { org.json.JSONArray(c.assets.open("i18n/strings.json").bufferedReader().readText()).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { emptyList() }
        lang = sp(c).getString("lang", null) ?: "en"
        table = loadCache(c, lang)
        val dev = java.util.Locale.getDefault().language.let { if (it == "he") "iw" else it }
        translateTo = sp(c).getString("translate_to", null) ?: (languages.firstOrNull { it.code == dev }?.code ?: "en")
        speechLang = sp(c).getString("speech_lang", "") ?: ""
    }

    private fun loadCache(c: Context, code: String): Map<String, String> {
        val j = sp(c).getString("tbl_$code", null) ?: return emptyMap()
        return try { val o = JSONObject(j); o.keys().asSequence().associateWith { o.getString(it) } } catch (e: Exception) { emptyMap() }
    }

    /** Looks up a fixed label; anything not in the table (names, messages, numbers) comes back unchanged. */
    fun t(s: String): String = if (lang == "en") s else table[s] ?: s

    fun missing(c: Context, code: String): Int = registry.count { it !in loadCache(c, code) }

    fun set(c: Context, code: String) {
        job?.cancel()
        lang = code; sp(c).edit().putString("lang", code).apply()
        table = if (code == "en") emptyMap() else loadCache(c, code)
        version++
        if (code == "en") { progress = null; return }
        val todo = registry.filter { it !in table }
        if (todo.isEmpty()) { progress = null; return }
        progress = 0f
        job = com.kafisir.wetalks.data.Repo.scope.launch(Dispatchers.IO) {
            val acc = HashMap(table); val gate = Semaphore(6); var done = 0
            try {
                todo.chunked(40).forEach { chunk ->
                    chunk.map { s -> async { gate.withPermit { val r = if (code == "hi-Latn" && com.kafisir.wetalks.data.HinglishOut.keep(s)) Translator.Result(s, "en") else try { Translator.translate(s, code, "en") } catch (e: Exception) { null }; if (r != null) synchronized(acc) { acc[s] = r.text } } } }.awaitAll()
                    done += chunk.size
                    table = HashMap(acc); version++; progress = done.toFloat() / todo.size
                    sp(c).edit().putString("tbl_$code", JSONObject(acc as Map<*, *>).toString()).apply()
                }
            } finally { progress = null }
        }
    }
}

/** Drop-in replacement for Material's Text: fixed app labels are shown in the chosen app language. */
@Composable
fun Text(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null,
         fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null,
         textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified, overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true,
         maxLines: Int = Int.MAX_VALUE, minLines: Int = 1, onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = LocalTextStyle.current) {
    @Suppress("UNUSED_VARIABLE") val v = I18n.version            // recompose when translations arrive
    androidx.compose.material3.Text(I18n.t(text), modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style)
}

@Composable
fun Text(text: AnnotatedString, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null,
         fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null,
         textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified, overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true,
         maxLines: Int = Int.MAX_VALUE, minLines: Int = 1, onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = LocalTextStyle.current) {
    androidx.compose.material3.Text(text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout = onTextLayout ?: {}, style = style)
}
