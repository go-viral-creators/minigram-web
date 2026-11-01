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
}
