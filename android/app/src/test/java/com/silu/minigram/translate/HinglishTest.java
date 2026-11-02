package com.silu.minigram.translate;

import static org.junit.Assert.*;

import org.junit.Test;

public class HinglishTest {
    @Test public void commonSentence() {
        String d = Hinglish.toDevanagari("Bhai kal ka plan final hai?");
        assertTrue(d, d.startsWith("भाई कल का "));
        assertTrue(d, d.endsWith(" है?"));
    }
    @Test public void unknownWordsGetPhoneticDevanagariNotLatin() {
        String d = Hinglish.toDevanagari("bike le aana");
        assertFalse(d, d.matches(".*[a-zA-Z].*"));
        assertTrue(d, d.endsWith("ले आना"));
    }
    @Test public void punctuationAndSpacingPreserved() {
        assertEquals("हाँ, ठीक है!", Hinglish.toDevanagari("haan, theek hai!"));
    }
    @Test public void romanHindiCodes() {
        assertTrue(Hinglish.isRomanHindi("hi-Latn"));
        assertFalse(Hinglish.isRomanHindi("hi"));
        assertFalse(Hinglish.isRomanHindi(null));
    }
    @Test public void detectsHinglishButNotEnglish() {
        assertTrue(Hinglish.looksRoman("bhai kal ka plan final hai"));
        assertTrue(Hinglish.looksRoman("Theek hai abhi to bhej do"));
        assertTrue(Hinglish.looksRoman("kya kar rahe ho"));
        assertFalse(Hinglish.looksRoman("do you have the time to talk"));
        assertFalse(Hinglish.looksRoman("see you in the main hall"));
        assertFalse(Hinglish.looksRoman(""));
    }
    @Test public void parsesInputToolsResponse() {
        assertEquals("क्या कर रहे हो", HinglishOnline.parse("[\"SUCCESS\",[[\"kya kar rahe ho\",[\"क्या कर रहे हो\"],[],{}]]]"));
        assertNull(HinglishOnline.parse("[\"FAILED_TO_PARSE_REQUEST_BODY\"]"));
    }
}
