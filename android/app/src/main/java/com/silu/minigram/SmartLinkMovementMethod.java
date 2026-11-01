package com.silu.minigram;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;
import android.widget.Toast;

/**
 * SmartLinkMovementMethod
 * ──────────────────────────────────────────────────────────────────────────
 * Purana approach: setMovementMethod(LinkMovementMethod) → TextView khud saare
 * touch events kha leta tha, parent bubble ka onClick/onLongClick nahi chalta.
 *
 * Naya approach: koi MovementMethod nahi. Uski jagah ek custom OnTouchListener
 * jo SIRF tab intercept karta hai jab ungli ACTUALLY kisi ClickableSpan
 * (URL / phone / @mention / inline code) ke upar ho. Baaki saare touches parent
 * view ko milte hain.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * BUGFIX — "@handle click nahi ho raha tha"
 * ═══════════════════════════════════════════════════════════════════════════
 * Purana code ki pehli line thi:
 *
 *     CharSequence text = widget.getText();
 *     if (!(text instanceof Spannable)) return false;      // ← yahin sab ruk jaata tha
 *
 * Aur `tv.getText()` bahut baar Spannable hota hi NAHI hai. Wajah Android ke
 * TextView ke andar hai:
 *
 *     setText(CharSequence) → BufferType.NORMAL
 *       → agar (type == SPANNABLE || mMovement != null)  → Spannable banta hai
 *       → warna TextUtils.stringOrSpannedString(text)
 *            → SpannableStringBuilder ko SpannedString bana deta hai
 *              (SpannedString = Spanned hai, par Spannable NAHI)
 *
 * MsgRenderer `tv.setText(ssb)` plain call karta tha aur apply() mein
 * `setMovementMethod(null)` hone ki wajah se mMovement bhi null rehta tha —
 * matlab text hamesha SpannedString banta tha aur touch listener pehli hi line
 * par bail out kar jaata tha.
 *
 * To phir URL links kaam kaise kar rahe the? Sirf ITTEFAAQ se. Neeche
 * `Linkify.addLinks(tv, mask)` chalta hai; jab use koi URL/phone MILTA hai to
 * wo andar se `addLinkMovementMethod(tv)` call karta hai (jo LinkMovementMethod
 * set karta hai) aur uske BAAD `tv.setText(s)` karta hai — ab mMovement != null
 * hai, isliye text Spannable ban jaata hai. Jab koi URL nahi milta, Linkify
 * kuch karta hi nahi aur text SpannedString hi reh jaata hai.
 *
 * Isliye behaviour bilkul yeh tha:
 *   • "dekho @rahul_kumar" (koi URL nahi)        → mention click NAHI hota tha
 *   • "dekho @rahul_kumar https://x.com"         → mention click HOTA tha
 *
 * FIX do jagah hai:
 *   1. MsgRenderer ab `setText(ssb, BufferType.SPANNABLE)` use karta hai —
 *      isse normal case mein text hamesha Spannable rehta hai.
 *   2. Yahan `ensureSpannable()` — kyunki kuch adapters render ke BAAD bhi
 *      `tv.setText(...)` karte hain (prependForwarded, _applySearchHighlight)
 *      aur wo phir se SpannedString bana dete hain. Ye ek hi jagah par saare
 *      cases cover kar deta hai, isliye kisi adapter ko chhedna nahi pada.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * NAYA — LONG PRESS = COPY
 * ═══════════════════════════════════════════════════════════════════════════
 * Kisi bhi link ya @handle par ungli daba kar rakho → wo copy ho jaata hai
 * (haptic buzz + toast ke saath).
 *
 * Pehle ye gesture bilkul bekaar jaata tha: touch listener ACTION_DOWN par
 * `true` return karta hai, isliye long press parent bubble tak pahunchta hi
 * nahi tha — na message-actions menu khulta tha, na kuch aur hota tha.
 *
 * Kya copy hota hai:
 *   • URL / phone (URLSpan)  → asli URL, dikhne wala text nahi. Linkify
 *                              "www.x.com" ko "http://www.x.com" bana deta hai,
 *                              aur paste karne ke liye wahi kaam ka hai.
 *   • @mention                → MentionHelper decide karta hai (@handle ya
 *                              poora share link — MentionHelper mein ek constant)
 *   • baaki koi bhi span      → jo text screen par dikh raha hai
 */
public class SmartLinkMovementMethod {

    private static final int PRESS_HIGHLIGHT_COLOR = Color.parseColor("#4D2196F3"); // translucent blue

