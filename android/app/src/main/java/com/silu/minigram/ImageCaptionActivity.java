package com.silu.minigram;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.animation.*;
import android.widget.*;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.target.Target;
import com.yalantis.ucrop.UCrop;

import java.io.File;
import java.util.*;

/**
 * ImageCaptionActivity — Returns URIs without direct uploading.
 *
 * FIXES (Issue 3):
 *  • UCrop now has freeStyleCropEnabled = true → user can drag/resize the crop
 *    rectangle freely without any locked aspect ratio.
 *  • Removed withAspectRatio() call (wasn't here but kept for clarity).
 *  • Added hideBottomControls(false) so the crop ratio/rotate buttons are visible.
 */
public class ImageCaptionActivity extends AppCompatActivity {

    // ── Extras ───────────────────────────────────────────────────────────────
    public static final String EXTRA_IMAGE_URI   = "image_uri";
    public static final String EXTRA_IMAGE_URIS  = "image_uris";
    public static final String EXTRA_CAPTION     = "caption";
    public static final int    REQUEST_CODE      = 7001;

    private static final int REQ_CROP = 8001;

    // ── Views ────────────────────────────────────────────────────────────────
    private ImageView          img_preview;
    private DrawingView        drawing_view;
    private LinearLayout       toolbar_top;
    private LinearLayout       toolbar_draw;
    private LinearLayout       bottom_bar;
    private HorizontalScrollView scroll_thumbnails;
    private LinearLayout       layout_thumbnails;
    private EditText           et_caption;
    private TextView           btn_send;
    private TextView           btn_back;
    private TextView           txt_size_label;
    private TextView           txt_image_counter;

    private TextView           btn_tool_crop;
    private TextView           btn_tool_draw;

    private TextView           btn_draw_pen;
    private TextView           btn_draw_eraser;
    private TextView           btn_draw_undo;
    private TextView           btn_draw_done;
    private SeekBar            seek_stroke;
    private HorizontalScrollView scroll_colors;
    private LinearLayout       layout_colors;

    // ── State ────────────────────────────────────────────────────────────────
    private ArrayList<Uri> imageUris  = new ArrayList<>();
    private int currentIndex = 0;
    private boolean drawMode    = false;

