package com.silu.minigram;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.silu.minigram.databinding.ActivityLanguageSettingsBinding;
import com.silu.minigram.databinding.RowPackBinding;
import com.silu.minigram.translate.LangPrefs;
import com.silu.minigram.translate.TranslationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Language &amp; Translation settings (design 09): my language, toggles, offline packs. */
public class LanguageSettingsActivity extends AppCompatActivity {
    private static final String[] COMMON = {"en", "hi", "es", "ta", "bn", "mr", "te", "gu", "kn", "ml", "ur",
            "ar", "fr", "de", "pt", "ru", "ja", "ko", "zh", "id", "tr"};

    private ActivityLanguageSettingsBinding b;
    private TranslationManager tm;
    private final Set<String> downloading = new HashSet<>();

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        b = ActivityLanguageSettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        EdgeToEdgeHelper.applyInsets(b.main);
        tm = TranslationManager.get(this);

        b.btnBack.setOnClickListener(v -> finish());
        b.swAuto.setChecked(LangPrefs.auto(this));
        b.swOriginal.setChecked(LangPrefs.showOriginal(this));
        b.swTranscripts.setChecked(LangPrefs.transcripts(this));
        b.swAuto.setOnCheckedChangeListener((v, c) -> LangPrefs.set(this, LangPrefs.AUTO, c));
        b.swOriginal.setOnCheckedChangeListener((v, c) -> LangPrefs.set(this, LangPrefs.SHOW_ORIGINAL, c));
        b.swTranscripts.setOnCheckedChangeListener((v, c) -> LangPrefs.set(this, LangPrefs.TRANSCRIPTS, c));
        b.rowMyLanguage.setOnClickListener(v -> pickLanguage());
        b.txtVoiceModel.setText("Applied automatically to every voice message");
        refresh();
        tm.refreshInstalled(this::refresh);
    }

    private void refresh() {
        Locale ui = Locale.getDefault();
        String mine = LangPrefs.myLang(this);
        b.txtMyLanguage.setText(TranslationManager.displayName(mine, ui));
        b.rowMyLanguage.setContentDescription("My language: " + TranslationManager.displayName(mine, ui));

        List<String> codes = new ArrayList<>(Arrays.asList(COMMON));
        if (!codes.contains(mine)) codes.add(0, mine);
        b.listPacks.removeAllViews();
        Set<String> inst = tm.installedPacks();
        for (int i = 0; i < codes.size(); i++) {
            final String code = codes.get(i);
            RowPackBinding r = RowPackBinding.inflate(LayoutInflater.from(this), b.listPacks, false);
            r.packName.setText(TranslationManager.displayName(code, ui) + ("hi".equals(code) ? " / Hinglish" : ""));
            r.packSize.setText("≈ 30 MB");
            boolean installed = inst.contains(code), busy = downloading.contains(code);
            r.packProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
            r.packAction.setVisibility(busy ? View.GONE : View.VISIBLE);
            r.packAction.setText(installed ? "Delete" : "Get");
            r.packAction.setTextColor(installed ? Ink.res(R.color.ink_danger) : Ink.res(R.color.ink_accent));
            r.packAction.setContentDescription((installed ? "Delete " : "Download ") + TranslationManager.displayName(code, ui) + " pack");
            r.packAction.setOnClickListener(v -> { if (installed) confirmDelete(code); else download(code); });
            b.listPacks.addView(r.getRoot());
            if (i < codes.size() - 1) {
                View d = new View(this);
                d.setBackgroundColor(Ink.res(R.color.ink_line));
                b.listPacks.addView(d, new android.widget.LinearLayout.LayoutParams(-1, 1));
            }
        }
    }

    private void download(String code) {
        downloading.add(code);
        refresh();
        tm.downloadPack(code, () -> { downloading.remove(code); refresh(); },
            () -> { downloading.remove(code); refresh();
                    Toast.makeText(this, "Download failed. Check your connection.", Toast.LENGTH_LONG).show(); });
    }

    private void confirmDelete(String code) {
        new AlertDialog.Builder(this)
            .setTitle("Delete " + TranslationManager.displayName(code, Locale.getDefault()) + " pack?")
            .setMessage("Messages in this language will not be translated until you download it again.")
            .setPositiveButton("Delete", (d, w) -> tm.deletePack(code, () -> { tm.clearCache(); refresh(); }))
            .setNegativeButton("Cancel", null).show();
    }

    private void pickLanguage() {
        final List<String> all = TranslationManager.allLanguages();
        String[] names = new String[all.size()];
        int checked = 0;
        String mine = LangPrefs.myLang(this);
        for (int i = 0; i < all.size(); i++) {
            names[i] = TranslationManager.displayName(all.get(i), Locale.getDefault());
            if (all.get(i).equals(mine)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("My language")
            .setSingleChoiceItems(names, checked, (d, which) -> {
                LangPrefs.setMyLang(this, all.get(which));
                tm.clearCache();
                d.dismiss();
                refresh();
                if (!tm.installedPacks().contains(all.get(which))) download(all.get(which));
            }).setNegativeButton("Cancel", null).show();
    }
}
