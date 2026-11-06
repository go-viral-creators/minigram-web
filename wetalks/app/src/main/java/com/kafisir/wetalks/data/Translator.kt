package com.kafisir.wetalks.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Text translation between 100+ languages (Google's neural translation, called from the phone).
 * "hi-Latn" means Hindi written in English letters (Hinglish): translated to Hindi, then romanised.
 */
object Translator {
    class Result(val text: String, val detected: String)

    suspend fun translate(text: String, target: String, source: String = "auto"): Result = withContext(Dispatchers.IO) {
        val hinglish = target == "hi-Latn"
        val tl = if (hinglish) "hi" else target
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$source&tl=$tl&dt=t&dt=rm&q=" + URLEncoder.encode(text, "UTF-8")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        }
        if (c.responseCode !in 200..299) throw IllegalStateException("Translation service is busy, try again")
        val root = JSONArray(c.inputStream.bufferedReader().readText())
        val segs = root.optJSONArray(0) ?: throw IllegalStateException("Could not translate")
        val tr = StringBuilder(); val rom = StringBuilder()
        for (i in 0 until segs.length()) {
            val s = segs.optJSONArray(i) ?: continue
            if (!s.isNull(0)) tr.append(s.optString(0)) else if (s.length() > 2 && !s.isNull(2)) rom.append(s.optString(2))
        }
        val out = if (hinglish) HinglishOut.convert(tr.toString()) else tr.toString()
        Result(out.trim(), root.optString(2))
    }
}
