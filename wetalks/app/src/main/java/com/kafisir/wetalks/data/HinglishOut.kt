package com.kafisir.wetalks.data

/**
 * Natural Hinglish for the app language "Hinglish": how people really write on WhatsApp, not textbook Hindi.
 * Hindi comes back from the translator in Devanagari; this turns it into everyday Roman letters, keeps the English words people
 * actually use (profile, group, privacy, settings …) as English, and writes Urdu-origin words (maaf, zaroor, khushi) the way they are said.
 */
object HinglishOut {
    /** Labels that stay exactly as they are: translating them gives silly results ("Me" -> "mujhe"). */
    private val KEEP = setOf("me", "chats", "chat", "calls", "call", "status", "settings", "profile", "group", "groups", "channel", "channels", "contacts", "online", "offline", "ok", "okay",
        "ai", "wetalks", "plus", "premium", "pin", "pinned", "mute", "block", "report", "app", "admin", "admins", "members", "member", "link", "links", "gif", "gifs", "video", "videos",
        "audio", "voice", "photo", "photos", "docs", "files", "media", "theme", "dark", "light", "system", "beta", "new", "chats & calls", "discover", "saved messages", "stickers", "emoji")
    fun keep(english: String) = english.trim().trimEnd('.', '!').lowercase() in KEEP

