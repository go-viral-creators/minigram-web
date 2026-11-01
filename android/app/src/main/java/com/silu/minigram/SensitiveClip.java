package com.silu.minigram;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;

/** Copies message text to the clipboard flagged as sensitive (Android 13+ hides the preview toast). */
final class SensitiveClip {
    private SensitiveClip() {}

    static void copy(Context c, String label, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData d = ClipData.newPlainText(label, text);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle pb = new PersistableBundle();
            pb.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            d.getDescription().setExtras(pb);
        }
        cm.setPrimaryClip(d);
    }
}
