package com.silu.minigram;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * ZoomableImageView
 * ─────────────────────────────────────────────────────────────────────────────
 * 100% custom — NO PhotoView or any third-party library.
 *
 * Features:
 *   • Pinch zoom  (ScaleGestureDetector, focal-point aware)
 *   • Double tap  (1x → 3x → 1x toggle, taps at finger position)
 *   • Single tap  (callback → UI toggle)
 *   • Pan         (GestureDetector.onScroll with boundary clamping)
 *   • Swipe-to-dismiss (vertical only, only at MIN_SCALE, single finger)
 *
 * Touch event ownership:
 *   ScaleGestureDetector  gets EVERY event (needs full stream for multi-touch).
 *   GestureDetector       gets events EXCEPT during active swipe-drag.
 *   This prevents the double-processing conflict that broke PhotoView.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class ZoomableImageView extends AppCompatImageView {

    // ── Scale constants ───────────────────────────────────────────────────────
    private static final float MIN_SCALE        = 1.0f;
    private static final float MAX_SCALE        = 5.0f;
    private static final float DOUBLE_TAP_SCALE = 3.0f;
    private static final long  ANIM_DURATION    = 250L;

    // ── Swipe-dismiss constants ───────────────────────────────────────────────
    /** Pixels of vertical drag required to trigger dismiss */
    private static final float DISMISS_THRESHOLD   = 200f;
    /** Range over which alpha fades from 1→0.25 during drag */
    private static final float DISMISS_ALPHA_RANGE = 450f;
    /** dy/dx ratio above which drag is considered a vertical swipe */
    private static final float SWIPE_ANGLE_RATIO   = 1.5f;
    /** Minimum pixel movement before a swipe is registered */
    private static final float SWIPE_SLOP          = 18f;

    // ── Matrix state ──────────────────────────────────────────────────────────
    private final Matrix  matrix     = new Matrix();
    private final Matrix  initMatrix = new Matrix();   // fitCenter baseline
    private final float[] vals       = new float[9];
    private boolean initialized      = false;
    private float   viewW, viewH;

    // ── Gesture detectors ─────────────────────────────────────────────────────
    private ScaleGestureDetector pinchDetector;
    private GestureDetector      tapDetector;

    // ── Swipe-dismiss runtime state ───────────────────────────────────────────
    private float   swipeStartX, swipeStartY;
    private boolean swipeDragging = false;

    // ── Active zoom animation (cancel-safe) ───────────────────────────────────
    private ValueAnimator zoomAnimator;

    // ── Callbacks ─────────────────────────────────────────────────────────────
    public interface OnSingleTapListener {
        void onSingleTap();
    }

    public interface OnSwipeDismissListener {
        /** Alpha to apply to the root container while dragging */
        void onSwipeProgress(float alpha);
        /** Threshold exceeded — perform the dismiss. direction: +1 down, -1 up */
        void onSwipeDismiss(float direction);
        /** User released before threshold — snap everything back */
        void onSwipeSnapBack();
    }

    private OnSingleTapListener    singleTapListener;
    private OnSwipeDismissListener swipeDismissListener;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────────────

    public ZoomableImageView(Context ctx) {
        super(ctx);
        init(ctx);
    }

    public ZoomableImageView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init(ctx);
    }

    public ZoomableImageView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        init(ctx);
    }

    private void init(Context ctx) {
        setScaleType(ScaleType.MATRIX);

        pinchDetector = new ScaleGestureDetector(ctx, new PinchListener());
        // Disable the "quick scale" (double-tap drag) built into ScaleGestureDetector
        // to avoid conflict with our own double-tap handler.
        pinchDetector.setQuickScaleEnabled(false);

        tapDetector = new GestureDetector(ctx, new TapScrollListener());
        tapDetector.setOnDoubleTapListener(new DoubleTapListener());
        tapDetector.setIsLongpressEnabled(false);
    }

    // ── Listener setters ─────────────────────────────────────────────────────

    public void setOnSingleTapListener(OnSingleTapListener l)      { singleTapListener   = l; }
    public void setOnSwipeDismissListener(OnSwipeDismissListener l) { swipeDismissListener = l; }

    // ─────────────────────────────────────────────────────────────────────────
    // fitCenter matrix init
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        viewW = w;
        viewH = h;
        resetToFitCenter();
    }

    /**
     * Called by Glide (or any image loader) when the bitmap arrives.
     * post() defers until the next layout pass so viewW/viewH are valid.
     */
    @Override
    public void setImageDrawable(Drawable d) {
        super.setImageDrawable(d);
        initialized = false;
        post(this::resetToFitCenter);
    }

    /**
     * Build and cache the fitCenter matrix so we can animate back to it precisely.
     */
    private void resetToFitCenter() {
        Drawable d = getDrawable();
        if (d == null || viewW == 0 || viewH == 0) return;

        float iW = d.getIntrinsicWidth();
        float iH = d.getIntrinsicHeight();
        if (iW <= 0 || iH <= 0) return;

        float scale = Math.min(viewW / iW, viewH / iH);
        float dx    = (viewW - iW * scale) / 2f;
        float dy    = (viewH - iH * scale) / 2f;

        matrix.reset();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        initMatrix.set(matrix);          // save a clean copy

        setImageMatrix(matrix);
        initialized = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Matrix helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Current scale factor (MSCALE_X == MSCALE_Y after uniform scaling). */
    private float currentScale() {
        matrix.getValues(vals);
        return vals[Matrix.MSCALE_X];
    }

    /** True when the view is fully zoomed out (within a small epsilon). */
    private boolean atMinScale() {
        return currentScale() <= MIN_SCALE + 0.07f;
    }

    /**
     * Clamping pass:
     *  1. Hard-clamp scale into [MIN_SCALE, MAX_SCALE].
     *  2. Prevent the image from being panned outside the visible area.
     *     When the image is smaller than the view, keep it centred.
     */
    private void clamp() {
        float sc = currentScale();

        // 1. Scale bounds
        if (sc < MIN_SCALE) {
            float f = MIN_SCALE / sc;
            matrix.postScale(f, f, viewW / 2f, viewH / 2f);
        } else if (sc > MAX_SCALE) {
            float f = MAX_SCALE / sc;
            matrix.postScale(f, f, viewW / 2f, viewH / 2f);
        }

        // 2. Translation bounds
        RectF r  = imageRect();
        float dx = 0f, dy = 0f;

        if (r.width() <= viewW) {
            dx = (viewW - r.width()) / 2f - r.left;   // centre H
        } else {
            if (r.left > 0)      dx = -r.left;
            if (r.right < viewW) dx = viewW - r.right;
        }

        if (r.height() <= viewH) {
            dy = (viewH - r.height()) / 2f - r.top;   // centre V
        } else {
            if (r.top > 0)        dy = -r.top;
            if (r.bottom < viewH) dy = viewH - r.bottom;
        }

        matrix.postTranslate(dx, dy);
    }

    /** Bounding rect of the image in view coordinates after current matrix. */
    private RectF imageRect() {
        Drawable d = getDrawable();
        if (d == null) return new RectF();
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(r);
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animated zoom
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Smoothly animate to targetScale.
     *
     * Snap-back to MIN_SCALE uses full matrix interpolation (initMatrix) so the
     * image always lands perfectly centred regardless of where the focal point is.
     *
     * Zoom-in uses incremental postScale at the focal point so it zooms toward
     * exactly where the user tapped.
     */
    private void animateZoom(float targetScale, float focalX, float focalY) {
        if (zoomAnimator != null && zoomAnimator.isRunning()) zoomAnimator.cancel();

        boolean toInit = targetScale <= MIN_SCALE + 0.05f;

        if (toInit) {
            // ── Interpolate the entire matrix → always lands on fitCenter ──────
            float[] startVals = new float[9];
            float[] endVals   = new float[9];
            matrix.getValues(startVals);
            initMatrix.getValues(endVals);

            zoomAnimator = ValueAnimator.ofFloat(0f, 1f);
            zoomAnimator.setDuration(ANIM_DURATION);
            zoomAnimator.setInterpolator(new DecelerateInterpolator());
            zoomAnimator.addUpdateListener(a -> {
                float t  = (float) a.getAnimatedValue();
                float[] iv = new float[9];
                for (int i = 0; i < 9; i++) iv[i] = startVals[i] + (endVals[i] - startVals[i]) * t;
                matrix.setValues(iv);
                setImageMatrix(matrix);
            });

        } else {
            // ── Incremental scale at focal point ─────────────────────────────
            float startScale = currentScale();
            final float[] last = {startScale};

            zoomAnimator = ValueAnimator.ofFloat(startScale, targetScale);
            zoomAnimator.setDuration(ANIM_DURATION);
            zoomAnimator.setInterpolator(new DecelerateInterpolator());
            zoomAnimator.addUpdateListener(a -> {
                float cur    = (float) a.getAnimatedValue();
                float factor = cur / last[0];
                last[0] = cur;
                matrix.postScale(factor, factor, focalX, focalY);
                clamp();
                setImageMatrix(matrix);
            });
        }

        zoomAnimator.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // onTouchEvent — single dispatcher, clear ownership rules
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!initialized) return false;

        int action = ev.getActionMasked();

        // ScaleGestureDetector ALWAYS receives every event.
        // It needs the complete pointer stream to track multi-finger gestures.
        pinchDetector.onTouchEvent(ev);

        switch (action) {

            // ── DOWN — record swipe origin, init GestureDetector ─────────────
            case MotionEvent.ACTION_DOWN:
                if (zoomAnimator != null) zoomAnimator.cancel();
                swipeStartX   = ev.getRawX();
                swipeStartY   = ev.getRawY();
                swipeDragging = false;
                tapDetector.onTouchEvent(ev);
                return true;

            // ── POINTER_DOWN — multi-finger → abort active swipe ─────────────
            case MotionEvent.ACTION_POINTER_DOWN:
                if (swipeDragging) {
                    swipeDragging = false;
                    animate().translationY(0f).setDuration(200).start();
                    if (swipeDismissListener != null) swipeDismissListener.onSwipeSnapBack();
                }
                tapDetector.onTouchEvent(ev);
                return true;

            // ── MOVE — swipe logic takes priority; GestureDetector gets the rest
            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() == 1 && !pinchDetector.isInProgress()) {
                    float dy = ev.getRawY() - swipeStartY;
                    float dx = ev.getRawX() - swipeStartX;

                    // Start swipe only when image is at min scale and gesture
                    // is predominantly vertical.
                    if (!swipeDragging && atMinScale()) {
                        if (Math.abs(dy) > SWIPE_SLOP
                                && Math.abs(dy) > Math.abs(dx) * SWIPE_ANGLE_RATIO) {
                            swipeDragging = true;
                        }
                    }

                    if (swipeDragging) {
                        // Move the entire ImageView (not just the bitmap matrix)
                        setTranslationY(dy);

                        // Feed alpha to the container (0.25 at full drag)
                        float fraction = Math.min(Math.abs(dy) / DISMISS_ALPHA_RANGE, 0.75f);
                        if (swipeDismissListener != null)
                            swipeDismissListener.onSwipeProgress(1f - fraction);

                        return true;   // consumed — GestureDetector gets nothing
                    }
                }
                tapDetector.onTouchEvent(ev);
                return true;

            // ── UP / CANCEL — decide: dismiss or snap back ────────────────────
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (swipeDragging) {
                    float dy = ev.getRawY() - swipeStartY;
                    swipeDragging = false;

                    if (Math.abs(dy) > DISMISS_THRESHOLD) {
                        if (swipeDismissListener != null)
                            swipeDismissListener.onSwipeDismiss(dy > 0 ? 1f : -1f);
                    } else {
                        animate().translationY(0f)
                                .setDuration(300)
                                .setInterpolator(new OvershootInterpolator(1.2f))
                                .start();
                        if (swipeDismissListener != null)
                            swipeDismissListener.onSwipeSnapBack();
                    }
                    return true;
                }
                tapDetector.onTouchEvent(ev);
                return true;

            default:
                tapDetector.onTouchEvent(ev);
                return true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pinch zoom listener
    // ─────────────────────────────────────────────────────────────────────────

    private class PinchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        @Override
        public boolean onScaleBegin(ScaleGestureDetector d) {
            // Cancel any running zoom animation when user starts pinching
            if (zoomAnimator != null) zoomAnimator.cancel();
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector d) {
            if (swipeDragging) return true;

            float factor = d.getScaleFactor();
            float sc     = currentScale();

            // Soft clamp — resist going beyond limits rather than hard-stop
            if (sc * factor < MIN_SCALE) factor = MIN_SCALE / sc;
            if (sc * factor > MAX_SCALE) factor = MAX_SCALE / sc;

            matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
            clamp();
            setImageMatrix(matrix);
            return true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pan listener (GestureDetector.onScroll)
    // ─────────────────────────────────────────────────────────────────────────

    private class TapScrollListener extends GestureDetector.SimpleOnGestureListener {

        /** Must return true so GestureDetector delivers subsequent scroll events. */
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dX, float dY) {
            if (swipeDragging || pinchDetector.isInProgress()) return false;
            matrix.postTranslate(-dX, -dY);
            clamp();
            setImageMatrix(matrix);
            return true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Double-tap and single-tap listener
    // ─────────────────────────────────────────────────────────────────────────

    private class DoubleTapListener implements GestureDetector.OnDoubleTapListener {

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (singleTapListener != null) singleTapListener.onSingleTap();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (atMinScale()) {
                // Zoom IN at exact tap position
                animateZoom(DOUBLE_TAP_SCALE, e.getX(), e.getY());
            } else {
                // Snap back to fitCenter (uses initMatrix interpolation)
                animateZoom(MIN_SCALE, viewW / 2f, viewH / 2f);
            }
            return true;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            return false;  // handled in onDoubleTap
        }
    }
}
