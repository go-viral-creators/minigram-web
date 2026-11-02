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

    private static List<String> ids(String... n) { return new ArrayList<>(Arrays.asList(n)); }

    static List<Pack> packs() {
        List<Pack> p = new ArrayList<>();
        p.add(new Pack("emoji_emotions", "Moods", ids("s:face_angry", "s:face_cool", "s:face_cry", "s:face_happy", "s:face_kiss", "s:face_laugh", "s:face_love", "s:face_party", "s:face_sad", "s:face_shock", "s:face_sleepy", "s:face_star", "s:face_wink", "s:face_wow")));
        p.add(new Pack("thumb_up", "Reactions", ids("s:hand_like", "s:big_heart", "s:big_star")));
        p.add(new Pack("chat_bubble_outline", "Words", ids("s:word_arre", "s:word_chai", "s:word_gm", "s:word_gn", "s:word_haan", "s:word_lol", "s:word_love_t", "s:word_nahi", "s:word_ok", "s:word_omw", "s:word_party_t", "s:word_sahi", "s:word_thanks", "s:word_wow_t")));
        return p;
    }

    /** Real artwork stickers live in assets/stickers/<name>.webp. */
    static boolean isArt(String id) { return id != null && id.startsWith("s:"); }
    static String assetUrl(String id) { return "file:///android_asset/stickers/" + id.substring(2) + ".webp"; }

    static boolean isEmoji(String id) { return id != null && id.startsWith("e:"); }
    static boolean isPhrase(String id) { return id != null && id.startsWith("t:"); }
    static String emojiOf(String id) { return id.substring(2); }
    static String phraseOf(String id) { int i = id.lastIndexOf('|'); return id.substring(2, i > 2 ? i : id.length()); }
    static int colorOf(String id) {
        try { return android.graphics.Color.parseColor(id.substring(id.lastIndexOf('|') + 1)); }
        catch (Exception e) { return 0xFF475569; }
    }
    /** Short human label for previews (chat list, reply quote). */
    static String label(String id) { if (isArt(id)) return "Sticker"; return isPhrase(id) ? "Sticker \u00b7 " + phraseOf(id) : "Sticker " + (isEmoji(id) ? emojiOf(id) : ""); }
}
