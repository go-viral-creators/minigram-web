package com.silu.minigram.translate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * On-device translation: ML Kit language-ID + ML Kit Translate (no network call for the text itself; only the
 * language packs are downloaded once). Called AFTER a message has been decrypted on this phone.
 */
public final class TranslationManager {
    public interface Result { void done(Outcome o); }

    public static final class Outcome {
        public String translated;     // null = nothing to show
        public String sourceLang;     // e.g. "hi", "hi-Latn"
        public boolean approximate;   // Hinglish path
        public boolean hinglish;      // source was Romanised Hindi
        public boolean needsPack;     // model for sourceLang/targetLang not installed
        public String packLang;       // which pack is missing
    }

    private static TranslationManager inst;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LanguageIdentifier idClient = LanguageIdentification.getClient(
            new LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build());
    private final Map<String, Translator> translators = new LinkedHashMap<>();
    private volatile Set<String> installed = new HashSet<>();
    private final Map<String, Outcome> cache = Collections.synchronizedMap(new LinkedHashMap<String, Outcome>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Outcome> e) { return size() > 800; }
    });
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<String>());

    private TranslationManager(Context c) { app = c.getApplicationContext(); refreshInstalled(null); }

    public static synchronized TranslationManager get(Context c) {
        if (inst == null) inst = new TranslationManager(c);
        return inst;
    }

    // ------------------------------------------------------------------ packs
    public Set<String> installedPacks() { return installed; }

    public void refreshInstalled(Runnable done) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class)
            .addOnSuccessListener(models -> {
                Set<String> s = new HashSet<>();
                for (TranslateRemoteModel m : models) s.add(m.getLanguage());
                installed = s;
                if (done != null) main.post(done);
            })
            .addOnFailureListener(e -> { if (done != null) main.post(done); });
    }

    public void downloadPack(String lang, Runnable ok, Runnable fail) {
        RemoteModelManager.getInstance().download(new TranslateRemoteModel.Builder(lang).build(), new DownloadConditions.Builder().build())
            .addOnSuccessListener(v -> refreshInstalled(ok))
            .addOnFailureListener(e -> { if (fail != null) main.post(fail); });
    }

    public void deletePack(String lang, Runnable done) {
        RemoteModelManager.getInstance().deleteDownloadedModel(new TranslateRemoteModel.Builder(lang).build())
            .addOnCompleteListener(t -> refreshInstalled(done));
    }

    /** Languages ML Kit can translate, sorted by their display name in the user's UI language. */
    public static List<String> allLanguages() {
        List<String> l = new ArrayList<>(TranslateLanguage.getAllLanguages());
        final Locale ui = Locale.getDefault();
        Collections.sort(l, (a, b) -> displayName(a, ui).compareToIgnoreCase(displayName(b, ui)));
        return l;
    }

    public static String displayName(String code, Locale ui) {
        String n = new Locale(code).getDisplayLanguage(ui);
        return n == null || n.isEmpty() ? code : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // -------------------------------------------------------------- translate
    private Translator translator(String src, String tgt) {
        String k = src + ">" + tgt;
        synchronized (translators) {
            Translator t = translators.get(k);
            if (t == null) {
                t = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build());
                translators.put(k, t);
                while (translators.size() > 6) {
                    String first = translators.keySet().iterator().next();
                    translators.remove(first).close();
                }
            }
            return t;
        }
    }

    /** Cached result for this message, or null if not (yet) computed. */
    public Outcome cached(String key) { return cache.get(key); }

    /**
     * @param key        stable key (msgId + text hash)
     * @param manualHinglish if true, run the approximate Hinglish path even if auto-translate skips it
     */
    public void translate(final String key, final String text, final String target, final boolean manualHinglish, final Result cb) {
        Outcome c = cache.get(key);
        if (c != null) { cb.done(c); return; }
        if (!pending.add(key)) return;
        idClient.identifyLanguage(text)
            .addOnSuccessListener(lang -> {
                final boolean latinGuess = lang == null || "und".equals(lang) || "en".equals(lang) || Hinglish.isRomanHindi(lang);
                final boolean roman = Hinglish.isRomanHindi(lang) || (latinGuess && Hinglish.looksRoman(text));
                if (!roman && (lang == null || "und".equals(lang))) { finish(key, new Outcome(), cb); return; }
                final Outcome o = new Outcome();
                o.sourceLang = roman ? "hi-Latn" : lang;
                o.hinglish = roman;
                final String src = roman ? "hi" : base(lang);
                if (src.equals(target) && !roman) { finish(key, o, cb); return; }
                if (!TranslateLanguage.getAllLanguages().contains(src)) { finish(key, o, cb); return; }
                String missing = roman && "hi".equals(target) ? null
                    : !installed.contains(src) ? src : !installed.contains(target) ? target : null;
                if (missing != null) { o.needsPack = true; o.packLang = missing; finish(key, o, cb); return; }
                if (!roman) {
                    translator(src, target).translate(text)
                        .addOnSuccessListener(r -> { o.translated = r; finish(key, o, cb); })
                        .addOnFailureListener(e -> finish(key, o, cb));
                    return;
                }
                // Romanised Hindi: transliterate to Devanagari (online if the user allowed it, else offline rules),
                // then use the on-device Hindi model.
                IO.execute(() -> {
                    String deva = null;
                    if (LangPrefs.hinglishOnline(app) == 1) deva = HinglishOnline.transliterate(text);
                    o.approximate = deva == null;
                    if (deva == null) deva = Hinglish.toDevanagari(text);
                    final String input = deva;
                    main.post(() -> {
                        if ("hi".equals(target)) { o.translated = input; finish(key, o, cb); return; }
                        translator("hi", target).translate(input)
                            .addOnSuccessListener(r -> { o.translated = r; finish(key, o, cb); })
                            .addOnFailureListener(e -> finish(key, o, cb));
                    });
                });
            })
            .addOnFailureListener(e -> finish(key, new Outcome(), cb));
    }

    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void finish(String key, Outcome o, Result cb) {
        pending.remove(key);
        // Do not cache "needs pack" - it may be installed a moment later
        if (!o.needsPack) cache.put(key, o);
        main.post(() -> cb.done(o));
    }

    public void clearCache() { cache.clear(); }

    private static String base(String lang) {
        int i = lang.indexOf('-');
        return i > 0 ? lang.substring(0, i) : lang;
    }
}
