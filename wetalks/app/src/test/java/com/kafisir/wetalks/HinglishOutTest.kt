package com.kafisir.wetalks

import com.kafisir.wetalks.data.HinglishOut
import org.junit.Assert.*
import org.junit.Test

class HinglishOutTest {
    @Test fun uiPhrasesBecomeNaturalHinglish() {
        assertEquals("privacy & security", HinglishOut.convert("गोपनीयता और सुरक्षा"))
        assertEquals("profile edit karo", HinglishOut.convert("प्रोफ़ाइल संपादित करें"))
        assertEquals("group", HinglishOut.convert("समूह"))
        assertEquals("notifications", HinglishOut.convert("सूचनाएं"))
        assertEquals("message bhejo", HinglishOut.convert("संदेश भेजें"))
    }
    @Test fun everydayWordsAreSpokenSpelling() {
        assertEquals("main aapko maaf karo", HinglishOut.convert("मैं आपको माफ़ करो"))
        assertEquals("kya aap theek hain", HinglishOut.convert("क्या आप ठीक हैं"))
    }
    @Test fun otherWordsAreRomanisedByRule() {
        assertEquals("samajh", HinglishOut.roman("समझ")); assertEquals("badalna", HinglishOut.roman("बदलना")); assertEquals("kamal", HinglishOut.roman("कमल"))
        assertEquals("hindi", HinglishOut.roman("हिंदी")); assertEquals("sandesh", HinglishOut.roman("संदेश")); assertEquals("kitab", HinglishOut.roman("किताब")); assertEquals("skul", HinglishOut.roman("स्कूल"))
    }
    @Test fun latinAndNumbersStay() { assertEquals("WeTalks 2 chats", HinglishOut.convert("WeTalks 2 चैट्स")) }
    @Test fun keptLabels() { assertTrue(HinglishOut.keep("Me")); assertTrue(HinglishOut.keep("Chats")); assertFalse(HinglishOut.keep("Delete this message?")) }
}
