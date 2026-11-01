package com.silu.minigram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.Locale;

/**
 * Loads a user/channel avatar — real photo if they have one, otherwise a
 * generated colored circle with their initials (Telegram-style), instead of
 * a generic gray-person icon.
 *
 * Usage (drop-in replacement for the old pattern):
 *   AvatarHelper.load(context, imgAvatar, photoUrl, displayName);
 *
 * The initials + color are both deterministic (same name → same initials AND
 * same color every time), so a person's avatar looks consistent everywhere
 * in the app without needing to store a color choice anywhere.
 */
public class AvatarHelper {

    // A small, deliberately-picked palette — distinct from each other,
    // readable with white text, and not jarring next to the app's own
    // sky-blue accent color used elsewhere (Pro badges, buttons, etc.).
    private static final int[] PALETTE = {
        0xFFE57373, // red
        0xFFF06292, // pink
        0xFFBA68C8, // purple
        0xFF9575CD, // deep purple
        0xFF7986CB, // indigo
        0xFF64B5F6, // blue
        0xFF4DB6AC, // teal
        0xFF81C784, // green
        0xFFFFB74D, // orange
        0xFFA1887F, // brown
    };

    public static void load(Context context, ImageView imageView, String photoUrl, String displayName) {
        if (photoUrl != null && !photoUrl.isEmpty()) {
            Glide.with(context)
                .load(photoUrl)
                .circleCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_person)
                .error(_placeholderFor(displayName))
                .into(imageView);
        } else {
            imageView.setImageBitmap(_generate(displayName));
        }
    }

    /** For cases that need the bitmap directly rather than an ImageView (rare). */
    private static android.graphics.drawable.Drawable _placeholderFor(String displayName) {
        return new android.graphics.drawable.BitmapDrawable(
            android.content.res.Resources.getSystem(), _generate(displayName));
    }

    private static Bitmap _generate(String displayName) {
        int size = 176; // px — scales cleanly for both list-row (~40dp) and profile-header (~96dp) sizes
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(_colorFor(displayName));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circlePaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(size * 0.42f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        String initials = _initialsFor(displayName);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(initials, size / 2f, textY, textPaint);

        return bitmap;
    }

    /** "Rahul Kumar" → "RK"  ·  "Rahul" → "R"  ·  empty/null → "?" */
    private static String _initialsFor(String name) {
        if (name == null) return "?";
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return "?";

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, 1).toUpperCase(Locale.ENGLISH);
        }
        String first = parts[0].isEmpty() ? "" : parts[0].substring(0, 1);
        String last  = parts[parts.length - 1].isEmpty() ? "" : parts[parts.length - 1].substring(0, 1);
        String combined = (first + last).toUpperCase(Locale.ENGLISH);
        return combined.isEmpty() ? "?" : combined;
    }

    /** Same name always maps to the same palette color. */
    private static int _colorFor(String name) {
        String key = (name == null || name.trim().isEmpty()) ? "?" : name.trim();
        int hash = Math.abs(key.hashCode());
        return PALETTE[hash % PALETTE.length];
    }
}
