package com.silu.minigram;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import java.util.List;

/**
 * MediaPickerAdapter
 * GridView adapter for TelegramMediaPicker bottom sheet.
 *
 * Item 0 = Camera tile (dark background + camera icon)
 * Item 1..N = Device images (thumbnail + selection badge)
 *
 * Selection: single tap toggles, badge shows order number.
 * Blue border on selected cells — Telegram style.
 */
public class MediaPickerAdapter extends BaseAdapter {

    // ─── View Types ──────────────────────────────────────────────────────────
    private static final int TYPE_CAMERA = 0;
    private static final int TYPE_IMAGE  = 1;

    // ─── Callback interface ──────────────────────────────────────────────────
    public interface OnCellActionListener {
        void onCameraClicked();
        void onImageToggled(Uri uri, boolean selected);
        void onImageLongPressed(Uri uri);
    }

    // ─── State ───────────────────────────────────────────────────────────────
    private final Context             context;
    private final List<Uri>           imageUris;
    private final List<Uri>           selectedUris;
    private final OnCellActionListener listener;
    private       int                 cellSize = 0; // set externally after layout

    // Cached Material typeface to avoid repeated asset loads
    private static android.graphics.Typeface materialTypeface = null;

    // ─── Constructor ─────────────────────────────────────────────────────────
    public MediaPickerAdapter(Context ctx,
                              List<Uri> images,
                              List<Uri> selected,
                              OnCellActionListener l) {
        this.context      = ctx;
        this.imageUris    = images;
        this.selectedUris = selected;
        this.listener     = l;
        _loadMaterialFont();
    }

    /** Called from TelegramMediaPicker after GridView is laid out */
    public void setCellSize(int px) {
        this.cellSize = px;
        notifyDataSetChanged();
    }

    // ─── BaseAdapter ─────────────────────────────────────────────────────────
    @Override public int getCount()        { return imageUris.size() + 1; } // +1 camera
    @Override public Object getItem(int p) { return p == 0 ? null : imageUris.get(p - 1); }
    @Override public long getItemId(int p) { return p; }
    @Override public int getViewTypeCount(){ return 2; }

    @Override
    public int getItemViewType(int pos) {
        return pos == 0 ? TYPE_CAMERA : TYPE_IMAGE;
    }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {

        // ── Apply square size ─────────────────────────────────────────────────
        if (convertView == null) {
            convertView = LayoutInflater.from(context)
                .inflate(R.layout.item_media_picker_cell, parent, false);
        }
        if (cellSize > 0) {
            GridView.LayoutParams lp = new GridView.LayoutParams(
                GridView.LayoutParams.MATCH_PARENT, cellSize);
            convertView.setLayoutParams(lp);
        }

        // ── Bind views ────────────────────────────────────────────────────────
        ImageView imgCell     = convertView.findViewById(R.id.img_cell);
        View      scrim       = convertView.findViewById(R.id.cell_scrim);
        TextView  overlayCheck= convertView.findViewById(R.id.overlay_check);
        TextView  badgeCount  = convertView.findViewById(R.id.badge_count);
        TextView  cameraIcon  = convertView.findViewById(R.id.camera_icon);
        TextView  cameraLabel = convertView.findViewById(R.id.camera_label);

        // ─────────────────────────────────────────────────────────────────────
        //  CAMERA TILE
        // ─────────────────────────────────────────────────────────────────────
        if (pos == 0) {
            imgCell.setImageDrawable(null);
            imgCell.setBackgroundColor(Ink.c("#1E1E1E"));

            cameraIcon.setVisibility(View.VISIBLE);
            cameraLabel.setVisibility(View.VISIBLE);
            overlayCheck.setVisibility(View.GONE);
            badgeCount.setVisibility(View.GONE);
            scrim.setVisibility(View.INVISIBLE);
            convertView.setBackground(null);
            convertView.setAlpha(1f);

            convertView.setOnClickListener(v -> {
                if (listener != null) listener.onCameraClicked();
            });
            convertView.setOnLongClickListener(null);
            convertView.setClickable(true);
            convertView.setEnabled(true);
            convertView.setPressed(false);
            return convertView;
        }

        // ─────────────────────────────────────────────────────────────────────
        //  IMAGE TILE
        // ─────────────────────────────────────────────────────────────────────
        cameraIcon.setVisibility(View.GONE);
        cameraLabel.setVisibility(View.GONE);
        imgCell.setBackgroundColor(Ink.c("#2C2C2E"));

        Uri uri        = imageUris.get(pos - 1);
        boolean isSel  = selectedUris.contains(uri);
        int     selIdx = isSel ? selectedUris.indexOf(uri) + 1 : -1;

        // Thumbnail load
        Glide.with(context)
            .load(uri)
            .apply(new RequestOptions()
                .override(400, 400)
                .centerCrop()
                .placeholder(android.R.drawable.ic_menu_gallery))
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .thumbnail(0.25f)
            .into(imgCell);

        // Selection state visuals
        if (isSel) {
            // Blue border
            GradientDrawable border = new GradientDrawable();
            border.setShape(GradientDrawable.RECTANGLE);
            border.setStroke(dp(3), Ink.c("#1A73E8"));
            convertView.setBackground(border);
            scrim.setVisibility(View.VISIBLE);
            convertView.setAlpha(1f);

            // Number badge (replaces check icon)
            overlayCheck.setVisibility(View.GONE);
            badgeCount.setVisibility(View.VISIBLE);
            badgeCount.setText(String.valueOf(selIdx));

            // Programmatic blue circle bg for badge
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Ink.c("#1A73E8"));
            circle.setStroke(dp(1), Color.WHITE);
            badgeCount.setBackground(circle);
            badgeCount.setTextColor(Color.WHITE);

        } else {
            convertView.setBackground(null);
            scrim.setVisibility(View.INVISIBLE);
            badgeCount.setVisibility(View.GONE);

            // Hollow circle hint
            overlayCheck.setVisibility(View.VISIBLE);
            overlayCheck.setText("radio_button_unchecked");
            overlayCheck.setTypeface(materialTypeface);
            overlayCheck.setTextColor(Color.parseColor("#AAFFFFFF"));
            overlayCheck.setTextSize(28);
            overlayCheck.setBackground(null);
            convertView.setAlpha(1f);
        }

        final Uri finalUri = uri;
        convertView.setOnClickListener(v -> {
            if (listener != null)
                listener.onImageToggled(finalUri, !selectedUris.contains(finalUri));
        });
        convertView.setOnLongClickListener(v -> {
            if (listener != null) listener.onImageLongPressed(finalUri);
            return true;
        });

        return convertView;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void _loadMaterialFont() {
        if (materialTypeface == null) {
            try {
                materialTypeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.material_icons);
            } catch (Exception e) {
                materialTypeface = android.graphics.Typeface.DEFAULT;
            }
        }
    }

    private int dp(int v) {
        return (int)(v * context.getResources().getDisplayMetrics().density);
    }
}
