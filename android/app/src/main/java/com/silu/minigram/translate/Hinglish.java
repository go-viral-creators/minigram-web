package com.silu.minigram.translate;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hinglish (Roman Hindi) fallback. ML Kit can identify Latin-script Hindi ("hi-Latn") but cannot translate it,
 * so we transliterate to Devanagari (common-word dictionary + phonetic rules) and let the on-device Hindi
 * model translate that. It is approximate by nature (Romanised spelling is not standardised), so the UI labels
 * such results as approximate and always keeps the original visible. Pure Java (unit-tested).
 */
public final class Hinglish {
    private Hinglish() {}

    private static final Map<String, String> WORDS = new HashMap<>();
    static {
        String[] w = {
            "hai","है","hain","हैं","tha","था","thi","थी","the","थे","ho","हो","hoga","होगा","hogi","होगी","hu","हूँ","hun","हूँ",
            "ka","का","ki","की","ke","के","ko","को","se","से","me","में","mein","में","main","मैं","par","पर","pe","पे","tak","तक",
            "aur","और","ya","या","lekin","लेकिन","magar","मगर","toh","तो","to","तो","bhi","भी","hi","ही","na","ना","nahi","नहीं","nahin","नहीं","mat","मत",
            "kya","क्या","kyu","क्यों","kyun","क्यों","kyunki","क्योंकि","kaise","कैसे","kaisa","कैसा","kab","कब","kahan","कहाँ","kaun","कौन","kitna","कितना","kitne","कितने",
            "mai","मैं","tum","तुम","tu","तू","aap","आप","hum","हम","wo","वो","woh","वो","ye","ये","yeh","यह","is","इस","us","उस","yahan","यहाँ","wahan","वहाँ",
            "mera","मेरा","meri","मेरी","mere","मेरे","tera","तेरा","teri","तेरी","tumhara","तुम्हारा","tumhari","तुम्हारी","apna","अपना","hamara","हमारा",
            "kal","कल","aaj","आज","abhi","अभी","phir","फिर","pehle","पहले","baad","बाद","subah","सुबह","shaam","शाम","raat","रात","din","दिन","baje","बजे",
            "ghar","घर","kaam","काम","paisa","पैसा","paise","पैसे","khana","खाना","paani","पानी","chai","चाय","dost","दोस्त","bhai","भाई","behen","बहन","yaar","यार",
            "accha","अच्छा","achha","अच्छा","theek","ठीक","bahut","बहुत","bohot","बहुत","thoda","थोड़ा","zyada","ज़्यादा","kam","कम","sab","सब","kuch","कुछ","koi","कोई",
            "karna","करना","kar","कर","karo","करो","karta","करता","karti","करती","kiya","किया","raha","रहा","rahi","रही","rahe","रहे","gaya","गया","gayi","गई","chalo","चलो",
            "aana","आना","aao","आओ","aaya","आया","jana","जाना","jao","जाओ","dekho","देखो","bolo","बोलो","bata","बता","batao","बताओ","suno","सुनो","le","ले","lo","लो","de","दे","do","दो",
            "nikalna","निकलना","pahunch","पहुँच","milte","मिलते","milna","मिलना","chahiye","चाहिए","sakta","सकता","sakti","सकती","wala","वाला","wali","वाली","liye","लिए",
            "yr","यार","yar","यार","kardo","कर दो","karo","करो","dedo","दे दो","dena","देना","bhej","भेज","bhejo","भेजो","bhejna","भेजना","hlw","हैलो","hello","हेलो","hlo","हैलो",
            "kb","कब","nhi","नहीं","nai","नहीं","ni","नहीं","h","है","hn","हाँ","ha","हाँ","thik","ठीक","thikse","ठीक से","bol","बोल","bola","बोला","bole","बोले","boli","बोली",
            "rha","रहा","rhi","रही","rhe","रहे","hogya","हो गया","hogaya","हो गया","gya","गया","gyi","गई","gaye","गए","wapas","वापस","mujhe","मुझे","tujhe","तुझे","tumhe","तुम्हें",
            "unko","उनको","usko","उसको","isko","इसको","apko","आपको","aapko","आपको","humko","हमको","tumko","तुमको","unka","उनका","uska","उसका","iska","इसका","apka","आपका","aapka","आपका",
            "bas","बस","jaldi","जल्दी","der","देर","paisa","पैसा","kitna","कितना","kuchh","कुछ","sirf","सिर्फ","bilkul","बिल्कुल","pakka","पक्का","zaroor","ज़रूर","shayad","शायद","lekin","लेकिन",
            "kyunki","क्योंकि","isliye","इसलिए","jaise","जैसे","tab","तब","jab","जब","agar","अगर","warna","वरना","sath","साथ","saath","साथ","wapis","वापस","chalega","चलेगा","chalta","चलता",
            "dekh","देख","dekha","देखा","suna","सुना","samajh","समझ","samjha","समझा","padh","पढ़","likh","लिख","soch","सोच","mil","मिल","mila","मिला","mili","मिली","lag","लग","laga","लगा",
            "hoga","होगा","hogi","होगी","honge","होंगे","tha","था","thi","थी","kaam","काम","dhanda","धंधा","update","अपडेट","dia","दिया","diya","दिया","liya","लिया","kiya","किया","hua","हुआ","hui","हुई",
            "kuchh","कुछ","wahi","वही","wohi","वही","ek","एक","do","दो","teen","तीन","char","चार","paanch","पाँच","bar","बार","baar","बार","kal","कल","parso","परसों","aaj","आज","din","दिन","raat","रात",
            "mat","मत","bhool","भूल","yaad","याद","pyaar","प्यार","gussa","ग़ुस्सा","khush","खुश","dukh","दुख","maaf","माफ़","sorry","सॉरी","thanks","थैंक्स","please","प्लीज़","plz","प्लीज़","accha","अच्छा",
            "haan","हाँ","han","हाँ","ji","जी","arre","अरे","oye","ओए","sahi","सही","pata","पता","maaf","माफ़","shukriya","शुक्रिया","dhanyavaad","धन्यवाद","namaste","नमस्ते","pyaar","प्यार"
        };
        for (int i = 0; i + 1 < w.length; i += 2) WORDS.put(w[i], w[i + 1]);
    }