    // longest phrases first: Devanagari -> everyday Hinglish (English where people say it in English)
    private val PHRASES: List<Pair<String, String>> = listOf(
        "गोपनीयता और सुरक्षा" to "privacy & security", "गोपनीयता एवं सुरक्षा" to "privacy & security", "निजता एवं सुरक्षा" to "privacy & security", "निजता और सुरक्षा" to "privacy & security",
        "प्रोफ़ाइल संपादित करें" to "profile edit karo", "प्रोफाइल संपादित करें" to "profile edit karo", "प्रोफ़ाइल संपादित करे" to "profile edit karo", "प्रोफ़ाइल बदलें" to "profile edit karo",
        "रद्द करें" to "cancel karo", "बंद करें" to "band karo", "चालू करें" to "on karo", "बंद करे" to "band karo", "जारी रखें" to "continue karo", "आगे बढ़ें" to "aage badho",
        "साझा करें" to "share karo", "शेयर करें" to "share karo", "कॉपी करें" to "copy karo", "उत्तर दें" to "reply karo", "जवाब दें" to "reply karo", "आगे भेजें" to "forward karo", "अग्रेषित करें" to "forward karo",
        "म्यूट करें" to "mute karo", "ब्लॉक करें" to "block karo", "अनब्लॉक करें" to "unblock karo", "रिपोर्ट करें" to "report karo", "पिन करें" to "pin karo", "सहेजें" to "save karo", "सेव करें" to "save karo",
        "हटाएं" to "delete karo", "हटाएँ" to "delete karo", "मिटाएं" to "delete karo", "मिटाएँ" to "delete karo", "डिलीट करें" to "delete karo", "जोड़ें" to "add karo", "खोजें" to "search karo", "ढूंढें" to "search karo",
        "संपादित करें" to "edit karo", "संपादित करे" to "edit karo", "भेजें" to "bhejo", "चुनें" to "chuno", "देखें" to "dekho", "खोलें" to "kholo", "लिखें" to "likho", "दर्ज करें" to "enter karo",
        "अनुमति दें" to "allow karo", "अनुमति दे" to "allow karo", "पुष्टि करें" to "confirm karo", "फिर से कोशिश करें" to "dobara try karo", "पुनः प्रयास करें" to "dobara try karo", "फिर कोशिश करें" to "dobara try karo",
        "वापस जाएं" to "wapas jao", "वापस जाएँ" to "wapas jao", "डाउनलोड करें" to "download karo", "अपलोड करें" to "upload karo", "लॉग आउट" to "logout", "साइन आउट" to "logout", "साइन इन" to "sign in", "लॉग इन" to "login",
        "नई चैट" to "new chat", "नया समूह" to "new group", "नया चैनल" to "new channel", "सभी पढ़ा हुआ" to "sab read", "पढ़ा हुआ" to "read", "अपठित" to "unread", "सूचनाएं" to "notifications", "सूचनाएँ" to "notifications", "सूचना" to "notification",
        "एंड-टू-एंड एन्क्रिप्टेड" to "end-to-end encrypted", "एन्क्रिप्टेड" to "encrypted", "एन्क्रिप्शन" to "encryption",
    )
    private val NOUNS: Map<String, String> = mapOf(
        "प्रोफ़ाइल" to "profile", "प्रोफाइल" to "profile", "समूह" to "group", "समूहों" to "groups", "ग्रुप" to "group", "चैनल" to "channel", "चैनलों" to "channels", "चैट" to "chat", "चैट्स" to "chats", "चैटें" to "chats",
        "गोपनीयता" to "privacy", "निजता" to "privacy", "सुरक्षा" to "security", "सुरक्षित" to "secure", "संदेश" to "message", "संदेशों" to "messages", "मैसेज" to "message", "संदेश।" to "message",
        "सेटिंग" to "settings", "सेटिंग्स" to "settings", "सेटिंगें" to "settings", "खाता" to "account", "खाते" to "account", "अकाउंट" to "account", "खोज" to "search", "भाषा" to "language", "भाषाएँ" to "languages", "भाषाएं" to "languages",
        "आवाज़" to "voice", "आवाज" to "voice", "तस्वीर" to "photo", "तस्वीरें" to "photos", "फ़ोटो" to "photo", "फोटो" to "photo", "वीडियो" to "video", "ऑडियो" to "audio", "कॉल" to "call", "कॉल्स" to "calls", "संपर्क" to "contacts", "संपर्कों" to "contacts",
        "पासवर्ड" to "password", "नाम" to "name", "नंबर" to "number", "फ़ोन" to "phone", "फोन" to "phone", "ऐप" to "app", "ऐप्स" to "apps", "थीम" to "theme", "डार्क" to "dark", "लाइट" to "light", "मोड" to "mode",
        "फ़ाइल" to "file", "फाइल" to "file", "फ़ाइलें" to "files", "फाइलें" to "files", "दस्तावेज़" to "document", "दस्तावेज" to "document", "स्टिकर" to "sticker", "स्टिकर्स" to "stickers", "इमोजी" to "emoji",
        "स्थिति" to "status", "ऑनलाइन" to "online", "ऑफ़लाइन" to "offline", "ब्लॉक" to "block", "म्यूट" to "mute", "पिन" to "pin", "लिंक" to "link", "एडमिन" to "admin", "व्यवस्थापक" to "admin", "सदस्य" to "members", "सदस्यों" to "members",
        "सूचियाँ" to "lists", "सूची" to "list", "विकल्प" to "options", "विवरण" to "details", "जानकारी" to "info", "बारे" to "baare", "नेटवर्क" to "network", "इंटरनेट" to "internet", "डेटा" to "data", "स्टोरेज" to "storage",
        "सहायता" to "help", "मदद" to "help", "समर्थन" to "support", "त्रुटि" to "error", "गलती" to "galti", "चेतावनी" to "warning", "अपडेट" to "update", "संस्करण" to "version", "ब्राउज़र" to "browser", "कैमरा" to "camera", "माइक्रोफ़ोन" to "mic", "माइक" to "mic",
        "अनुमति" to "permission", "अनुमतियाँ" to "permissions", "सुरक्षा कोड" to "security code", "कोड" to "code", "कुंजी" to "key", "चाबी" to "key", "बैकअप" to "backup", "अनुवाद" to "translate", "अनुवादित" to "translated", "स्क्रीन" to "screen",
        "सेव" to "save", "संपादित" to "edit", "प्रीमियम" to "premium", "प्लस" to "plus", "मैं" to "main", "मुझे" to "mujhe",
    )
    private val WORDS: Map<String, String> = mapOf(
        "मैं" to "main", "मुझे" to "mujhe", "मुझसे" to "mujhse", "मेरा" to "mera", "मेरी" to "meri", "मेरे" to "mere", "तुम" to "tum", "तू" to "tu", "आप" to "aap", "आपका" to "aapka", "आपकी" to "aapki", "आपके" to "aapke", "आपको" to "aapko", "आपसे" to "aapse",
        "हम" to "hum", "हमारा" to "hamara", "हमारी" to "hamari", "हमारे" to "hamare", "हमें" to "humein", "तुम्हें" to "tumhe", "तुम्हारा" to "tumhara", "तुम्हारी" to "tumhari", "उसे" to "use", "इसे" to "ise", "उन्हें" to "unhe", "उसका" to "uska", "उसकी" to "uski",
        "यह" to "ye", "ये" to "ye", "वह" to "wo", "वो" to "wo", "वे" to "wo", "है" to "hai", "हैं" to "hain", "था" to "tha", "थी" to "thi", "थे" to "the", "हो" to "ho", "होगा" to "hoga", "होगी" to "hogi", "हूँ" to "hoon", "हूं" to "hoon",
        "कर" to "kar", "करें" to "karo", "करो" to "karo", "करना" to "karna", "करने" to "karne", "किया" to "kiya", "करता" to "karta", "करती" to "karti", "करते" to "karte", "की" to "ki", "कि" to "ki", "का" to "ka", "के" to "ke", "को" to "ko", "से" to "se", "में" to "mein", "पर" to "par", "पे" to "pe",
        "और" to "aur", "या" to "ya", "तो" to "to", "भी" to "bhi", "ही" to "hi", "नहीं" to "nahi", "ना" to "na", "न" to "na", "क्या" to "kya", "कैसे" to "kaise", "कौन" to "kaun", "कब" to "kab", "कहाँ" to "kahan", "कहां" to "kahan", "क्यों" to "kyun", "कितना" to "kitna", "कितने" to "kitne",
        "यहाँ" to "yahan", "यहां" to "yahan", "वहाँ" to "wahan", "वहां" to "wahan", "अब" to "ab", "अभी" to "abhi", "फिर" to "phir", "बस" to "bas", "सब" to "sab", "सभी" to "sabhi", "सारे" to "saare", "कुछ" to "kuch", "बहुत" to "bahut", "थोड़ा" to "thoda", "लिए" to "liye", "साथ" to "saath",
        "बाद" to "baad", "पहले" to "pehle", "ज़रूर" to "zaroor", "जरूर" to "zaroor", "ज़रूरी" to "zaroori", "जरूरी" to "zaroori", "अच्छा" to "accha", "अच्छी" to "achhi", "अच्छे" to "acche", "ठीक" to "theek", "हाँ" to "haan", "हां" to "haan", "जी" to "ji", "भाई" to "bhai", "दोस्त" to "dost", "दोस्तों" to "dosto",
        "लेकिन" to "lekin", "मगर" to "magar", "क्योंकि" to "kyunki", "अगर" to "agar", "जब" to "jab", "तब" to "tab", "इस" to "is", "उस" to "us", "इन" to "in", "उन" to "un", "अपना" to "apna", "अपनी" to "apni", "अपने" to "apne", "सकते" to "sakte", "सकता" to "sakta", "सकती" to "sakti",
        "चाहिए" to "chahiye", "चाहते" to "chahte", "रहा" to "raha", "रही" to "rahi", "रहे" to "rahe", "गया" to "gaya", "गई" to "gayi", "गयी" to "gayi", "गए" to "gaye", "दिया" to "diya", "लिया" to "liya", "देना" to "dena", "लेना" to "lena", "जाना" to "jaana", "आना" to "aana", "कोई" to "koi", "किसी" to "kisi",
        "सिर्फ़" to "sirf", "सिर्फ" to "sirf", "केवल" to "sirf", "हमेशा" to "hamesha", "कभी" to "kabhi", "आज" to "aaj", "कल" to "kal", "नया" to "naya", "नई" to "nayi", "नये" to "naye", "नए" to "naye", "पुराना" to "purana", "पुराने" to "purane", "सारा" to "saara", "पूरा" to "poora", "पूरी" to "poori",
        "माफ़" to "maaf", "माफ" to "maaf", "माफ़ी" to "maafi", "शुक्रिया" to "shukriya", "धन्यवाद" to "thanks", "कृपया" to "please", "स्वागत" to "welcome", "नमस्ते" to "namaste", "खुशी" to "khushi", "ख़ुशी" to "khushi", "ख़राब" to "kharab", "खराब" to "kharab", "प्यार" to "pyaar", "ज़िंदगी" to "zindagi", "वक़्त" to "waqt", "समय" to "time",
        "लोग" to "log", "लोगों" to "logon", "व्यक्ति" to "person", "बात" to "baat", "बातें" to "baatein", "बातचीत" to "baat-cheet", "चीज़" to "cheez", "चीजें" to "cheezein", "काम" to "kaam", "दिन" to "din", "रात" to "raat", "साल" to "saal", "महीना" to "mahina", "घंटा" to "ghanta", "मिनट" to "minute", "सेकंड" to "second",
        "चालू" to "on", "बंद" to "off", "सक्रिय" to "active", "निष्क्रिय" to "inactive", "खाली" to "khaali", "भरा" to "bhara", "सफल" to "successful", "विफल" to "failed", "ग़लत" to "galat", "गलत" to "galat", "सही" to "sahi", "सुरक्षित।" to "secure",
    )

