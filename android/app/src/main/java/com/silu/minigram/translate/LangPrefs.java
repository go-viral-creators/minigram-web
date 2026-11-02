package com.silu.minigram.translate;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Language & Translation settings (stored locally, never sent anywhere). */
public final class LangPrefs {
    private static final String P = "lang_prefs";
    public static final String MY_LANG = "my_lang", AUTO = "auto_translate", SHOW_ORIGINAL = "show_original", TRANSCRIPTS = "translate_transcripts";
    private LangPrefs() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(P, Context.MODE_PRIVATE); }

    public static String myLang(Context c) {
        String v = sp(c).getString(MY_LANG, null);
        return v != null ? v : Locale.getDefault().getLanguage();
    }
    public static void setMyLang(Context c, String code) { sp(c).edit().putString(MY_LANG, code).apply(); }
    public static boolean auto(Context c) { return sp(c).getBoolean(AUTO, true); }
    public static boolean showOriginal(Context c) { return sp(c).getBoolean(SHOW_ORIGINAL, false); }
    public static boolean transcripts(Context c) { return sp(c).getBoolean(TRANSCRIPTS, true); }
    /** Per-chat switch (default on). */
    public static boolean chatAuto(Context c, String chatId) { return sp(c).getBoolean("chat_auto_" + chatId, true); }
    public static void setChatAuto(Context c, String chatId, boolean v) { sp(c).edit().putBoolean("chat_auto_" + chatId, v).apply(); }
    /** 0 = not asked yet, 1 = on, 2 = off. Online Hinglish transliteration sends text to Google, so it is opt-in. */
    public static int hinglishOnline(Context c) { return sp(c).getInt("hinglish_online", 0); }
    public static void setHinglishOnline(Context c, boolean on) { sp(c).edit().putInt("hinglish_online", on ? 1 : 2).apply(); }
    public static void set(Context c, String key, boolean v) { sp(c).edit().putBoolean(key, v).apply(); }
}
