package com.silu.minigram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;

/**
 * Data and Storage settings — controls whether images / voice messages
 * download automatically or require a manual tap, and now also owns
 * Clear Cache (moved here from the old Privacy & Security sheet — cache
 * size is storage housekeeping, not a privacy control, so this is where
 * Telegram/WhatsApp put it too).
 *
 * Both auto-download toggles default to OFF (manual download) — matches
 * Telegram/WhatsApp's "no surprise data usage" behavior.
 */
public class DataStorageSheet extends BottomSheetDialogFragment {

    public static final String PREFS             = "minigram_data_storage";
    public static final String KEY_AUTO_DL_IMAGES = "auto_download_images";
    public static final String KEY_AUTO_DL_VOICE  = "auto_download_voice";

    public static boolean isAutoDownloadImages(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_DL_IMAGES, false); // OFF by default
    }

    public static boolean isAutoDownloadVoice(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_DL_VOICE, false); // OFF by default
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.sheet_data_storage, container, false);

        if (getContext() == null) return v;
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        Switch swImages = v.findViewById(R.id.sw_auto_dl_images);
        Switch swVoice  = v.findViewById(R.id.sw_auto_dl_voice);

        swImages.setChecked(prefs.getBoolean(KEY_AUTO_DL_IMAGES, false));
        swVoice.setChecked(prefs.getBoolean(KEY_AUTO_DL_VOICE, false));

        swImages.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean(KEY_AUTO_DL_IMAGES, checked).apply());

        swVoice.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean(KEY_AUTO_DL_VOICE, checked).apply());

        // ── Clear Cache ──────────────────────────────────────────────────
        TextView txtCacheSize = v.findViewById(R.id.txt_cache_size);
        if (txtCacheSize != null) {
            txtCacheSize.setText(formatBytes(getDirSize(requireContext().getCacheDir())));
        }
        View rowClearCache = v.findViewById(R.id.row_clear_cache);
        if (rowClearCache != null) {
            rowClearCache.setOnClickListener(x -> confirmClearCache(txtCacheSize));
        }

        return v;
    }

    private void confirmClearCache(TextView txtCacheSize) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Clear Chat Cache")
                .setMessage("This will clear locally cached images and data. " +
                        "Your messages will NOT be deleted.")
                .setPositiveButton("Clear", (d, w) -> {
                    try {
                        deleteDir(requireContext().getCacheDir());
                        File glideDir = new File(requireContext().getCacheDir(), "image_manager_disk_cache");
                        if (glideDir.exists()) deleteDir(glideDir);

                        if (txtCacheSize != null) txtCacheSize.setText("0 B");
                        Toast.makeText(requireContext(), "Cache cleared ✓", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "Cache clear fail hua", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private long getDirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                size += f.isDirectory() ? getDirSize(f) : f.length();
            }
        }
        return size;
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024)       return bytes + " B";
        if (bytes < 1048576)    return String.format("%.1f KB", bytes / 1024f);
        if (bytes < 1073741824) return String.format("%.2f MB", bytes / 1048576f);
        return String.format("%.2f GB", bytes / 1073741824f);
    }
}
