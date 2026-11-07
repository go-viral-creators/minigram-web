package com.kafisir.wetalks.data

import java.text.Normalizer
import java.util.Locale

/** Why a message was flagged. [en] is shown to everybody, [hi] is added for Hindi / Hinglish messages. */
enum class ScamReason(val en: String, val hinglish: String, val hindi: String) {
    ASKS_SECRET("asks for OTP, PIN or password", "OTP, PIN ya password maang raha hai", "ओटीपी, पिन या पासवर्ड मांग रहा है"),
    ASKS_DETAILS("asks for bank or ID details", "bank ya ID ki details maang raha hai", "बैंक या पहचान की जानकारी मांग रहा है"),
    REMOTE_ACCESS("asks you to install a remote-control app", "remote app install karne ko bol raha hai", "रिमोट ऐप इंस्टॉल करने को कह रहा है"),
    SCREEN_SHARE("asks to share your screen", "screen share karne ko bol raha hai", "स्क्रीन शेयर करने को कह रहा है"),
    LINK_SHORT("link shortener", "chhota (shortened) link", "छोटा (शॉर्ट) लिंक"),
    LINK_FAKE("link looks like a fake site", "link nakli site jaisa hai", "लिंक नकली साइट जैसा है"),
    LINK_RISKY("risky link", "khatarnak link", "खतरनाक लिंक"),
    LINK_APK("link downloads an app file", "link se app file download hoti hai", "लिंक से ऐप फाइल डाउनलोड होती है"),
    THREAT("threatens to block or cut a service", "account/service band karne ki dhamki", "खाता या सेवा बंद करने की धमकी"),
    AUTHORITY("claims police, customs or a case against you", "police, customs ya case ki dhamki", "पुलिस, कस्टम या केस की धमकी"),
    KYC("asks you to update KYC or verify", "KYC update / verify karne ko bol raha hai", "केवाईसी अपडेट या वेरिफाई करने को कह रहा है"),
    PRIZE("promises a prize or lottery", "inaam ya lottery ka lalach", "इनाम या लॉटरी का लालच"),
    JOB("easy money or job offer", "aasaan kamai ya job ka lalach", "आसान कमाई या नौकरी का लालच"),
    INVEST("promises fast or guaranteed returns", "jaldi ya pakka munafa ka lalach", "जल्दी या पक्के मुनाफे का लालच"),
    LOAN("loan with a fee first", "pehle fees wala loan", "पहले फीस वाला लोन"),
    FEE("asks for a fee to release money or a parcel", "paisa ya parcel chhudane ke liye fees maang raha hai", "पैसा या पार्सल छुड़ाने के लिए फीस मांग रहा है"),
    UPI_TRICK("UPI money trick", "UPI paisa trick", "यूपीआई पैसे की चाल"),
    GIFT_CARDS("asks you to buy gift cards or send codes", "gift card kharid ke code bhejne ko bol raha hai", "गिफ्ट कार्ड खरीदकर कोड भेजने को कह रहा है"),
}

/** What the rules found in one message. All of it stays in memory on the phone, nothing is stored or logged. */
class ScamHits {
    val weights = LinkedHashMap<ScamReason, Int>()
    var secretRequest = false      // OTP / PIN / CVV / password / card number asked for
    var remoteInstall = false
    var upiTrick = false
    var suspiciousLink = false     // a link with enough bad signs on its own
    var hasLink = false
    var telegram = false
    var psa = false                // looks like a safety reminder ("never share your OTP")
    var aware = false              // talks ABOUT scams (warning a friend, reporting one)
    var weakBrand = false
    var weakPrize = false
    var weakRefund = false
    var timing = false
    fun add(r: ScamReason, w: Int) { if (w > (weights[r] ?: 0)) weights[r] = w }
}

