package com.silu.minigram;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/** "Share" sheet opened by the paperclip: photos, video, audio, documents, stickers. */
public class AttachSheet extends BottomSheetDialogFragment {
    public interface Picker { void pick(String what); }
    private Picker picker;
    public void setPicker(Picker p) { picker = p; }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inf.inflate(R.layout.sheet_attach, c, false);
        int[] ids = {R.id.att_gallery, R.id.att_video, R.id.att_audio, R.id.att_document, R.id.att_sticker};
        final String[] what = {"gallery", "video", "audio", "document", "sticker"};
        for (int i = 0; i < ids.length; i++) {
            final String w = what[i];
            v.findViewById(ids[i]).setOnClickListener(x -> { dismiss(); if (picker != null) picker.pick(w); });
        }
        return v;
    }
}