    private static final String[][] CONS = {
        {"chh","छ"},{"ksh","क्ष"},{"gya","ज्ञ"},{"shh","ष"},{"kh","ख"},{"gh","घ"},{"ch","च"},{"jh","झ"},{"th","थ"},{"dh","ध"},
        {"ph","फ"},{"bh","भ"},{"sh","श"},{"ng","ंग"},{"k","क"},{"g","ग"},{"c","क"},{"j","ज"},{"t","त"},{"d","द"},{"n","न"},{"p","प"},
        {"b","ब"},{"m","म"},{"y","य"},{"r","र"},{"l","ल"},{"v","व"},{"w","व"},{"s","स"},{"h","ह"},{"f","फ"},{"z","ज़"},{"q","क"},{"x","क्स"}
    };
    private static final String[][] VOWELS = {   // {roman, independent, matra}
        {"aa","आ","ा"},{"ai","ऐ","ै"},{"au","औ","ौ"},{"ee","ई","ी"},{"ii","ई","ी"},{"oo","ऊ","ू"},{"uu","ऊ","ू"},{"ei","ऐ","ै"},
        {"a","अ",""},{"i","इ","ि"},{"u","उ","ु"},{"e","ए","े"},{"o","ओ","ो"}
    };

    /** Common chat spellings -> a form the dictionary knows ("nhi" -> "nahi", "rha" -> "raha", ...). */
    static String normalize(String text) {
        String s = " " + text + " ";
        String[][] m = {{" nhi "," nahi "},{" nahin "," nahi "},{" rha "," raha "},{" rhi "," rahi "},{" rhe "," rahe "},{" hogya "," ho gaya "},{" hogaya "," ho gaya "},
            {" kr "," kar "},{" krdo "," kar do "},{" kardo "," kar do "},{" krna "," karna "},{" kro "," karo "},{" mje "," mujhe "},{" tmhe "," tumhe "},{" tum"+"hara "," tumhara "},
            {" kya"+"h "," kya hai "},{" kb "," kab "},{" kyu "," kyun "},{" abhi"+"tk "," abhi tak "},{" mt "," mat "},{" jyda "," zyada "},{" jada "," zyada "},{" thikse "," theek se "},{" thik "," theek "}};
        for (String[] p : m) { while (s.contains(p[0])) s = s.replace(p[0], p[1]); }
        return s.trim();
    }

    public static String toDevanagari(String text) {
        text = normalize(text);
        StringBuilder out = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if (Character.isLetter(c) && c < 128) word.append(c);
            else {
                if (word.length() > 0) { out.append(convertWord(word.toString())); word.setLength(0); }
                if (i < text.length()) out.append(c == '?' ? '?' : c);
            }
        }
        return out.toString();
    }

    static String convertWord(String w) {
        String lower = w.toLowerCase(Locale.ROOT);
        String d = WORDS.get(lower);
        if (d != null) return d;
        StringBuilder out = new StringBuilder();
        boolean afterConsonant = false;
        int i = 0;
        outer:
        while (i < lower.length()) {
            for (String[] v : VOWELS) {
                if (lower.startsWith(v[0], i)) {
                    out.append(afterConsonant ? v[2] : v[1]);
                    afterConsonant = false; i += v[0].length(); continue outer;
                }
            }
            for (String[] c : CONS) {
                if (lower.startsWith(c[0], i)) {
                    if (afterConsonant) out.append('्');        // virama: consonant cluster
                    out.append(c[1]); afterConsonant = true; i += c[0].length(); continue outer;
                }
            }
            out.append(lower.charAt(i)); afterConsonant = false; i++;
        }
        return out.toString();
    }

    /** Dictionary words that are also common English words, so they do not prove a text is Hinglish. */
    private static final java.util.Set<String> AMBIGUOUS = new java.util.HashSet<>(java.util.Arrays.asList(
        "to","do","me","is","hi","the","main","par","kar","le","de","us","na","ho","pe","tu","ye","kam","bata","pehle","as","a","i","or","in","un","ya","sab","wo","h","ha","ni","bar","hello","sorry","thanks","please","plz","update","tab","mil","lag","din","ek","char","bas","time"));

    /**
     * True when a Latin-script text is probably Romanised Hindi. ML Kit often labels such text "en" or "und",
     * so this checks how many tokens are unmistakably Hindi function words / common words.
     */
    public static boolean looksRoman(String text) {
        if (text == null) return false;
        int tokens = 0, strong = 0;
        for (String raw : normalize(text.toLowerCase(Locale.ROOT)).split("[^a-z]+")) {
            if (raw.isEmpty()) continue;
            tokens++;
            if (WORDS.containsKey(raw) && !AMBIGUOUS.contains(raw)) strong++;
        }
        if (tokens == 0) return false;
        if (tokens <= 2) return strong >= 1 && strong * 2 >= tokens;
        return strong >= 1 && strong * 100 >= tokens * 25;
    }

    /** Language-ID codes that mean "Hindi written in Latin letters". */
    public static boolean isRomanHindi(String langCode) {
        return langCode != null && langCode.toLowerCase(Locale.ROOT).startsWith("hi-latn");
    }
}