    private final int[] PALETTE = {
        Color.RED, Ink.c("#FF9800"), Color.YELLOW,
        Color.GREEN, Color.CYAN, Color.BLUE, Ink.c("#9C27B0"),
        Color.WHITE, Color.BLACK
    };

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdgeHelper.setup(this);
        setContentView(R.layout.image_caption);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));
        bindViews();

        ArrayList<String> uriStrings = getIntent().getStringArrayListExtra(EXTRA_IMAGE_URIS);
        if (uriStrings != null && !uriStrings.isEmpty()) {
            for (String s : uriStrings) imageUris.add(Uri.parse(s));
        } else {
            String single = getIntent().getStringExtra(EXTRA_IMAGE_URI);
            if (single == null || single.isEmpty()) { finish(); return; }
            imageUris.add(Uri.parse(single));
        }

        currentIndex = 0;
        loadPreview(imageUris.get(currentIndex));
        showSizeLabel(imageUris.get(currentIndex));
        updateCounter();

        if (imageUris.size() > 1) {
            scroll_thumbnails.setVisibility(View.VISIBLE);
            buildThumbnailStrip();
        } else {
            scroll_thumbnails.setVisibility(View.GONE);
        }

        setupToolbar();
        setupDrawToolbar();
        setupColorPalette();
        setupBottom();
    }

    // ── Bind views ───────────────────────────────────────────────────────────

    private void bindViews() {
        img_preview       = findViewById(R.id.img_preview);
        drawing_view      = findViewById(R.id.drawing_view);
        toolbar_top       = findViewById(R.id.toolbar_top);
        toolbar_draw      = findViewById(R.id.toolbar_draw);
        bottom_bar        = findViewById(R.id.bottom_bar);
        scroll_thumbnails = findViewById(R.id.scroll_thumbnails);
        layout_thumbnails = findViewById(R.id.layout_thumbnails);
        et_caption        = findViewById(R.id.et_caption);
        btn_send          = findViewById(R.id.btn_send);
        btn_back          = findViewById(R.id.btn_back);
        txt_size_label    = findViewById(R.id.txt_size_label);
        txt_image_counter = findViewById(R.id.txt_image_counter);

        btn_tool_crop     = findViewById(R.id.btn_tool_crop);
        btn_tool_draw     = findViewById(R.id.btn_tool_draw);
        btn_draw_pen      = findViewById(R.id.btn_draw_pen);
        btn_draw_eraser   = findViewById(R.id.btn_draw_eraser);
        btn_draw_undo     = findViewById(R.id.btn_draw_undo);
        btn_draw_done     = findViewById(R.id.btn_draw_done);
        seek_stroke       = findViewById(R.id.seek_stroke);
        scroll_colors     = findViewById(R.id.scroll_colors);
        layout_colors     = findViewById(R.id.layout_colors);
    }

    // ── Thumbnail strip (multi-image) ────────────────────────────────────────

    private void buildThumbnailStrip() {
        if (layout_thumbnails == null) return;
        layout_thumbnails.removeAllViews();
        int sizePx   = dp(64);
        int marginPx = dp(4);

        for (int i = 0; i < imageUris.size(); i++) {
            final int idx = i;
            FrameLayout wrapper = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
            lp.setMargins(marginPx, marginPx, marginPx, marginPx);
            wrapper.setLayoutParams(lp);

            ImageView thumb = new ImageView(this);
            thumb.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);

            GradientDrawable border = new GradientDrawable();
            border.setShape(GradientDrawable.RECTANGLE);
            border.setCornerRadius(dp(6));
            border.setStroke(dp(i == currentIndex ? 3 : 1),
                i == currentIndex ? Ink.c("#2196F3") : Ink.c("#757575"));
            wrapper.setBackground(border);
            wrapper.setClipToOutline(true);

            Glide.with(this).load(imageUris.get(i))
                .centerCrop()
                .into(thumb);

            wrapper.addView(thumb);
            wrapper.setOnClickListener(v -> {
                if (drawMode) exitDrawMode();
                currentIndex = idx;
                loadPreview(imageUris.get(currentIndex));
                showSizeLabel(imageUris.get(currentIndex));
                updateCounter();
                refreshThumbnailSelection();
            });

            layout_thumbnails.addView(wrapper);
        }
    }

    private void refreshThumbnailSelection() {
        if (layout_thumbnails == null) return;
        for (int i = 0; i < layout_thumbnails.getChildCount(); i++) {
            View child = layout_thumbnails.getChildAt(i);
            GradientDrawable border = new GradientDrawable();
            border.setShape(GradientDrawable.RECTANGLE);
            border.setCornerRadius(dp(6));
            border.setStroke(dp(i == currentIndex ? 3 : 1),
                i == currentIndex ? Ink.c("#2196F3") : Ink.c("#757575"));
            child.setBackground(border);
        }
    }

    private void updateCounter() {
        if (txt_image_counter == null) return;
        if (imageUris.size() > 1) {
            txt_image_counter.setVisibility(View.VISIBLE);
            txt_image_counter.setText((currentIndex + 1) + " / " + imageUris.size());
        } else {
            txt_image_counter.setVisibility(View.GONE);
        }
    }

    // ── Load preview ─────────────────────────────────────────────────────────

    private void loadPreview(Uri uri) {
        drawing_view.clear();
        Glide.with(this).load(uri)
            .placeholder(R.drawable.img_placeholder)
            .error(R.drawable.img_placeholder)
            .into(img_preview);
    }

    private void showSizeLabel(Uri uri) {
        String label = ImageEditHelper.getFileSizeLabel(this, uri);
        if (txt_size_label != null && !label.isEmpty())
            txt_size_label.setText(label);
    }

    // ── Toolbar (top) ────────────────────────────────────────────────────────

    private void setupToolbar() {
        btn_back.setOnClickListener(v -> {
            if (drawMode) exitDrawMode();
            else finish();
        });

        if (btn_tool_crop != null) {
            btn_tool_crop.setOnClickListener(v -> {
                Uri dest = ImageEditHelper.getCropDestination(this);
                // ── Issue 3 FIX: free-form crop with draggable handles ────────
                // withAspectRatio() is intentionally NOT called → fully free crop.
                // freeStyleCropEnabled(true) lets user drag any corner/side freely.
                UCrop.of(imageUris.get(currentIndex), dest)
                    .withMaxResultSize(2048, 2048)   // raised limit for quality
                    .withOptions(buildUCropOptions())
                    .start(this, REQ_CROP);
            });
        }

        if (btn_tool_draw != null) {
            btn_tool_draw.setOnClickListener(v -> {
                if (drawMode) exitDrawMode();
                else enterDrawMode();
            });
        }
    }

    /**
     * Issue 3 — UCrop options:
     *  • setFreeStyleCropEnabled(true)  — user drags/resizes crop handles freely
     *  • hideBottomControls(false)      — keeps rotate/flip controls visible
     *  • No withAspectRatio()           — no locked ratio; user has full control
     */
    private com.yalantis.ucrop.UCrop.Options buildUCropOptions() {
        com.yalantis.ucrop.UCrop.Options opts = new com.yalantis.ucrop.UCrop.Options();
        opts.setToolbarColor(Color.BLACK);
        opts.setStatusBarColor(Color.BLACK);
        opts.setActiveControlsWidgetColor(Ink.c("#2196F3"));
        opts.setToolbarWidgetColor(Color.WHITE);
        opts.setToolbarTitle("Crop Image");

        // ── Issue 3: Free-form crop — no aspect ratio lock ───────────────────
        opts.setFreeStyleCropEnabled(true);   // drag any corner / side freely
        opts.setShowCropGrid(true);           // show grid lines for alignment
        opts.setShowCropFrame(true);          // show crop frame border
        opts.setHideBottomControls(false);    // keep rotate / flip buttons visible

        return opts;
    }

    // ── Draw mode ────────────────────────────────────────────────────────────

    private void enterDrawMode() {
        drawMode = true;

        // Map the DrawingView exact bounds to the actual fitCenter visible image bounds
        if (img_preview.getDrawable() != null) {
            RectF imageRectF = new RectF(0, 0,
                img_preview.getDrawable().getIntrinsicWidth(),
                img_preview.getDrawable().getIntrinsicHeight());
            img_preview.getImageMatrix().mapRect(imageRectF);

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) drawing_view.getLayoutParams();
            lp.width = Math.round(imageRectF.width());
            lp.height = Math.round(imageRectF.height());
            lp.gravity = Gravity.CENTER;
            drawing_view.setLayoutParams(lp);
        }

        drawing_view.setVisibility(View.VISIBLE);
        toolbar_draw.setVisibility(View.VISIBLE);
        toolbar_top.setVisibility(View.GONE);
        bottom_bar.setVisibility(View.GONE);
        scroll_thumbnails.setVisibility(View.GONE);
        drawing_view.setEraserMode(false);
        drawing_view.setStrokeColor(Color.RED);
        drawing_view.setStrokeWidth(14f);
    }

    private void exitDrawMode() {
        drawMode = false;
        toolbar_draw.setVisibility(View.GONE);
        toolbar_top.setVisibility(View.VISIBLE);
        bottom_bar.setVisibility(View.VISIBLE);
        if (imageUris.size() > 1) scroll_thumbnails.setVisibility(View.VISIBLE);
        drawing_view.setVisibility(View.GONE);

        try {
            Bitmap bgBmp    = ((android.graphics.drawable.BitmapDrawable) img_preview.getDrawable()).getBitmap();
            Bitmap drawBmp  = drawing_view.exportBitmap();
            Bitmap drawScaled = Bitmap.createScaledBitmap(drawBmp, bgBmp.getWidth(), bgBmp.getHeight(), true);
            Bitmap merged   = ImageEditHelper.flattenDrawing(bgBmp, drawScaled);
            img_preview.setImageBitmap(merged);
            Uri editedUri = Uri.fromFile(new File(getCacheDir(),
                "drawn_" + currentIndex + "_" + System.currentTimeMillis() + ".jpg"));
            new Thread(() -> {
                try {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(editedUri.getPath());
                    merged.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                    fos.close();
                    imageUris.set(currentIndex, editedUri);
                } catch (Exception ignored) {}
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            drawing_view.clear();
        }
    }

    // ── Draw toolbar ─────────────────────────────────────────────────────────

    private void setupDrawToolbar() {
        if (btn_draw_pen    != null) btn_draw_pen.setOnClickListener(v -> { drawing_view.setEraserMode(false); highlightTool(btn_draw_pen); });
        if (btn_draw_eraser != null) btn_draw_eraser.setOnClickListener(v -> { drawing_view.setEraserMode(true); highlightTool(btn_draw_eraser); });
        if (btn_draw_undo   != null) btn_draw_undo.setOnClickListener(v -> drawing_view.undo());
        if (btn_draw_done   != null) btn_draw_done.setOnClickListener(v -> exitDrawMode());
        if (seek_stroke != null) {
            seek_stroke.setMax(40); seek_stroke.setProgress(10);
            seek_stroke.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { drawing_view.setStrokeWidth(8f + p); }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }
    }

    private void highlightTool(TextView active) {
        if (btn_draw_pen    != null) btn_draw_pen.setAlpha(0.5f);
        if (btn_draw_eraser != null) btn_draw_eraser.setAlpha(0.5f);
        active.setAlpha(1f);
    }

    // ── Color palette ────────────────────────────────────────────────────────

    private void setupColorPalette() {
        if (layout_colors == null) return;
        int sizePx = dp(32), marginPx = dp(6);
        for (int color : PALETTE) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
            lp.setMargins(marginPx, 0, marginPx, 0);
            dot.setLayoutParams(lp);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(color);
            gd.setStroke(dp(2), Color.WHITE);
            dot.setBackground(gd);
            final int c = color;
            dot.setOnClickListener(v -> {
                drawing_view.setEraserMode(false);
                drawing_view.setStrokeColor(c);
                v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()).start();
            });
            layout_colors.addView(dot);
        }
    }

    // ── Bottom bar ───────────────────────────────────────────────────────────

    private void setupBottom() {
        et_caption.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        btn_send.setOnClickListener(v -> {
            String caption = et_caption.getText().toString().trim();
            ArrayList<String> resultUris = new ArrayList<>();
            for (Uri u : imageUris) {
                resultUris.add(u.toString());
            }
            Intent result = new Intent();
            result.putStringArrayListExtra(EXTRA_IMAGE_URIS, resultUris);
            result.putExtra(EXTRA_CAPTION, caption);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    // ── UCrop result ─────────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CROP && resultCode == RESULT_OK && data != null) {
            Uri cropped = UCrop.getOutput(data);
            if (cropped != null) {
                imageUris.set(currentIndex, cropped);
                loadPreview(cropped);
                showSizeLabel(cropped);
                if (layout_thumbnails != null && currentIndex < layout_thumbnails.getChildCount()) {
                    ImageView thumb = (ImageView) ((FrameLayout) layout_thumbnails.getChildAt(currentIndex)).getChildAt(0);
                    Glide.with(this).load(cropped).centerCrop().into(thumb);
                }
            }
        } else if (resultCode == UCrop.RESULT_ERROR && data != null) {
            Throwable err = UCrop.getError(data);
            if (err != null)
                Toast.makeText(this, "Crop failed: " + err.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawMode) exitDrawMode();
        else super.onBackPressed();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
