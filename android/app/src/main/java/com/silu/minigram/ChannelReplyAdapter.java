package com.silu.minigram;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.text.SimpleDateFormat;
import java.util.*;

public class ChannelReplyAdapter extends BaseAdapter {

    public interface OnDeleteListener {
        void onDelete(String replyId);
    }

    private static final int[] AVATAR_COLORS = {
        0xFFE91E63, 0xFF9C27B0, 0xFF3F51B5,
        0xFF2196F3, 0xFF009688, 0xFFFF5722, 0xFFFF9800
    };

    private final Context                            context;
    private final ArrayList<HashMap<String, Object>> list;
    private final String                             myUid;
    private final boolean                            isOwner;
    private final OnDeleteListener                   deleteListener;

    public ChannelReplyAdapter(Context context,
                                ArrayList<HashMap<String, Object>> list,
                                String myUid,
                                boolean isOwner,
                                OnDeleteListener deleteListener) {
        this.context        = context;
        this.list           = list;
        this.myUid          = myUid != null ? myUid : "";
        this.isOwner        = isOwner;
        this.deleteListener = deleteListener;
    }

    @Override public int    getCount()         { return list.size(); }
    @Override public Object getItem(int pos)   { return list.get(pos); }
    @Override public long   getItemId(int pos) { return pos; }

    @Override
    public View getView(int pos, View cv, ViewGroup parent) {
        if (cv == null)
            cv = LayoutInflater.from(context)
                .inflate(R.layout.item_channel_reply, parent, false);

        HashMap<String, Object> reply = list.get(pos);

        final String replyId  = (String)  reply.get("replyId");
        String senderUid      = (String)  reply.get("sender_uid");
        String senderName     = (String)  reply.get("sender_name");
        String senderPhoto    = (String)  reply.get("sender_photo");
        String text           = (String)  reply.get("text");
        Long   ts             = (Long)    reply.get("timestamp");
        boolean isDeleted     = Boolean.TRUE.equals(reply.get("is_deleted"));

        if (senderName  == null || senderName.trim().isEmpty()) senderName = "User";
        if (text        == null) text = "";
        if (senderUid   == null) senderUid = "";
        if (senderPhoto == null) senderPhoto = "";

        // ── View refs ─────────────────────────────────────────────────────────
        ImageView imgAvatar = cv.findViewById(R.id.img_avatar);
        TextView  txtName   = cv.findViewById(R.id.txt_sender_name);
        TextView  txtText   = cv.findViewById(R.id.txt_reply_text);
        TextView  txtTime   = cv.findViewById(R.id.txt_reply_time);
        TextView  btnDelete = cv.findViewById(R.id.btn_delete_reply);

        // ── Avatar ────────────────────────────────────────────────────────────
        if (imgAvatar != null) {
            imgAvatar.setVisibility(View.VISIBLE);

            if (!senderPhoto.isEmpty()) {
                // Real profile photo → Glide load karo
                Glide.with(context)
                    .load(senderPhoto)
                    .circleCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(imgAvatar);
            } else {
                // No photo → colored circle with initial letter (programmatic)
                String initial    = senderName.substring(0, 1).toUpperCase(Locale.getDefault());
                int    colorIndex = Math.abs(senderUid.hashCode()) % AVATAR_COLORS.length;
                Bitmap bm         = _makeInitialBitmap(initial, AVATAR_COLORS[colorIndex]);
                imgAvatar.setImageBitmap(bm);
            }
        }

        // ── Sender name ───────────────────────────────────────────────────────
        if (txtName != null) txtName.setText(senderName);

        // ── Reply text ────────────────────────────────────────────────────────
        if (txtText != null) {
            if (isDeleted) {
                txtText.setText("🚫 Reply deleted");
                txtText.setTextColor(Color.parseColor("#757575"));
                txtText.setTypeface(null, Typeface.ITALIC);
            } else {
                txtText.setText(text);
                txtText.setTextColor(Color.parseColor("#424242"));
                txtText.setTypeface(null, Typeface.NORMAL);
            }
        }

        // ── Timestamp ─────────────────────────────────────────────────────────
        if (txtTime != null && ts != null)
            txtTime.setText(formatTime(ts));

        // ── Delete button ─────────────────────────────────────────────────────
        if (btnDelete != null) {
            boolean canDelete = !isDeleted && (isOwner || myUid.equals(senderUid));
            btnDelete.setVisibility(canDelete ? View.VISIBLE : View.GONE);
            if (canDelete) {
                final String fId = replyId;
                btnDelete.setOnClickListener(v -> {
                    if (deleteListener != null && fId != null)
                        deleteListener.onDelete(fId);
                });
            } else {
                btnDelete.setOnClickListener(null);
            }
        }

        return cv;
    }

    // =========================================================================
    // Colored circle bitmap with initial letter (no XML TextView needed)
    // =========================================================================
    private Bitmap _makeInitialBitmap(String initial, int bgColor) {
        int size   = 120;
        Bitmap bm  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cvs = new Canvas(bm);

        // Background circle
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(bgColor);
        cvs.drawCircle(size / 2f, size / 2f, size / 2f, p);

        // Initial letter
        p.setColor(Color.WHITE);
        p.setTextSize(size * 0.42f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        Paint.FontMetrics fm = p.getFontMetrics();
        float y = size / 2f - (fm.ascent + fm.descent) / 2f;
        cvs.drawText(initial, size / 2f, y, p);

        return bm;
    }

    // =========================================================================
    // Time formatter
    // =========================================================================
    private String formatTime(long ts) {
        Calendar c     = Calendar.getInstance(); c.setTimeInMillis(ts);
        Calendar today = Calendar.getInstance();
        if (c.get(Calendar.YEAR)        == today.get(Calendar.YEAR)
         && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR))
            return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(ts));
        return new SimpleDateFormat("d MMM · hh:mm a", Locale.getDefault()).format(new Date(ts));
    }
}
