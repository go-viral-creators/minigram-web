package com.kafisir.wetalks

import com.kafisir.wetalks.voice.Hinglish
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HinglishTest {
    private fun init() {
        val d = listOf("src/main/assets/hinglish", "app/src/main/assets/hinglish").map { File(it) }.first { it.exists() }
        Hinglish.set(File(d, "hi_lex.tsv").readLines(Charsets.UTF_8), File(d, "en_words.txt").readLines())
    }

    // expected values come from the Python prototype in tools/tts/hinglish.py, so both versions stay identical
    private val cases = listOf(
        Triple("ab tum dekhna bhai, main apne application ke andar aise feature dal dunga, ki logon ko majbur hokar idhar aana padega.", "अब तुम देखना भाई, मैं अपने application के अंदर ऐसे feature डाल दूँगा, की लोगों को मजबूर होकर इधर आना पड़ेगा.", true),
        Triple("yaar tu kal kya kar raha tha, mujhe office jaana hai aur tumhe bhi aana hai", "यार तू कल क्या कर रहा था, मुझे office जाना है और तुम्हें भी आना है", true),
        Triple("Mera presentation tomorrow hai aur main nervous hoon", "मेरा presentation tomorrow है और मैं nervous हूँ", true),
        Triple("kal mujhe office jaana hai", "कल मुझे office जाना है", true),
        Triple("Hello how are you doing today my friend", "Hello how अरे you doing today म्य friend", false),
        Triple("bhai kal milte hain, tension mat lo sab theek ho jayega", "भाई कल मिलते हैं, tension मत लो सब ठीक हो जाएगा", true),
        Triple("main ghar pahunch gaya hoon thodi der mein call karta hoon", "मैं घर पहुँच गया हूँ थोड़ी देर में call करता हूँ", true),
        Triple("tumhara kaam khatam hua ya nahi abhi tak", "तुम्हारा काम ख़त्म हुआ या नहीं अभी तक", true),
        Triple("aaj ka match bahut accha tha tumne dekha kya", "आज का match बहुत अच्छा था तुमने देखा क्या", true),
        Triple("mujhe nahi pata wo kab aayega lekin jab bhi aaye mujhe bata dena", "मुझे नहीं पता वो कब आएगा लेकिन जब भी आए मुझे बता देना", true),
        Triple("kya haal hai bhai kaisa chal raha hai sab kuch", "क्या हाल है भाई कैसा चल रहा है सब कुछ", true),
        Triple("Aishwarya kal Bengaluru aa rahi hai", "ऐश्वर्या कल Bengaluru आ रही है", true),
        Triple("dhanyavaad, sab kuch badhiya hai", "धन्यवाद, सब कुछ बढ़िया है", true),
        Triple("I will call you tomorrow at five", "I will call you tomorrow अत five", false),
        Triple("pyaar mohabbat zindagi", "प्यार मोहब्बत ज़िंदगी", true),
        Triple("chhote bacche school ja rahe hain", "छोटे बच्चे स्कूल जा रहे हैं", true),
        Triple("khatarnak ghanta chhodo", "खतर्नक घंटा छोड़ो", true),
        Triple("kal subah 9 baje milte hain", "कल सुबह 9 बजे मिलते हैं", true),
        Triple("bro kal party hai aana zaroor", "bro कल party है आना ज़रूर", true),
    )

    @Test fun matchesThePrototype() {
        init()
        for ((src, want, hing) in cases) { assertEquals(want, Hinglish.toDevanagari(src)); assertEquals(src, hing, Hinglish.looksHinglish(src)) }
    }

    @Test fun englishIsNotTouched() {
        init()
        assertFalse(Hinglish.looksHinglish("Hello how are you doing today my friend"))
        assertFalse(Hinglish.looksHinglish("I will call you tomorrow at five"))
        assertEquals("application", Hinglish.convertWord("application"))
    }

    @Test fun letterRules() {
        init()
        assertEquals("दोस्त", Hinglish.translit("dost")); assertEquals("कमल", Hinglish.translit("kamal")); assertEquals("करना", Hinglish.translit("karna")); assertEquals("मस्त", Hinglish.translit("mast")); assertEquals("चलती", Hinglish.translit("chalti"))
        assertEquals("पहुंच", Hinglish.translit("pahunch"))
    }

    @Test fun segmentsUseTheRightVoice() {
        init()
        val s = Hinglish.segments("Bhai ek problem ho gayi yr Tu help kar dega?")
        assertEquals(listOf("hi", "en", "hi", "en", "hi"), s.map { it.first })
        assertEquals("problem", s[1].second.trim()); assertEquals("help", s[3].second.trim())
        assertTrue(s[0].second.contains("भाई")); assertTrue(s[2].second.contains("यार")); assertTrue(s[4].second.trim().endsWith("?"))
    }

    // the extra word list holds verb forms (banana, samjhao …); the letter rules cover the rest
    private fun initWithExtra() {
        val d = listOf("src/main/assets/hinglish", "app/src/main/assets/hinglish").map { File(it) }.first { it.exists() }
        Hinglish.set(File(d, "hi_lex.tsv").readLines(Charsets.UTF_8), File(d, "en_words.txt").readLines(), File(d, "hi_lex_extra.tsv").readLines(Charsets.UTF_8))
    }

    @Test fun commonVerbFormsAreReadAsHindi() {
        initWithExtra()
        val cases = mapOf(
            "mujhe plan banana hai" to "मुझे plan बनाना है", "usne khana banaya" to "उसने खाना बनाया", "mujhe vishwas nahi hota" to "मुझे विश्वास नहीं होता",
            "usko samjhao" to "उसको समझाओ", "hum milkar karenge" to "हम मिलकर करेंगे", "kya karun" to "क्या करूँ", "video call nahi ho pa rahi" to "video call नहीं हो पा रही",
            "main kal bolunga" to "मैं कल बोलूँगा", "tum kab aaoge" to "तुम कब आओगे", "sab theek ho jayega" to "सब ठीक हो जाएगा")
        for ((a, b) in cases) assertEquals(a, b, Hinglish.toDevanagari(a))
    }

    @Test fun englishIsNeverMistakenForHinglishBecauseOfGeneratedForms() {
        initWithExtra()
        assertFalse(Hinglish.looksHinglish("The late train came to the lane near the gate and we waited"))
        assertFalse(Hinglish.looksHinglish("I will call you tomorrow at five"))
        assertTrue(Hinglish.looksHinglish("bhai kal milte hain, tension mat lo sab theek ho jayega"))
    }

    @Test fun mixedSentencesKeepEachLanguageInItsOwnPiece() {
        initWithExtra()
        val segs = Hinglish.segments("yaar ye app bahut slow chal raha hai")
        assertEquals(listOf("hi", "en", "hi", "en", "hi"), segs.map { it.first })
        assertEquals(listOf("app", "slow"), segs.filter { it.first == "en" }.map { it.second.trim() })
    }
}
