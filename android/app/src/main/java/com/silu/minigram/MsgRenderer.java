package com.silu.minigram;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.*;
import android.text.style.*;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;

public class MsgRenderer {

    // GitHub Dark palette for Code Blocks
    private static final int CODE_BG    = Ink.c("#1A73E8");
    private static final int CODE_TEXT  = Ink.c("#E3F2FD");
    
    // Theme for Inline Mono Text
    private static final int INLINE_BG  = Ink.c("#1A73E8");
    private static final int INLINE_TXT = Ink.c("#E3F2FD");
    
    // Theme for Inline Buttons
    private static final int BTN_BG     = Ink.c("#1A73E8"); // Material Blue
    private static final int BTN_TXT    = Color.WHITE;

    /** Text colour for the bubble this TextView lives in (white on sent bubbles, ink on received). */
    public static void render(Context ctx, TextView tv, String text, int textColor) {
        defaultColor = textColor;
        try { render(ctx, tv, text); } finally { defaultColor = 0; }
    }
    private static int defaultColor = 0;

    public static void render(Context ctx, TextView tv, String text) {
        if (text == null || text.isEmpty()) {
            tv.setText("");
            resetStyle(tv);
            return;
        }

        // Full code block: ``` ... ```
        if (isCodeBlock(text)) {
            _renderCodeBlock(ctx, tv, text);
            return;
        }

        // Reset to normal message style first
        resetStyle(tv);

        SpannableStringBuilder ssb = new SpannableStringBuilder(text);

        // 1. AUTO-LINK FIRST: (URLs, Emails, Phone)
        Linkify.addLinks(ssb, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES | Linkify.PHONE_NUMBERS);
        for (URLSpan us : ssb.getSpans(0, ssb.length(), URLSpan.class)) {
            int s0 = ssb.getSpanStart(us), e0 = ssb.getSpanEnd(us), fl = ssb.getSpanFlags(us);
            ssb.removeSpan(us);
            ssb.setSpan(new BrowserSpan(us.getURL()), s0, e0, fl);
        }

        // 2. Formatting apply karein
        _applyPattern(ssb, "\\*\\*(.+?)\\*\\*", () -> new StyleSpan(Typeface.BOLD));
        _applyPattern(ssb, "(?<![a-zA-Z])_(.+?)_(?![a-zA-Z])", () -> new StyleSpan(Typeface.ITALIC));
        _applyPattern(ssb, "~~(.+?)~~", () -> new StrikethroughSpan());

        // 3. Inline Buttons: {"url", "Button Text"}
        _applyInlineButtons(ctx, ssb);

        // 4. Inline Mono (Click to Copy)
        _applyInlineCode(ctx, ssb);

        // 5. @mentions — SABSE AAKHIR MEIN, jaan-boojh kar.
        //    Upar ke saare steps ssb.replace() karte hain (markers hataate hain),
        //    jisse offsets shift hote hain. Mentions ko last mein lagane se wo
        //    hamesha FINAL text par lagte hain aur kabhi galat position par
        //    nahi jaate.
        List<String> unknownHandles = MentionHelper.applyMentions(ctx, ssb);

        // BUGFIX: BufferType.SPANNABLE zaroor dena hai, sirf setText(ssb) nahi.
        //
        // TextView.setText(CharSequence) apne aap Spannable NAHI banata —
        // sirf tab jab TextView ke paas pehle se MovementMethod ho. Hamare
        // SmartLinkMovementMethod mein setMovementMethod(null) hota hai, isliye
        // us condition ke bina TextView ye Spanned content ko immutable
        // SpannedString mein badal deta. Aur SpannedString `instanceof
        // Spannable` FALSE hai — isliye jab tak koi URL na ho (jo Linkify ko
        // apne aap MovementMethod set karne par majboor karta), koi bhi
        // ClickableSpan — @mention, inline button, inline code — click hi nahi
        // hota tha.
        tv.setText(ssb, TextView.BufferType.SPANNABLE);

        // Jo handles pehli baar dikhe hain unka existence background mein check
        // hota hai. Agar koi handle exist nahi karta, to ye row ek baar dobara
        // render hoti hai taaki wo blue text wapas normal ho jaye. Cache warm
        // hone ke baad ye dobara nahi chalta.
        final String originalText = text;
        MentionHelper.resolveAll(ctx, unknownHandles, tv, ssb,
                () -> render(ctx, tv, originalText));
        // LinkMovementMethod zaruri hai ClickableSpan aur Links dono ko tap karne ke liye
        // tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    }

    private static void _renderCodeBlock(Context ctx, TextView tv, String raw) {
        String code = raw
            .replaceAll("^```[a-zA-Z0-9+#]*\\r?\\n?", "")
            .replaceAll("```\\s*$", "")
            .trim();

        SpannableString ss = new SpannableString(code);
        ClickableSpan copySpan = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                SensitiveClip.copy(ctx, "Copied code", code);
                Toast.makeText(ctx, "Code copied to clipboard", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void updateDrawState(TextPaint ds) { } // No underline
        };
        ss.setSpan(copySpan, 0, code.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Same fix as the main render() path (dekho wahan ka comment) — bina
        // BufferType.SPANNABLE ke ye code block ka "tap to copy" bhi kaam nahi
        // karta tha jab tak message mein alag se koi real URL na ho.
        tv.setText(ss, TextView.BufferType.SPANNABLE);
        
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(CODE_BG);
        gd.setCornerRadius(dpToPx(ctx, 8)); // 8dp corner radius
        tv.setBackground(gd);
        
        tv.setTextColor(CODE_TEXT);
        tv.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 10), dpToPx(ctx, 12), dpToPx(ctx, 10));
        tv.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        tv.setTextSize(13f);
        tv.setLineSpacing(6f, 1f);
        tv.setHorizontallyScrolling(true);
        // tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    }

    private static void _applyInlineButtons(Context ctx, SpannableStringBuilder ssb) {
        // Regex pattern matches: {"url", "text"}
        String regex = "\\{\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*\\}";
        Matcher m = Pattern.compile(regex).matcher(ssb.toString());
        List<MatchResult> results = new ArrayList<>();
        while (m.find()) results.add(m.toMatchResult());

        int paddingX = dpToPx(ctx, 12); // Left-Right padding for rounded corners
        int paddingY = dpToPx(ctx, 8);  // Button Top-Bottom Padding (8dp)
        int marginY  = dpToPx(ctx, 5);  // 5dp Top & Bottom Margin
        int radius   = dpToPx(ctx, 16); // completely rounded capsule shape

        for (int i = results.size() - 1; i >= 0; i--) {
            MatchResult res = results.get(i);
            int start = res.start();
            int end = res.end();
            String url = res.group(1).trim();
            String btnText = res.group(2).trim(); 

            // Replace entire syntax with just the button text
            ssb.replace(start, end, btnText);
            int newEnd = start + btnText.length();

            // 1. Custom Rounded Background Span for Button Look (with Margins)
            RoundedBackgroundSpan bgSpan = new RoundedBackgroundSpan(
                BTN_BG, BTN_TXT, radius, paddingX, paddingY, marginY, 0.9f, true
            );
            ssb.setSpan(bgSpan, start, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            // 2. Clickable Span to open URL
            ClickableSpan clickSpan = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    try {
                        BrowserActivity.open(ctx, url);
                    } catch (Exception e) {
                        Toast.makeText(ctx, "Invalid URL format", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override
                public void updateDrawState(TextPaint ds) {
                    // Prevent default underline and link color
                    ds.setUnderlineText(false);
                }
            };
            ssb.setSpan(clickSpan, start, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            
            // Remove any overlapping auto-links created by Linkify on this text earlier
            URLSpan[] urlSpans = ssb.getSpans(start, newEnd, URLSpan.class);
            for (URLSpan span : urlSpans) {
                ssb.removeSpan(span);
            }
        }
    }

    private static void _applyInlineCode(Context ctx, SpannableStringBuilder ssb) {
        Matcher m = Pattern.compile("`([^`\\n]+)`").matcher(ssb.toString());
        List<MatchResult> results = new ArrayList<>();
        while (m.find()) results.add(m.toMatchResult());
        
        int paddingX = dpToPx(ctx, 6); 
        int paddingY = dpToPx(ctx, 2); 
        int radius = dpToPx(ctx, 6);   
        
        for (int i = results.size() - 1; i >= 0; i--) {
            MatchResult res = results.get(i);
            int start = res.start();
            int end = res.end();
            String innerText = res.group(1); 

            ssb.replace(start, end, innerText);
            int newEnd = start + innerText.length();

            // 0 margin for inline code
            RoundedBackgroundSpan bgSpan = new RoundedBackgroundSpan(
                INLINE_BG, INLINE_TXT, radius, paddingX, paddingY, 0, 0.85f, false
            );
            ssb.setSpan(bgSpan, start, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            ClickableSpan clickSpan = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Copied Mono Text", innerText));
                    Toast.makeText(ctx, "Copied: " + innerText, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void updateDrawState(TextPaint ds) { }
            };
            ssb.setSpan(clickSpan, start, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    interface SpanFactory { Object create(); }

    private static void _applyPattern(SpannableStringBuilder ssb, String pattern, SpanFactory factory) {
        Matcher m = Pattern.compile(pattern).matcher(ssb.toString());
        List<MatchResult> results = new ArrayList<>();
        while (m.find()) results.add(m.toMatchResult());
        
        for (int i = results.size() - 1; i >= 0; i--) {
            MatchResult res = results.get(i);
            int start = res.start();
            int end = res.end();

            // ── MENTION GUARD ────────────────────────────────────────────────
            // Username mein underscore allowed hai ([a-z0-9_]{4,20}) aur italic
            // ka pattern "_text_" hai. Bina is guard ke:
            //     "@rahul_kumar ne @amit_ok ko bola"
            // mein "_kumar ne @amit_" italic ban jaata aur DONO underscores
            // text se hat jaate — matlab dono mentions toot jaate aur kabhi
            // resolve hi nahi hote. Isliye jo match kisi mention ko chhoo raha
            // hai, use chhod dete hain.
            //
            // Reverse order mein chalne ki wajah se ab tak sirf HIGHER indices
            // par replace hua hai, isliye [start,end) abhi bhi ssb ke current
            // state ke hisaab se sahi hai.
            if (MentionHelper.overlapsMention(ssb, start, end)) continue;

            String innerText = res.group(1);

            ssb.replace(start, end, innerText);
            ssb.setSpan(factory.create(), start, start + innerText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public static boolean isCodeBlock(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("```") && t.endsWith("```") && t.length() > 6;
    }

    private static void resetStyle(TextView tv) {
        tv.setBackgroundResource(0);
        tv.setBackgroundColor(Color.TRANSPARENT);
        tv.setTypeface(Appearance.typeface(tv.getContext()));
        tv.setTextSize(Appearance.textSp(tv.getContext()));
        tv.setTextColor(defaultColor != 0 ? defaultColor : Ink.res(R.color.ink_text));
        tv.setHorizontallyScrolling(false);
        tv.setPadding(0, 0, 0, 0); 
        tv.setLineSpacing(0f, 1f);
    }
    
    // DP to Pixels helper
    private static int dpToPx(Context ctx, int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics()));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Custom ReplacementSpan for Rounded Corners, Padding & Font Size
    // ────────────────────────────────────────────────────────────────────────
    private static class RoundedBackgroundSpan extends ReplacementSpan {
        private final int backgroundColor;
        private final int textColor;
        private final float cornerRadius;
        private final int paddingX;
        private final int paddingY;
        private final int marginY; 
        private final float textScale;
        private final boolean isBold;

        public RoundedBackgroundSpan(int backgroundColor, int textColor, float cornerRadius, int paddingX, int paddingY, int marginY, float textScale, boolean isBold) {
            this.backgroundColor = backgroundColor;
            this.textColor = textColor;
            this.cornerRadius = cornerRadius;
            this.paddingX = paddingX;
            this.paddingY = paddingY;
            this.marginY = marginY;
            this.textScale = textScale;
            this.isBold = isBold;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            Paint newPaint = new Paint(paint);
            newPaint.setTextSize(paint.getTextSize() * textScale);
            if (isBold) {
                newPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            } else {
                newPaint.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            }
            
            if (fm != null) {
                Paint.FontMetricsInt newFm = newPaint.getFontMetricsInt();
                // Add marginY to both ascent (top margin) and descent (bottom margin)
                fm.ascent = newFm.ascent - paddingY - marginY;
                fm.descent = newFm.descent + paddingY + marginY;
                fm.top = fm.ascent;
                fm.bottom = fm.descent;
            }
            return Math.round(newPaint.measureText(text, start, end) + (paddingX * 2));
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            Paint newPaint = new Paint(paint);
            newPaint.setTextSize(paint.getTextSize() * textScale);
            if (isBold) {
                newPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            } else {
                newPaint.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            }

            float textWidth = newPaint.measureText(text, start, end);
            float rectWidth = textWidth + (paddingX * 2);
            
            Paint.FontMetrics fm = newPaint.getFontMetrics();
            float rectTop = y + fm.ascent - paddingY;
            float rectBottom = y + fm.descent + paddingY;

            // Draw Background
            Paint bgPaint = new Paint();
            bgPaint.setColor(backgroundColor);
            bgPaint.setAntiAlias(true);
            
            // Draw shadow for button (simple shadow approach)
            if (isBold) {
                bgPaint.setShadowLayer(4f, 0f, 2f, Color.parseColor("#40000000"));
            }
            
            RectF rect = new RectF(x, rectTop, x + rectWidth, rectBottom);
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint);

            // Draw Text
            newPaint.setColor(textColor);
            newPaint.setAntiAlias(true);
            
            // Adjust Y slightly to center text perfectly visually in capsule
            float textYOffset = (fm.descent + fm.ascent) / 2f;
            float verticalCenter = rectTop + (rectBottom - rectTop) / 2f;
            
            canvas.drawText(text, start, end, x + paddingX, verticalCenter - textYOffset, newPaint);
        }
    }

    /** Link span that opens http(s) in the in-app browser (still a URLSpan so copy-on-long-press keeps working). */
    static final class BrowserSpan extends URLSpan {
        BrowserSpan(String url) { super(url); }
        @Override public void onClick(View widget) { BrowserActivity.open(widget.getContext(), getURL()); }
    }
}
