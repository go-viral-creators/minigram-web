package com.silu.minigram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Built-in sticker packs. A sticker id is "e:<emoji>" (big emoji) or "t:<phrase>|#RRGGBB" (coloured phrase card). */
final class Stickers {
    static final class Pack {
        final String icon, title; final List<String> items;
        Pack(String icon, String title, List<String> items) { this.icon = icon; this.title = title; this.items = items; }
    }
    private Stickers() {}

    private static List<String> emoji(String... e) { List<String> l = new ArrayList<>(); for (String s : e) l.add("e:" + s); return l; }

    static List<Pack> packs() {
        List<Pack> p = new ArrayList<>();
        p.add(new Pack("emoji_emotions", "Faces", emoji(
            "\uD83D\uDE00","\uD83D\uDE02","\uD83E\uDD23","\uD83D\uDE0A","\uD83D\uDE0D","\uD83E\uDD70","\uD83D\uDE18","\uD83D\uDE0E","\uD83E\uDD29","\uD83E\uDD73",
            "\uD83D\uDE0F","\uD83D\uDE44","\uD83D\uDE2D","\uD83D\uDE22","\uD83D\uDE21","\uD83E\uDD2F","\uD83D\uDE31","\uD83E\uDD74","\uD83D\uDE34","\uD83E\uDD14",
            "\uD83E\uDD2D","\uD83D\uDE07","\uD83E\uDD11","\uD83E\uDD76")));
        p.add(new Pack("favorite", "Love", emoji(
            "\u2764\uFE0F","\uD83E\uDDE1","\uD83D\uDC9B","\uD83D\uDC9A","\uD83D\uDC99","\uD83D\uDC9C","\uD83D\uDDA4","\uD83D\uDC96","\uD83D\uDC95","\uD83D\uDC98",
            "\uD83D\uDC8B","\uD83D\uDC91","\uD83C\uDF39","\uD83C\uDF81","\uD83D\uDC8D","\uD83E\uDD17")));
        p.add(new Pack("thumb_up", "Vibes", emoji(
            "\uD83D\uDC4D","\uD83D\uDC4E","\uD83D\uDC4F","\uD83D\uDE4F","\uD83D\uDCAA","\u270C\uFE0F","\uD83E\uDD1D","\uD83D\uDC4C","\uD83E\uDD1E","\uD83D\uDD25",
            "\uD83D\uDCAF","\uD83C\uDF89","\u2728","\uD83C\uDF1F","\uD83D\uDCA5","\uD83D\uDE80","\uD83C\uDFC6","\uD83D\uDC51")));
        p.add(new Pack("fastfood", "Fun", emoji(
            "\u2615","\uD83C\uDF55","\uD83C\uDF54","\uD83C\uDF5F","\uD83C\uDF66","\uD83C\uDF82","\uD83C\uDFAE","\uD83C\uDFB5","\uD83D\uDCF1","\uD83D\uDE97",
            "\u26BD","\uD83C\uDFCF","\uD83D\uDC36","\uD83D\uDC31","\uD83D\uDC12","\uD83E\uDD81")));
        List<String> t = new ArrayList<>(Arrays.asList(
            "t:Kya baat hai!|#7C3AED", "t:Ek number|#0EA5E9", "t:Chal bhai|#16A34A", "t:Arre wah!|#EA580C",
            "t:Tension nahi|#0D9488", "t:Mast hai|#DB2777", "t:Bas kar pagle|#DC2626", "t:Shukriya|#2563EB",
            "t:Theek hai|#475569", "t:Aaja yaar|#9333EA", "t:Party?|#D97706", "t:Good night|#1E3A8A",
            "t:Good morning|#F59E0B", "t:Thanks!|#059669", "t:On my way|#0284C7", "t:LOL|#E11D48"));
        p.add(new Pack("chat_bubble_outline", "Phrases", t));
        return p;
    }

    static boolean isEmoji(String id) { return id != null && id.startsWith("e:"); }
    static boolean isPhrase(String id) { return id != null && id.startsWith("t:"); }
    static String emojiOf(String id) { return id.substring(2); }
    static String phraseOf(String id) { int i = id.lastIndexOf('|'); return id.substring(2, i > 2 ? i : id.length()); }
    static int colorOf(String id) {
        try { return android.graphics.Color.parseColor(id.substring(id.lastIndexOf('|') + 1)); }
        catch (Exception e) { return 0xFF475569; }
    }
    /** Short human label for previews (chat list, reply quote). */
    static String label(String id) { return isPhrase(id) ? "Sticker \u00b7 " + phraseOf(id) : "Sticker " + (isEmoji(id) ? emojiOf(id) : ""); }
}