    private val PHRASE_SORTED = PHRASES.sortedByDescending { it.first.length }
    private val DEV = Regex("[\\u0900-\\u097F]+")

    /** Devanagari text -> everyday Hinglish. Latin letters, numbers and punctuation are kept. */
    fun convert(dev: String): String {
        var s = dev
        for ((hi, rm) in PHRASE_SORTED) if (s.contains(hi)) s = s.replace(hi, "\u0001$rm\u0002")
        s = DEV.replace(s) { m -> NOUNS[m.value] ?: WORDS[m.value] ?: roman(m.value) }
        return s.replace("\u0001", "").replace("\u0002", "").replace(Regex("\\s+"), " ").trim().let { if (it.isNotEmpty()) it else dev }
    }

    // ---------------- letter-by-letter fallback for every other word
    private val CONS = mapOf('क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h", 'ळ' to "l")
    private val NUKTA = mapOf('क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f")
    private val VOW = mapOf('अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऋ' to "ri", 'ऑ' to "o")
    private val MAT = mapOf('ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ृ' to "ri", 'ॉ' to "o", 'ॅ' to "e")
    private const val VIRAMA = '्'; private const val NUKTA_CH = '़'

    private class Syl(var c: String, var v: String?, var schwa: Boolean)

    fun roman(w: String): String {
        val out = ArrayList<Syl>(); val pre = StringBuilder()
        var i = 0
        while (i < w.length) {
            val ch = w[i]
            when {
                ch in CONS -> {
                    var c = CONS.getValue(ch); i++
                    if (i < w.length && w[i] == NUKTA_CH) { NUKTA[ch]?.let { c = it }; i++ }
                    if (ch == 'क' && i + 1 < w.length && w[i] == VIRAMA && w[i + 1] == 'ष') { c = "ksh"; i += 2 }
                    if (ch == 'ज' && i + 1 < w.length && w[i] == VIRAMA && w[i + 1] == 'ञ') { c = "gy"; i += 2 }
                    if (i < w.length && w[i] == VIRAMA) { i++; pre.append(c); continue }                  // conjunct: the consonant joins the next syllable
                    val mat = if (i < w.length) MAT[w[i]] else null
                    if (mat != null) { out.add(Syl(pre.toString() + c, mat, false)); pre.clear(); i++ } else { out.add(Syl(pre.toString() + c, null, true)); pre.clear() }
                }
                ch in VOW -> { out.add(Syl(pre.toString(), VOW.getValue(ch), false)); pre.clear(); i++ }
                ch == 'ं' || ch == 'ँ' -> { out.add(Syl("n", null, false)); i++ }
                ch == 'ः' -> { out.add(Syl("h", null, false)); i++ }
                else -> i++
            }
        }
        if (pre.isNotEmpty()) out.add(Syl(pre.toString(), null, false))
        // schwa deletion: the last one always, and one inside a word when it sits between two sounded syllables (badalna, not badalana)
        for (k in out.indices.reversed()) {
            val s = out[k]
            if (!s.schwa) continue
            if (k == out.lastIndex) { s.schwa = false; continue }
            val prevSounded = k > 0 && (out[k - 1].v != null || out[k - 1].schwa)
            val next = out[k + 1]
            if (k > 0 && prevSounded && (next.v != null)) s.schwa = false
        }
        val sb = StringBuilder()
        for (s in out) { sb.append(s.c); sb.append(s.v ?: if (s.schwa) "a" else "") }
        return sb.toString()
    }
}