object ScamRules {
    // ---------------------------------------------------------------- normalisation

    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF\\u00AD\\u093C\\u034F]")
    private val DEV_DIGITS = "०१२३४५६७८९"
    private val SPACED = Regex("(?<![a-z0-9])([a-z])(?:[ ._*\\-]+[a-z]){2,}(?![a-z0-9])")
    private val REPEAT = Regex("([a-z\\u0900-\\u097F])\\1{2,}")
    private val LEET_TOKEN = Regex("[a-z0-9@\\$!]{3,14}")

    /** NFKC, no invisible characters, Devanagari digits to ASCII, lower case. URLs are read from this form. */
    fun clean(raw: String): String {
        val s0 = if (raw.length > MAX_CHARS) raw.substring(0, MAX_CHARS) else raw
        val s1 = INVISIBLE.replace(Normalizer.normalize(s0, Normalizer.Form.NFKC), "")
        val sb = StringBuilder(s1.length)
        for (ch in s1) { val d = DEV_DIGITS.indexOf(ch); sb.append(if (d >= 0) ('0' + d) else ch) }
        return sb.toString().lowercase(Locale.ROOT)
    }

    /** [clean] plus: leetspeak words ("0tp", "p1n"), spaced letters ("o t p"), long repeats and a few spelling variants. */
    fun normalise(raw: String): String {
        var s = clean(raw)
        s = LEET_TOKEN.replace(s) { m -> unleet(m.value) }
        s = SPACED.replace(s) { m -> m.value.filter { it in 'a'..'z' } }
        s = REPEAT.replace(s) { m -> m.groupValues[1] }
        for ((rx, to) in SPELLING) s = rx.replace(s, to)
        return s
    }

    private fun unleet(t: String): String {
        var letters = 0; var digits = 0
        for (c in t) if (c in 'a'..'z') letters++ else digits++
        if (letters < 2 || digits == 0 || digits > 2) return t
        if (t.all { it in '0'..'9' }) return t
        val sb = StringBuilder(t.length)
        for (c in t) sb.append(when (c) { '0' -> 'o'; '1' -> 'i'; '3' -> 'e'; '4' -> 'a'; '5' -> 's'; '7' -> 't'; '@' -> 'a'; '$' -> 's'; '!' -> 'i'; else -> c })
        return sb.toString()
    }

    private val SPELLING = listOf(
        Regex("(?<![a-z])(?:pass ?ward|pasword|pasward|paswrd|psswrd|passwd|pswd|pwd)(?![a-z])") to "password",
        Regex("(?<![a-z])(?:aadhar|adhar|aadhaar|adhaar|aadhr)(?![a-z])") to "aadhaar",
        Regex("(?<![a-z])(?:acount|accont|acct|a/c)(?![a-z])") to "account",
        Regex("(?<![a-z])(?:nmbr|nbr|num|no\\.)(?![a-z])") to "number",
        Regex("(?<![a-z])(?:wi-fi|wi fi)(?![a-z])") to "wifi",
        Regex("(?<![a-z])(?:ur|ure)(?![a-z])") to "your",
        Regex("(?<![a-z])(?:pls|plz|pleez|plese)(?![a-z])") to "please",
        Regex("(?<![a-z])(?:btao|batao|bataiye|bataiyen|bataye|batayen|bataen|batado|batana|batain|bta)(?![a-z])") to "bata",
        Regex("(?<![a-z])(?:bhejo|bhejiye|bhejiyo|bhejie|bhejna|bhij?wao|bhejen|bhejein|bhej do|bhejdo|bhjo|bhej)(?![a-z])") to "bhej",
        Regex("(?<![a-z])(?:dedo|de do|dijiye|dijie|dijiyea|dena|denaa)(?![a-z])") to "de",
        Regex("(?<![a-z])(?:daalo|daalein|dalein|daalna|dalo|daal)(?![a-z])") to "daal",
        Regex("(?<![a-z])(?:shaer|sher|shayar)(?![a-z])") to "share",
        Regex("(?<![a-z])(?:kisiko|kisi ko)(?![a-z])") to "kisi ko",
        Regex("(?<![a-z])(?:nahin|nahi|nhi|nai)(?![a-z])") to "nahi",
        Regex("(?<![a-z])(?:mtt|mat)(?![a-z])") to "mat",
        Regex("(?<![a-z])(?:ru?pa?ya?|rupees?|rupaye|rupaiye|inr)(?![a-z])") to "rs",
        Regex("[₹]") to "rs ",
    )

    const val MAX_CHARS = 1500

    // ---------------------------------------------------------------- word boundaries that also work for Devanagari
    private const val B = "(?<![\\p{L}\\p{M}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{M}\\p{N}])"
    private const val GAP = "[^.!?\\n]"

    // ---------------------------------------------------------------- nouns (things a scammer wants)
    private val OTP = "(?:${B}otp${E}|${B}o\\s?t\\s?p${E}|one ?time ?(?:password|pass|pin|code)|verification ?code|security ?code|auth(?:entication)? ?code|ओ\\s?टी\\s?पी|वन[ -]?टाइम[ -]?(?:पासवर्ड|पिन|कोड)|[4-8][ -]?(?:digits?|अंकों का|अंक का)\\s*(?:code|otp|number|pin|कोड|नंबर)|(?:code|कोड)${GAP}{0,25}(?:sms|message|aaya|aya|आया|received|mila|मिला|phone|mobile|मोबाइल|फोन)|(?:sms|message|phone|mobile|मोबाइल|फोन|एसएमएस)${GAP}{0,15}(?:code|कोड))"
    private val PIN = "(?:(?:upi|atm|debit|card|mpin|mobile|netbanking|net ?banking|secret|login|tpin|your|apna|apni|tera|tumhara|aapka|आपका|अपना|तुम्हारा|यूपीआई|एटीएम|कार्ड|सीक्रेट)[ -]?pin(?!\\s*(?:code|kod|कोड))|${B}mpin${E}|${B}pin\\s*(?:number|no)${E}|(?:यूपीआई|यू\\.?पी\\.?आई|एटीएम|कार्ड|सीक्रेट|एम|आपका|अपना|तुम्हारा|अपने)\\s*पिन(?!\\s*कोड)|पिन(?=\\s*(?:भेज|बता|शेयर|दे[ंनवो]|दो|डाल|बोल|भर|मुझे|नंबर)))"
    private val CVV = "(?:${B}(?:cvv2?|cvc)${E}|card ?verification|सीवीवी|सी\\.?वी\\.?वी)"
    private val CARD = "(?:(?:card|कार्ड)\\s*(?:ki|ke|ka|की|के|का)?\\s*(?:number|details?|detail|नंबर|डिटेल|विवरण)|(?:credit|debit|atm)\\s*(?:card)?\\s*(?:number|details?|नंबर|डिटेल)|${B}expiry${E}|एक्सपायरी)"
    private val PASSWORD = "(?:(?<!time )(?<!time-)(?:password|passcode|पासवर्ड|पासकोड)|user ?id|login ?id|यूजर आईडी|user id)"
    private val BANK = "(?:bank ?(?:account|details?|detail|info|information|khata)|account ?(?:number|details?)|${B}ifsc${E}|खाता ?(?:नंबर|संख्या|विवरण|डिटेल)|बैंक ?(?:खाता|डिटेल|विवरण|अकाउंट)|खाते की डिटेल|net ?banking)"
    private val ID = "(?:aadhaar|आधार|${B}pan ?(?:card|number)${E}|पैन ?(?:कार्ड|नंबर)|${B}pan${E}\\s*(?:photo|ki photo)|पैन की फोटो)"

    // ---------------------------------------------------------------- verbs ("send me", "bata do", "भेजो")
    private val REQ = "(?:${B}(?:share|send|tell|give|provide|enter|forward|read|confirm|submit|reply|text|dictate|type|input|disclose|reveal|whatsapp|mail|email|update|verify|fill|dial|bhej|bata|de|daal|likh|bol|bharo|bharein|sunao|suna|padh)\\w*|बता\\w*|भेज\\w*|शेयर|दे(?:ं|ना|दो|दीजिए|दीजिये|दें)?${E}|दीजिए|दीजिये|दो${E}|डाल\\w*|बोल\\w*|लिख\\w*|भर\\w*|सुना\\w*|पढ़\\w*|कन्फर्म|वेरिफाई|अपडेट)"

    private fun near(a: String, b: String, gap: Int = 35): Regex =
        Regex("(?:$a)${GAP}{0,$gap}?(?:$b)|(?:$b)${GAP}{0,$gap}?(?:$a)")

    private val R_OTP_REQ = near(OTP, REQ)
    private val R_PIN_REQ = near(PIN, REQ)
    private val R_CVV_REQ = near(CVV, REQ)
    private val R_PW_REQ = near(PASSWORD, REQ)
    private val R_CARD_REQ = near(CARD, REQ)
    private val R_BANK_REQ = near(BANK, REQ)
    private val R_ID_REQ = near(ID, REQ)
    private val R_OTP_ANY = Regex(OTP)
    private val R_RETURN_IT = Regex("(?:wapas|वापस|back)${GAP}{0,12}(?:bhej|bata|send|reply|भेज|बता)|(?:bhej|send|reply|भेज)\\w*${GAP}{0,12}(?:wapas|वापस|back)")
    /** "OTP 4821" or "4821 is your OTP": the sender is giving a code, not asking for one. */
    private val R_OTP_GIVEN = Regex("(?:$OTP)${GAP}{0,8}?\\d{4,8}|\\d{4,8}${GAP}{0,16}?(?:$OTP)")
    private val R_OTP_TO_DRIVER = Regex("(?:driver|rider|delivery ?(?:boy|guy|person|partner|executive|wale|wala|man|agent)|courier ?(?:boy|guy|wale|wala)|pickup|captain|ड्राइवर|राइडर|डिलीवरी ?(?:वाले|बॉय|पार्टनर|वाला))${GAP}{0,45}(?:$OTP)|(?:$OTP)${GAP}{0,45}(?:driver|rider|delivery ?(?:boy|guy|person|partner|executive|wale|wala|man|agent)|courier ?(?:boy|guy|wale|wala)|captain|ड्राइवर|राइडर|डिलीवरी ?(?:वाले|बॉय|पार्टनर|वाला))")
    private val R_NOT_SECRET_CONTEXT = Regex("(?:wifi|router|laptop|zip|pdf|file|folder|tablet|door|gate|locker|netflix|prime|spotify|hotstar|office|guest|lock screen|wifi)\\s+(?:password|pin|pass|code)")

    /** Safety reminders: "never share your OTP", "bank kabhi OTP nahi maangta", "किसी को न बताएं". */
    private val R_PSA = Regex(
        "(?:never|(?:do|does|should|must|please|pls|kindly|can)\\s*(?:not|n't|nt)|don'?t|dont|shouldn'?t|cannot|can'?t|do not)\\s+(?:\\w+\\s+){0,4}?(?:share|tell|give|send|reveal|disclose|provide|forward|click|enter|type|reply|pass|ask)" +
        "|never\\s+(?:ask|asks|request|requests|call|calls)|(?:will|would)\\s+never" +
        "|(?:kisi ko|kisi se|kisi ke|kabhi|koi)?\\s*(?:ko\\s*)?(?:mat|na|nahi)\\s+(?:\\w+\\s+){0,3}?(?:share|bata|bhej|de|karein|kare|karo|dijiye|diya|dena)" +
        "|(?:share|bata|bhej)\\w*\\s+(?:mat|na|nahi)" +
        "|nahi\\s+(?:maang|mang|pooch|puch|puchh)\\w*|kabhi\\s+nahi\\s+\\w+" +
        "|(?:न|मत|नहीं)\\s+(?:\\S+\\s+){0,3}?(?:बता|शेयर|दे|भेज|करें|करे|दें)|(?:बताएं|शेयर|साझा)\\s+(?:न|ना|नहीं)|नहीं\\s+(?:मांग|मांगता|पूछ)|कभी\\s+नहीं"
    )
    /** Messages that talk ABOUT scams (a friend warning you, someone reporting one). */
    private val R_AWARE = Regex(
        "beware|be careful|warning:|fraud alert|scam alert|advisory|stay safe|cyber ?crime|helpline|1930|fake (?:call|calls|message|sms|link|kyc)|phishing|scam message|i just hung up|hung up|ignore (?:it|this)|delete it" +
        "|savdhan|sawdhan|chetavani|satark|mana kar diya|mana kiya|kaat diya|kaat di\\b|fraud se|fake call" +
        "|सावधान|चेतावनी|फर्जी|धोखाधड़ी|साइबर क्राइम|हेल्पलाइन|मना कर दिया|काट दिया|इग्नोर|अनदेखा"
    )

    // ---------------------------------------------------------------- remote access, screen share, apps
    private val REMOTE_TOOL = "(?:anydesk|any ?desk|teamviewer|team ?viewer|quick ?support|rustdesk|airdroid|aweray|alpemix|ammyy|supremo|rapid ?support|remote ?(?:access|support|control)|एनीडेस्क|एनी डेस्क|टीमव्यूअर|क्विकसपोर्ट)"
    private val INSTALL = "(?:install|instal|download|open|run|setup|इंस्टॉल|डाउनलोड|kholo|खोलो|chalu)"
    private val R_REMOTE_INSTALL = near(REMOTE_TOOL, INSTALL, 40)
    private val R_REMOTE_MENTION = Regex(REMOTE_TOOL)
    private val R_SCREEN_SHARE = Regex("(?:share|dikha\\w*|show|enable|start|on)\\s*(?:your|apni|apna|अपनी|your phone|the)?\\s*screen|screen\\s*(?:share|shar\\w*|dikha\\w*|शेयर)|स्क्रीन\\s*शेयर|अपनी स्क्रीन")
    private val R_APK_TEXT = Regex("(?:install|download|इंस्टॉल|डाउनलोड)${GAP}{0,25}(?:this|the|new|is|ye|yeh|यह|ये|इस|naya|नया)?\\s*(?:app|apk|application|ऐप|एप)")
    private val R_CLICK = Regex("(?:click|tap|open|visit|login|log in|sign in)\\s*(?:here|below|this|the|on|link)|link (?:par|pe|पर)|क्लिक|click karo|click karein|is link")

    // ---------------------------------------------------------------- pressure, bait, lures
    private val R_THREAT = Regex(
        "(?:account|a/?c|khata|खाता|खाते|card|sim|number|connection|service|licence|license|pan|aadhaar|upi|paytm|gpay|phonepe|whatsapp|instagram|netflix|policy|loan|parcel|package|courier|electricity|bijli|बिजली|सिम|कार्ड|नंबर|पैन|आधार|सेवा|कनेक्शन|क्रेडिट|डीटीएच|dth|yono|योनो)${GAP}{0,45}?(?:block(?:ed)?|suspend\\w*|clos(?:e|ed)|deactivat\\w*|frozen|freez\\w*|disconnect\\w*|expire\\w*|terminat\\w*|lock(?:ed)?|banned|cancel+ed|hold\\b|hacked|compromised|limited|band\\b|kat\\b|kat jayega|ruka|ब्लॉक|बंद|निलंबित|फ्रीज़|फ्रीज|कट|समाप्त|हैक|होल्ड|सीमित|रुका|काट)" +
        "|(?:block|suspend|close|disconnect|deactivat|freeze)\\w*\\s+(?:your|ur|aapka)${E}" +
        "|(?:account|khata|खाता)${GAP}{0,12}(?:hack|compromis)|unusual (?:login|sign-?in|activity)|असामान्य"
    )
    private val R_TIMING = Regex("(?:within|in|next)\\s+\\d+\\s*(?:hours?|hrs?|mins?|minutes?)|\\d+\\s*(?:ghante|ghanta|घंटे|घंटा|मिनट)|(?:today|tonight|immediately|urgently|asap|right now|at once|aaj\\s*(?:hi|raat)|turant|abhi|tatkal|तुरंत|आज\\s*(?:ही|रात)|अभी|last warning|final warning|आखिरी चेतावनी|last chance|only today|sirf aaj|सिर्फ आज)")
    private val R_AUTHORITY = Regex("police case|police (?:will|ke)|arrest|warrant|cyber ?crime branch|customs|illegal (?:items|activity|saaman|gair)|money laundering|case (?:is )?(?:registered|filed|darj)|narcotics|court notice|\\bcbi\\b|\\bed notice\\b|पुलिस केस|गिरफ्तार|वारंट|कस्टम|गैरकानूनी|मनी लॉन्ड्रिंग|केस दर्ज|साइबर क्राइम ब्रांच|police case|gair ?kanooni|illegal saaman|safe account|सेफ अकाउंट")
    private val R_KYC = Regex("${B}k\\.?y\\.?c${E}|केवाईसी|know your customer|re-?kyc|e-?kyc|verification pending|verify (?:your )?(?:account|identity|number|details)|account verification|(?:pan|aadhaar)\\s*(?:update|link)")
    private val R_PRIZE_STRONG = Regex(
        "you(?:'ve| have)? (?:have )?won|you are (?:the |a )?(?:lucky )?winner|lucky winner|lucky customer|claim (?:your|the)? ?(?:prize|reward|gift|cashback|voucher|bonus|free)|won (?:a|the)? ?(?:lottery|lucky draw)|you have been selected|free (?:iphone|gift card|recharge|laptop|data|netflix|ration|gift)|${B}kbc${E}" +
        "|(?:won|jeet\\w*|जीत\\w*)${GAP}{0,30}?(?:lakh|crore|lac|लाख|करोड़|rs${E}|\\d{4,})|(?:lakh|crore|लाख|करोड़)${GAP}{0,30}?(?:won|jeet|prize|inaam|lucky|लकी|इनाम)|(?:aapne|आपने)${GAP}{0,30}?(?:jeet|जीत)|भाग्यशाली (?:विजेता|ग्राहक)|lucky draw${GAP}{0,40}(?:rs|lakh|crore|claim|prize|number|sim)|(?:win|jeet|जीत)${GAP}{0,6}free|(?:sim|number|नंबर|सिम)${GAP}{0,20}(?:won|jeet|lucky|लकी)"
    )
    private val R_PRIZE_WEAK = Regex("lottery|lucky draw|jackpot|${B}prize|inaam|winner|badhai ho|congratulations!|congrats!|इनाम|लॉटरी|विजेता|बधाई हो|voucher|लकी ड्रा")
    private val R_JOB = Regex(
        "(?:earn|kamao|kamaye|kamayen|कमाएं|कमाओ|कमाये|income)${GAP}{0,30}?(?:daily|per day|a day|roz|rozana|har din|रोज़|रोज|प्रतिदिन|per task|every day|per month|from home|ghar baithe|घर बैठे|\\d{3,6}\\s*(?:rs|per))" +
        "|(?:work from home|ghar baithe|घर बैठे|part ?time job|पार्ट टाइम|data entry|डेटा एंट्री)${GAP}{0,60}?(?:earn|kamao|कमाएं|salary|income|\\d{3,6})" +
        "|(?:like|liking|subscribe)${GAP}{0,25}?(?:youtube|videos?|vids|वीडियो)${GAP}{0,30}?(?:earn|paid|rs|kamao|कमा|\\d{2,5})|(?:har|per|हर) ?task|task job|task${GAP}{0,30}?(?:rs|\\d{3,5})" +
        "|registration fees?|रजिस्ट्रेशन फीस|training kit|joining fee|no investment|without investment|बिना निवेश|bina invest|part time job chahiye|id card${GAP}{0,20}(?:rs|\\d{3,5})|(?:कमाएं|कमाओ|कमाना)${GAP}{0,30}?बिना|online paise kamao|ऑनलाइन पैसे कमाएं|हर टास्क|ghar se (?:amazon|kaam)|घर से अमेज़न"
    )
    private val R_INVEST = Regex(
        "(?:double|dugna|dugni|दोगुना|दुगना)${GAP}{0,15}?(?:money|paisa|paise|पैसा|पैसे|investment|amount)|guaranteed? (?:returns?|profit|income)|गारंटीड|100% ?(?:profit|returns?|safe|sure)|sure ?shot|stock tips?|trading group|vip (?:group|trading)" +
        "|invest${GAP}{0,30}?(?:get|pao|पाओ|and earn|return|double|\\d+ days|\\d+ din)|(?:invest|lagao|lagayen|लगाओ|लगाएं)${GAP}{0,15}?\\d{3,6}${GAP}{0,30}?(?:pao|get|पाओ|milega|मिलेगा|return)|paisa double|पैसा दोगुना"
    )
    private val R_CRYPTO = Regex("crypto|bitcoin|बिटकॉइन")
    private val R_LOAN = Regex(
        "(?:instant|pre-?approved|bina document|without (?:documents?|cibil|collateral|income proof)|बिना दस्तावेज़?|loan (?:approved|is approved|manzoor|मंज़ूर|approve ho gaya|pao|पाएं))${GAP}{0,40}?(?:loan|lakh|लोन|rs${E}|लाख)" +
        "|loan${GAP}{0,40}?(?:processing fee|insurance|gst|advance|fees?|charge|बीमा|जीएसटी)|(?:लोन|loan)${GAP}{0,20}?(?:मंज़ूर|approve)${GAP}{0,50}?(?:बीमा|insurance|पहले|first|pehle)|pehle insurance|पहले बीमा|loan.{0,25}pehle.{0,15}(?:insurance|fees)"
    )
    private val R_FEE = Regex(
        "(?:processing|clearance|delivery|handling|registration|activation|verification|re-?delivery|customs|security|joining|training)\\s*(?:fees?|charges?|duty|deposit|kit)|(?:insurance|gst|tax|customs|fine|duty|जीएसटी|बीमा|टैक्स|जुर्माना)\\s*(?:of\\s*|ke\\s*|के\\s*)?(?:rs\\.?\\s*)?\\d{2,6}" +
        "|(?:gst|tax|insurance)\\s*(?:ke|के)?\\s*\\d{2,6}|\\d{2,6}\\s*(?:rs\\s*)?(?:ka|ke|का|के)?\\s*(?:gst|tax|insurance|processing fee|delivery charge|जीएसटी|टैक्स|फीस)|(?:fees?|फीस)\\s*(?:rs\\.?\\s*)?\\d{2,6}|\\d{2,6}\\s*(?:rs\\s*)?(?:fee|fees|फीस|charge|चार्ज)" +
        "|account activate|खाता चालू करने के|activate karne|pay (?:rs\\.?\\s*)?\\d+${GAP}{0,30}(?:to (?:release|receive|claim|unlock)|processing fee)"
    )
    private val R_UPI = Regex(
        "(?:scan|स्कैन)${GAP}{0,30}?(?:qr|क्यूआर)${GAP}{0,40}?(?:receive|get|pao|पाएं|पाओ|refund|cashback|credit|money|rs${E}|रुपये|\\d)|(?:qr|क्यूआर)${GAP}{0,30}?(?:scan|स्कैन)${GAP}{0,50}?(?:receive|get|pao|पाएं|पाओ|refund|cashback|credit|\\d{2,})" +
        "|(?:enter|daal|डालें|डाल)${GAP}{0,20}?(?:upi|यूपीआई)${GAP}{0,15}?(?:pin|पिन)${GAP}{0,30}?(?:receive|get|pao|पाएं|पाओ|money|paisa|पैसे|रुपये)|(?:receive|pao|पाने|get)${GAP}{0,40}?(?:enter|daal|डालें)${GAP}{0,20}?(?:pin|पिन)" +
        "|collect ?request|कलेक्ट रिक्वेस्ट|(?:payment|paisa) request${GAP}{0,60}?(?:pin|enter|approve)|(?:approve|accept|manzoor|मंज़ूर)${GAP}{0,30}?(?:request|रिक्वेस्ट)" +
        "|(?:pay|bharo|bharein|de|भरें|दें|दो)${GAP}{0,6}?(?:rs\\.?\\s*)?1\\b\\s*(?:rs)?${GAP}{0,30}?(?:verify|activate|bonus|वेरिफाई|बोनस)|(?:1|एक)\\s*(?:rs|रुपया|रुपये)${GAP}{0,30}?(?:verify|वेरिफाई|bonus|बोनस)|(?:rs\\.?\\s*)1\\b${GAP}{0,20}?(?:verify|activate|वेरिफाई)|pay (?:rs\\.?\\s*)?(?:1|10)\\b${GAP}{0,40}?(?:verify|activate|bonus|ration)" +
        "|(?:refund|रिफंड)${GAP}{0,50}?(?:scan|qr|upi pin|यूपीआई पिन)|(?:scan|स्कैन)${GAP}{0,40}?(?:पिन|pin)${GAP}{0,20}?(?:daal|डाल|enter)|scan ke baad${GAP}{0,12}pin|स्कैन के बाद"
    )
    private val R_WRONG_TRANSFER = Regex("(?:by mistake|accidentally|galti se|गलती से)${GAP}{0,40}?(?:sent|transfer|bhej|भेज|चले गए|refund)${GAP}{0,60}?(?:return|wapas|वापस|back|refund|approve|मंज़ूर)")
    private val R_GIFTCARD = Regex("(?:buy|kharid\\w*|खरीद\\w*)${GAP}{0,20}?(?:gift ?cards?|google play|itunes|gift card|गिफ्ट कार्ड)${GAP}{0,50}?(?:code|codes|कोड|bhej|भेज|send)|(?:itunes|google play|gift ?cards?|गिफ्ट कार्ड)${GAP}{0,30}?(?:code|codes|कोड)")
    private val R_REFUND = Regex("refund|रिफंड|cashback|कैशबैक|reward points|रिवॉर्ड")
    private val R_BRAND = Regex("(?:sbi|hdfc|icici|axis bank|pnb|kotak|paytm|phonepe|gpay|google pay|amazon|flipkart|jio|airtel|bsnl|trai|rbi|income ?tax|uidai|epfo|irctc|\\blic\\b|fedex|dhl|india post|bses|npci|netflix|dear (?:customer|user|sir|madam)|प्रिय ग्राहक|एसबीआई|पेटीएम|अमेज़न|फ्लिपकार्ट|जियो|एयरटेल|इनकम टैक्स|आधार|योनो|yono)")

    class Parsed(val clean: String, val norm: String)

    fun parse(raw: String): Parsed = Parsed(clean(raw), normalise(raw))

    /** Runs every text rule. [urlsText] is the URL-friendly cleaned text, [n] the fully normalised text. */
    fun scanText(n: String, hits: ScamHits) {
        // wifi/netflix/office passwords and codes are everyday talk
        val t = R_NOT_SECRET_CONTEXT.replace(n, "x")
        hits.psa = R_PSA.containsMatchIn(t)
        hits.aware = R_AWARE.containsMatchIn(t)

        var strong = false
        val otpMention = R_OTP_ANY.containsMatchIn(t)
        val given = otpMention && R_OTP_GIVEN.containsMatchIn(t)
        val toDriver = otpMention && R_OTP_TO_DRIVER.containsMatchIn(t)
        val returnIt = otpMention && R_RETURN_IT.containsMatchIn(t)
        if (R_OTP_REQ.containsMatchIn(t) && !toDriver && !given) strong = true
        if (returnIt) { strong = true; hits.psa = false }
        if (R_PIN_REQ.containsMatchIn(t)) strong = true
        if (R_CVV_REQ.containsMatchIn(t)) strong = true
        if (R_PW_REQ.containsMatchIn(t)) strong = true
        if (R_CARD_REQ.containsMatchIn(t)) strong = true
        if (strong && !hits.psa && !hits.aware) { hits.secretRequest = true; hits.add(ScamReason.ASKS_SECRET, 6) }
        else if (!strong && otpMention && !hits.psa && !given && !toDriver) { /* an OTP is only mentioned, not asked for */ }

        if (!hits.psa && !hits.aware) {
            if (R_BANK_REQ.containsMatchIn(t)) hits.add(ScamReason.ASKS_DETAILS, 3)
            else if (R_ID_REQ.containsMatchIn(t)) hits.add(ScamReason.ASKS_DETAILS, 3)
        }

        if (R_REMOTE_INSTALL.containsMatchIn(t)) { hits.remoteInstall = true; hits.add(ScamReason.REMOTE_ACCESS, 5) }
        else if (R_REMOTE_MENTION.containsMatchIn(t)) hits.add(ScamReason.REMOTE_ACCESS, 2)
        if (R_SCREEN_SHARE.containsMatchIn(t)) hits.add(ScamReason.SCREEN_SHARE, 2)
        if (R_APK_TEXT.containsMatchIn(t)) hits.add(ScamReason.LINK_APK, 2)

        if (R_THREAT.containsMatchIn(t)) hits.add(ScamReason.THREAT, 2)
        if (R_TIMING.containsMatchIn(t)) hits.timing = true
        if (R_AUTHORITY.containsMatchIn(t)) hits.add(ScamReason.AUTHORITY, 3)
        if (R_KYC.containsMatchIn(t)) hits.add(ScamReason.KYC, 2)
        if (R_PRIZE_STRONG.containsMatchIn(t)) hits.add(ScamReason.PRIZE, 4) else if (R_PRIZE_WEAK.containsMatchIn(t)) hits.weakPrize = true
        if (R_JOB.containsMatchIn(t)) hits.add(ScamReason.JOB, 3)
        if (R_INVEST.containsMatchIn(t)) hits.add(ScamReason.INVEST, 3)
        if (R_LOAN.containsMatchIn(t)) hits.add(ScamReason.LOAN, 3)
        if (R_FEE.containsMatchIn(t)) hits.add(ScamReason.FEE, 3)
        if (R_UPI.containsMatchIn(t)) { hits.upiTrick = true; hits.add(ScamReason.UPI_TRICK, 4) }
        if (R_WRONG_TRANSFER.containsMatchIn(t)) hits.add(ScamReason.UPI_TRICK, 3)
        if (R_GIFTCARD.containsMatchIn(t)) hits.add(ScamReason.GIFT_CARDS, 5)
        if (R_REFUND.containsMatchIn(t)) hits.weakRefund = true
        if (R_BRAND.containsMatchIn(t)) hits.weakBrand = true
    }

    fun hasClickWord(n: String) = R_CLICK.containsMatchIn(n)

    // ---------------------------------------------------------------- links

    private val URL_RX = Regex("(?<![\\w@./-])(?:(?:https?://|www\\.)[^\\s<>\"')\\]]+|(?:[a-z0-9][a-z0-9-]*\\.)+[a-z]{2,24}(?::\\d+)?(?:/[^\\s<>\"')\\]]*)?)")
    private val IP_RX = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
    private val KNOWN_TLDS = hashSetOf(
        "com", "in", "net", "org", "co", "io", "me", "ly", "gl", "gle", "app", "xyz", "top", "click", "live", "icu", "vip", "site", "online", "shop", "buzz", "cfd", "sbs", "rest",
        "monster", "work", "loan", "loans", "gq", "tk", "ml", "cf", "ga", "link", "info", "biz", "gd", "gy", "at", "cc", "ws", "pw", "su", "ru", "cn", "uk", "us", "club", "fun", "store",
        "tech", "space", "website", "bank", "ink", "bid", "win", "date", "stream", "download", "review", "trade", "party", "science", "zip", "mov", "today", "life", "world", "support",
        "help", "digital", "cloud", "email", "finance", "money", "cash", "fund", "cyou", "lol", "pk", "bd", "lk", "np", "ae", "sa", "tv", "to", "la", "sh", "ai", "gov", "edu", "page",
        "one", "pro", "name", "mobi", "asia", "xin", "kim", "mom", "best", "quest", "bar", "cam", "casa", "mx", "br", "de", "fr", "es", "it", "nl", "jp", "kr", "sg", "my", "ph", "th",
        "vn", "au", "nz", "ca", "za", "ng", "ke", "tr", "ir", "ua", "pl", "sbi", "gift", "rocks", "city", "company", "center", "network", "services", "agency", "solutions", "capital",
        "exchange", "bike", "ooo", "pics", "mom", "wiki",
    )
    private val SUSPICIOUS_TLDS = hashSetOf(
        "xyz", "top", "click", "icu", "vip", "site", "online", "shop", "buzz", "cfd", "sbs", "rest", "monster", "work", "loan", "loans", "gq", "tk", "ml", "cf", "ga", "link", "live",
        "support", "help", "cyou", "quest", "fun", "bid", "win", "party", "stream", "download", "review", "trade", "science", "zip", "mov", "cam", "casa", "pw", "ws", "su", "gift", "rocks",
    )
    private val SHORTENERS = hashSetOf(
        "bit.ly", "tinyurl.com", "cutt.ly", "t.co", "rb.gy", "is.gd", "shorturl.at", "goo.gl", "ow.ly", "buff.ly", "tiny.cc", "rebrand.ly", "bl.ink", "s.id", "v.gd", "clck.ru",
        "qr.ae", "t.ly", "shorte.st", "adf.ly", "u.to", "zpr.io", "bitly.com", "short.io", "tr.ee", "lnkd.in", "shrtco.de", "x.co", "chilp.it", "1url.com", "cli.re", "soo.gd", "han.gl",
    )
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "ac", "edu", "nic", "res", "sch")
    /** Registrable domains we never flag (the sites people really share every day). */
    private val TRUSTED = hashSetOf(
        "google.com", "youtube.com", "youtu.be", "g.co", "goo.gl", "gle", "app.goo.gl", "googleusercontent.com", "gstatic.com", "googleapis.com", "microsoft.com", "live.com", "office.com", "outlook.com",
        "zoom.us", "whatsapp.com", "wa.me", "instagram.com", "facebook.com", "fb.com", "twitter.com", "x.com", "linkedin.com", "github.com", "wikipedia.org", "amazon.in", "amazon.com",
        "flipkart.com", "irctc.co.in", "makemytrip.com", "thehindu.com", "indiatimes.com", "ndtv.com", "bbc.com", "bbc.co.uk", "sbi.co.in", "onlinesbi.sbi", "sbicard.com", "hdfcbank.com",
        "icicibank.com", "axisbank.com", "kotak.com", "paytm.com", "phonepe.com", "npci.org.in", "rbi.org.in", "jio.com", "airtel.in", "apple.com", "spotify.com", "netflix.com", "hotstar.com",
        "swiggy.com", "zomato.com", "telegram.org", "reddit.com", "medium.com", "stackoverflow.com", "dropbox.com", "notion.so", "canva.com", "figma.com", "pinterest.com", "snapchat.com",
        "play.google.com", "flipkart.in", "myntra.com", "bookmyshow.com", "olacabs.com", "uber.com", "meesho.com", "nykaa.com", "paytmbank.com", "amazonaws.com", "cloudfront.net", "wetalks.in",
    )
    private val BRANDS: Map<String, Set<String>> = mapOf(
        "sbi" to setOf("sbi.co.in", "onlinesbi.sbi", "sbicard.com", "sbilife.co.in", "sbi.bank.in", "sbimf.com"),
        "yono" to setOf("sbi.co.in", "onlinesbi.sbi"),
        "hdfc" to setOf("hdfcbank.com", "hdfc.com", "hdfclife.com", "hdfcergo.com", "hdfcsec.com", "hdfcfund.com"),
        "icici" to setOf("icicibank.com", "icicilombard.com", "iciciprulife.com", "icicidirect.com", "icicipruamc.com"),
        "axis" to setOf("axisbank.com", "axismf.com"),
        "paytm" to setOf("paytm.com", "paytmbank.com", "paytm.in"),
        "phonepe" to setOf("phonepe.com"),
        "gpay" to setOf("pay.google.com", "google.com"),
        "amazon" to setOf("amazon.in", "amazon.com", "amazon.co.uk", "amazon.jobs", "amazonaws.com", "primevideo.com", "amzn.to", "amzn.in", "a.co"),
        "flipkart" to setOf("flipkart.com", "flipkart.in"),
        "netflix" to setOf("netflix.com", "nflx.com"),
        "jio" to setOf("jio.com", "jiomart.com", "jiopay.com", "ril.com"),
        "airtel" to setOf("airtel.in", "airtel.com", "airtelbank.com"),
        "fedex" to setOf("fedex.com"),
        "whatsapp" to setOf("whatsapp.com", "whatsapp.net", "wa.me"),
        "instagram" to setOf("instagram.com"),
        "microsoft" to setOf("microsoft.com", "live.com", "office.com", "outlook.com"),
        "incometax" to setOf("incometax.gov.in", "incometaxindia.gov.in"),
        "uidai" to setOf("uidai.gov.in"),
        "irctc" to setOf("irctc.co.in", "irctc.com"),
        "epfo" to setOf("epfindia.gov.in", "epfindia.com"),
        "parivahan" to setOf("parivahan.gov.in"),
        "bses" to setOf("bsesdelhi.com", "bsesdelhi.in"),
        "npci" to setOf("npci.org.in"),
        "trai" to setOf("trai.gov.in"),
    )
    private val BRAND_SUFFIX = setOf("bank", "card", "life", "pay", "kyc", "yono", "secure", "login", "care", "support", "rewards", "reward", "online", "net", "india", "id", "app", "update", "verify", "bill", "refund")
    private val LURE_WORDS = listOf("kyc", "verify", "update", "secure", "login", "signin", "reward", "refund", "claim", "bonus", "free", "gift", "offer", "prize", "lucky", "bill", "pay", "fine", "appeal", "helpdesk", "billing", "cancel", "limit", "suspend", "unlock", "confirm", "account", "auth", "wallet", "cash", "earn", "invest", "pan-card", "pancard", "apk", "txn", "track", "delivery", "fee")

    /** What is wrong with one link. [score] is only a weight; the reasons feed the banner text. */
    class UrlCheck(val url: String, val score: Int, val reasons: Set<ScamReason>, val telegram: Boolean)

    fun extractUrls(cleaned: String): List<String> {
        val out = ArrayList<String>(2)
        for (m in URL_RX.findAll(cleaned)) {
            var u = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', '।')
            if (u.length < 4) continue
            if (!u.contains("://") && !u.startsWith("www.")) {
                // a bare "word.tld" only counts if the tld is a real one
                val host = u.substringBefore('/').substringBefore(':')
                val tld = host.substringAfterLast('.')
                if (tld !in KNOWN_TLDS || host.count { it == '.' } < 1) continue
                if (host.length < 5) continue
            }
            out.add(u)
            if (out.size >= 6) break
        }
        return out
    }

    private fun registrable(host: String): String {
        val l = host.split('.')
        if (l.size <= 2) return host
        val tld = l.last(); val sld = l[l.size - 2]
        return if (tld.length == 2 && sld in SECOND_LEVEL) l.takeLast(3).joinToString(".") else l.takeLast(2).joinToString(".")
    }

    private fun mapGlyph(s: String) = s.replace('i', 'l').replace('1', 'l').replace('0', 'o').replace("rn", "m").replace("vv", "w")

    private fun segmentHas(seg: String, tok: String): Boolean =
        seg == tok || (tok.length >= 5 && seg.contains(tok)) ||
            (seg.startsWith(tok) && seg.removePrefix(tok) in BRAND_SUFFIX) || (seg.endsWith(tok) && seg.removeSuffix(tok) in BRAND_SUFFIX)

    fun checkUrl(url: String): UrlCheck {
        val reasons = LinkedHashSet<ScamReason>()
        var score = 0
        val hasScheme = url.contains("://")
        val rest = url.substringAfter("://")
        val scheme = if (hasScheme) url.substringBefore("://") else "https"
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val pathAndQuery = rest.removePrefix(authority)
        val hostPort = authority.substringAfterLast('@')
        val host = hostPort.substringBefore(':').removePrefix("www.").trim('.')
        if (host.isEmpty()) return UrlCheck(url, 0, emptySet(), false)
        val reg = registrable(host)
        val telegram = reg == "t.me" || reg == "telegram.me" || reg == "telegram.dog"

        fun add(w: Int, r: ScamReason) { score += w; reasons.add(r) }

        if (authority.contains('@') && hasScheme) add(4, ScamReason.LINK_FAKE)
        if (pathAndQuery.substringBefore('?').endsWith(".apk") || host.endsWith(".apk") || Regex("\\.(apk|xapk|scr|exe)(?:[?#]|$)").containsMatchIn(pathAndQuery)) add(5, ScamReason.LINK_APK)
        if (IP_RX.matches(host)) { add(4, ScamReason.LINK_RISKY); return UrlCheck(url, score, reasons, telegram) }
        if (host.contains("xn--") || host.any { it.code > 127 }) add(4, ScamReason.LINK_FAKE)

        val isTrusted = reg in TRUSTED || host in TRUSTED || reg.endsWith(".gov.in") || reg.endsWith(".nic.in") || reg.endsWith(".edu")
        if (isTrusted && !(reg == "wa.me")) return UrlCheck(url, score, reasons, telegram)

        if (reg in SHORTENERS) {
            if (reg == "goo.gl" && pathAndQuery.startsWith("/maps")) return UrlCheck(url, score, reasons, telegram)
            add(2, ScamReason.LINK_SHORT)
        }
        if (reg == "wa.me") {
            val text = pathAndQuery.substringAfter("text=", "")
            if (text.length > 40) add(3, ScamReason.LINK_RISKY)
            return UrlCheck(url, score, reasons, telegram)
        }

        val labels = host.split('.')
        val tld = labels.last()
        val segs = host.split('.', '-')
        // brand look-alike: the name of a bank or app inside a domain that is not theirs
        var brandHit = false
        val mappedSegs = segs.map { mapGlyph(it) }
        for ((b, official) in BRANDS) {
            val mb = mapGlyph(b)
            if (mappedSegs.any { segmentHas(it, mb) } && reg !in official && !official.any { host.endsWith(it) }) { brandHit = true; break }
        }
        if (brandHit) add(5, ScamReason.LINK_FAKE)
        val lure = LURE_WORDS.count { host.contains(it) }
        if (lure >= 2) add(3, ScamReason.LINK_FAKE) else if (lure == 1 && (host.contains('-') || tld in SUSPICIOUS_TLDS)) add(2, ScamReason.LINK_FAKE)
        if (tld in SUSPICIOUS_TLDS) add(2, ScamReason.LINK_RISKY)
        if (host.count { it == '-' } >= 3) add(2, ScamReason.LINK_RISKY)
        if (labels.size >= 5) add(2, ScamReason.LINK_RISKY)
        if (scheme == "http" && (lure >= 1 || brandHit || Regex("bank|pay|kyc|login|secure|verify|card").containsMatchIn(host))) add(2, ScamReason.LINK_RISKY)
        return UrlCheck(url, score, reasons, telegram)
    }
}