    /**
     * Koi bhi ClickableSpan isse implement karke bata sakta hai ki long press
     * par kya copy hona chahiye. MentionHelper.MentionSpan isi ka use karta hai.
     */
    public interface CopyableSpan {
        /** Clipboard mein kya jaye. */
        String getCopyText();
        /** Toast mein kya dikhe, e.g. "Username copied". null = default. */
        String getCopyLabel();
    }

    /**
     * Linkify karega (URLs + phone numbers) aur sirf link-spans par touch
     * intercept karega. Baaki touches parent bubble tak bubble up karenge.
     */
    public static void apply(final TextView tv) {
        // Step 0 (NAYA aur ZAROORI): text ko Spannable banao.
        //
        // Ye Linkify se PEHLE hona chahiye — tabhi Linkify apni in-place
        // Spannable branch leta hai aur pehle se lage hue spans (mention colour,
        // bold, clickable) chhedta nahi. Warna wo naya SpannableString banata
        // hai aur setText karta hai.
        ensureSpannable(tv);

        // Step 1: Linkify — detected URLs/phones par ClickableSpan lagata hai.
        // Note: addLinks(Spannable, mask) sirf purane URLSpan hataata hai;
        // hamare mention wale ClickableSpan URLSpan nahi hain, isliye wo
        // safe rehte hain.
        Linkify.addLinks(tv, Linkify.PHONE_NUMBERS | Linkify.WEB_URLS);

        // Step 2: koi MovementMethod nahi — warna TextView SAARE touches kha
        // jaata hai aur bubble ka click/long-click marr jaata hai.
        tv.setMovementMethod(null);
        tv.setFocusable(false);
        tv.setClickable(false);
        tv.setLongClickable(false);

        // Linkify ne agar text badal diya ho to dobara Spannable ensure karo.
        ensureSpannable(tv);

        // Step 3: custom touch listener — sirf link touches intercept, saath
        // mein press feedback aur long-press-to-copy.
        tv.setOnTouchListener(new View.OnTouchListener() {

            private final Handler handler = new Handler(Looper.getMainLooper());

            private BackgroundColorSpan pressHighlight = null;
            private Runnable  longPressRunnable = null;
            private boolean   longPressFired    = false;
            private float     downX = 0f, downY = 0f;

            private void clearHighlight(TextView widget, Spannable spannable) {
                if (pressHighlight != null) {
                    spannable.removeSpan(pressHighlight);
                    pressHighlight = null;
                    widget.invalidate();
                }
            }

            private void cancelLongPress() {
                if (longPressRunnable != null) {
                    handler.removeCallbacks(longPressRunnable);
                    longPressRunnable = null;
                }
            }

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!(v instanceof TextView)) return false;
                final TextView widget = (TextView) v;

                // BUGFIX: pehle yahan `if (!(text instanceof Spannable)) return false;`
                // tha, jo bina URL wale har message par turant bail out kar
                // jaata tha — matlab @mention kabhi clickable hi nahi hota tha.
                final Spannable spannable = ensureSpannable(widget);
                if (spannable == null) return false;

                // Ungli kis character ke upar hai
                int x = (int) event.getX()
                        - widget.getTotalPaddingLeft()
                        + widget.getScrollX();
                int y = (int) event.getY()
                        - widget.getTotalPaddingTop()
                        + widget.getScrollY();

                Layout layout = widget.getLayout();
                if (layout == null) return false;

                int line   = layout.getLineForVertical(y);
                int offset = layout.getOffsetForHorizontal(line, x);

                final ClickableSpan[] spans = spannable.getSpans(offset, offset, ClickableSpan.class);

                if (spans.length > 0) {
                    final ClickableSpan span = spans[0];

                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN: {
                            downX = event.getX();
                            downY = event.getY();
                            longPressFired = false;

                            // Press feedback — sirf is link ke exact bounds par.
                            int start = spannable.getSpanStart(span);
                            int end   = spannable.getSpanEnd(span);
                            clearHighlight(widget, spannable);
                            pressHighlight = new BackgroundColorSpan(PRESS_HIGHLIGHT_COLOR);
                            spannable.setSpan(pressHighlight, start, end,
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            widget.invalidate();

                            // Long press → copy
                            cancelLongPress();
                            final int fStart = start, fEnd = end;
                            longPressRunnable = () -> {
                                longPressFired = true;
                                clearHighlight(widget, spannable);
                                widget.performHapticFeedback(
                                        HapticFeedbackConstants.LONG_PRESS);
                                copySpan(widget.getContext(), spannable, span, fStart, fEnd);
                            };
                            handler.postDelayed(longPressRunnable,
                                    ViewConfiguration.getLongPressTimeout());
                            return true;
                        }

                        case MotionEvent.ACTION_MOVE: {
                            // Ungli slide ho gayi to long press cancel — warna
                            // scroll karte waqt galti se copy ho jaata.
                            int slop = ViewConfiguration.get(widget.getContext())
                                    .getScaledTouchSlop();
                            if (Math.abs(event.getX() - downX) > slop
                                    || Math.abs(event.getY() - downY) > slop) {
                                cancelLongPress();
                            }
                            return true; // ungli abhi bhi span par hai — consumed
                        }

                        case MotionEvent.ACTION_UP: {
                            cancelLongPress();
                            clearHighlight(widget, spannable);
                            // Long press already copy kar chuka hai to click
                            // mat chalao — warna copy ke saath link bhi khul jaata.
                            if (!longPressFired) span.onClick(widget);
                            longPressFired = false;
                            return true;
                        }

                        case MotionEvent.ACTION_CANCEL: {
                            cancelLongPress();
                            clearHighlight(widget, spannable);
                            longPressFired = false;
                            return true;
                        }
                    }
                    return true; // consume — bubble ko ye touch mat do
                }

                // Ungli kisi link par nahi hai — pending long press cancel karo,
                // highlight hatao (ungli link se slide hokar hat gayi ho sakti
                // hai) aur touch parent bubble ko jaane do.
                cancelLongPress();
                clearHighlight(widget, spannable);
                return false;
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * TextView ke text ko Spannable banata hai (agar pehle se nahi hai) aur
     * wapas karta hai.
     *
     * Kyun zaroori hai: TextView.setText(CharSequence) default BufferType.NORMAL
     * use karta hai, jo Spanned ko IMMUTABLE SpannedString bana deta hai. Uspar
     * na naye span lagte hain, na `instanceof Spannable` true hota hai — aur
     * hamara poora click detection ClickableSpan dhoondhne par tika hai.
     *
     * BufferType.SPANNABLE dene se TextView SpannableString banata hai, jo
     * mutable hai (press-highlight span lagane ke liye bhi zaroori).
     *
     * @return Spannable, ya null agar text hi null ho.
     */
    private static Spannable ensureSpannable(TextView tv) {
        CharSequence text = tv.getText();
        if (text == null) return null;
        if (text instanceof Spannable) return (Spannable) text;

        // Plain String ho ya SpannedString — dono ko Spannable bana do.
        // Content bilkul wahi rehta hai, sirf buffer type badalta hai, isliye
        // layout par koi asar nahi padta.
        tv.setText(text, TextView.BufferType.SPANNABLE);
        CharSequence now = tv.getText();
        return (now instanceof Spannable) ? (Spannable) now : null;
    }

    /** Long press par span ka content clipboard mein daalta hai. */
    private static void copySpan(Context ctx, Spannable spannable,
                                 ClickableSpan span, int start, int end) {
        if (ctx == null) return;

        String toCopy;
        String label;

        if (span instanceof CopyableSpan) {
            // Span khud batata hai kya copy karna hai (e.g. @mention).
            toCopy = ((CopyableSpan) span).getCopyText();
            label  = ((CopyableSpan) span).getCopyLabel();
        } else if (span instanceof URLSpan) {
            // Dikhne wala text nahi, asli URL copy karo — Linkify
            // "www.x.com" ko "http://www.x.com" bana deta hai aur paste
            // karne ke liye wahi sahi hai.
            toCopy = ((URLSpan) span).getURL();
            label  = "Link copied";
        } else {
            // Inline code, buttons, ya koi aur span — jo dikh raha hai wahi.
            toCopy = safeSub(spannable, start, end);
            label  = "Copied";
        }

        if (toCopy == null || toCopy.trim().isEmpty()) {
            toCopy = safeSub(spannable, start, end);
        }
        if (toCopy == null || toCopy.trim().isEmpty()) return;
        if (label == null || label.trim().isEmpty()) label = "Copied";

        try {
            ClipboardManager cm =
                    (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText(label, toCopy));

            // Android 13+ khud ek system "Copied" popup dikhata hai, isliye
            // wahan apna toast dobara dikhana clutter hai.
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            // Clipboard kabhi-kabhi OEM ROMs par fail hota hai — chup-chaap
            // chhod do, iske liye crash karna bekaar hai.
        }
    }

    private static String safeSub(CharSequence cs, int start, int end) {
        if (cs == null) return null;
        int s = Math.max(0, Math.min(start, cs.length()));
        int e = Math.max(s, Math.min(end, cs.length()));
        return cs.subSequence(s, e).toString();
    }

    /** Spanned import ko explicit rakhne ke liye — koi behaviour nahi. */
    @SuppressWarnings("unused")
    private static boolean isSpanned(CharSequence cs) {
        return cs instanceof Spanned;
    }
}
