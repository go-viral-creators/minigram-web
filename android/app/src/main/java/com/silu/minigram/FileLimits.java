package com.silu.minigram;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Upload limits for video / audio / document attachments (kept low: files are held in memory while encrypting/uploading). */
final class FileLimits {
    static final long VIDEO_MAX_BYTES = 30L * 1024 * 1024;  static final int VIDEO_MAX_SEC = 90;
    static final long AUDIO_MAX_BYTES = 15L * 1024 * 1024;  static final int AUDIO_MAX_SEC = 600;
    static final long DOC_MAX_BYTES   = 20L * 1024 * 1024;
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
        "apk","apks","xapk","exe","msi","bat","cmd","com","scr","sh","jar","dex","js","vbs","ps1","dll","so","bin","iso","lnk"));

    private FileLimits() {}

    static String ext(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** @return null if OK, otherwise a message for the user. */
    static String check(String kind, String name, long size, int durationSec) {
        String mb = "video".equals(kind) ? "30 MB" : "audio".equals(kind) ? "15 MB" : "20 MB";
        if (size <= 0) return "Could not read that file";
        long max = "video".equals(kind) ? VIDEO_MAX_BYTES : "audio".equals(kind) ? AUDIO_MAX_BYTES : DOC_MAX_BYTES;
        if (size > max) return "File is too large (" + human(size) + "). Limit is " + mb + ".";
        if ("video".equals(kind) && durationSec > VIDEO_MAX_SEC) return "Video is too long. Limit is 90 seconds.";
        if ("audio".equals(kind) && durationSec > AUDIO_MAX_SEC) return "Audio is too long. Limit is 10 minutes.";
        if ("document".equals(kind) && BLOCKED.contains(ext(name))) return "This file type can't be sent.";
        return null;
    }

    static String human(long b) {
        if (b >= 1024L * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        if (b >= 1024) return (b / 1024) + " KB";
        return b + " B";
    }

    static String glyph(String kind, String name) {
        if ("video".equals(kind)) return "videocam";
        if ("audio".equals(kind)) return "audiotrack";
        String e = ext(name);
        if (e.equals("pdf")) return "picture_as_pdf";
        if (e.equals("zip") || e.equals("rar") || e.equals("7z")) return "folder_zip";
        if (e.equals("xls") || e.equals("xlsx") || e.equals("csv")) return "table_chart";
        if (e.equals("ppt") || e.equals("pptx")) return "slideshow";
        return "description";
    }
}
