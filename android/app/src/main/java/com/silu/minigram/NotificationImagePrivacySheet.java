package com.silu.minigram;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * NotificationImagePrivacySheet
 *
 * Per-channel / per-chat bottom sheet that lets the user toggle "Blur notification
 * images" for ONE specific channel or chat.
 *
 * How it works:
 *   • Stores a boolean in NotificationPrivacyHelper (SharedPreferences) keyed by
 *     the targetId (channelId for channel posts, chatId for DM chats).
 *   • FCMNotificationSender reads this before building the notification and applies
 *     NotificationImageUtil.stackBlur() when the flag is true.
 *
 * Usage from ChannelActivity:
 *   NotificationImagePrivacySheet
 *       .forChannel(channelId, channelName, channelPhotoUrl)
 *       .show(getSupportFragmentManager(), "notif_img_privacy");
 *
 * Usage from ChatActivity:
 *   NotificationImagePrivacySheet
 *       .forChat(chatId, peerName, peerPhotoUrl)
 *       .show(getSupportFragmentManager(), "notif_img_privacy");
 */
public class NotificationImagePrivacySheet extends BottomSheetDialogFragment {

    private static final String ARG_TARGET_ID   = "target_id";
    private static final String ARG_TARGET_NAME = "target_name";
    private static final String ARG_TARGET_PHOTO= "target_photo";
    private static final String ARG_TARGET_TYPE = "target_type"; // "channel" | "chat"

    // ── Factory methods ───────────────────────────────────────────────────────

    public static NotificationImagePrivacySheet forChannel(String channelId,
                                                            String channelName,
                                                            String channelPhoto) {
        return build(channelId, channelName, channelPhoto, "channel");
    }

    public static NotificationImagePrivacySheet forChat(String chatId,
                                                         String peerName,
                                                         String peerPhoto) {
        return build(chatId, peerName, peerPhoto, "chat");
    }

    private static NotificationImagePrivacySheet build(String id, String name,
                                                        String photo, String type) {
        NotificationImagePrivacySheet sheet = new NotificationImagePrivacySheet();
        Bundle args = new Bundle();
        args.putString(ARG_TARGET_ID,    id     != null ? id    : "");
        args.putString(ARG_TARGET_NAME,  name   != null ? name  : "");
        args.putString(ARG_TARGET_PHOTO, photo  != null ? photo : "");
        args.putString(ARG_TARGET_TYPE,  type);
        sheet.setArguments(args);
        return sheet;
    }

    // ── onCreateView ─────────────────────────────────────────────────────────

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.sheet_notif_image_privacy, container, false);

        Bundle args      = requireArguments();
        String targetId   = args.getString(ARG_TARGET_ID,    "");
        String targetName = args.getString(ARG_TARGET_NAME,  "");
        String targetPhoto= args.getString(ARG_TARGET_PHOTO, "");
        String targetType = args.getString(ARG_TARGET_TYPE,  "channel");
        boolean isChannel = "channel".equals(targetType);

        // ── Drag handle ───────────────────────────────────────────────────────
        GradientDrawable handle = new GradientDrawable();
        handle.setColor(Ink.c("#E0E0E0"));
        handle.setCornerRadius(dp(3));
        v.findViewById(R.id.drag_handle).setBackground(handle);

        // ── Avatar ────────────────────────────────────────────────────────────
        ImageView imgAvatar = v.findViewById(R.id.img_target_avatar);
        if (!targetPhoto.isEmpty()) {
            Glide.with(requireContext())
                .load(targetPhoto)
                .circleCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(isChannel ? R.drawable.ic_channel : R.drawable.ic_person)
                .into(imgAvatar);
        } else {
            imgAvatar.setImageResource(isChannel ? R.drawable.ic_channel : R.drawable.ic_person);
        }

        // ── Name + subtitle ───────────────────────────────────────────────────
        ((TextView) v.findViewById(R.id.txt_target_name)).setText(targetName);
        ((TextView) v.findViewById(R.id.txt_target_type))
            .setText(isChannel ? "Channel" : "Personal Chat");

        // ── Cards backgrounds ─────────────────────────────────────────────────
        applyCardBg(v.findViewById(R.id.card_blur));
        applyCardBg(v.findViewById(R.id.card_info));

        // ── Info tip ──────────────────────────────────────────────────────────
        GradientDrawable tipBg = new GradientDrawable();
        tipBg.setColor(Ink.c("#E3F2FD"));
        tipBg.setCornerRadius(dp(10));
        v.findViewById(R.id.card_info).setBackground(tipBg);

        // ── Blur switch ───────────────────────────────────────────────────────
        Switch swBlur = v.findViewById(R.id.sw_blur_images);
        boolean current = NotificationPrivacyHelper.isBlurEnabled(requireContext(), targetId);
        swBlur.setChecked(current);

        // Status label (shows current blur state)
        TextView txtStatus = v.findViewById(R.id.txt_blur_status);
        updateStatusLabel(txtStatus, current);

        swBlur.setOnCheckedChangeListener((btn, isChecked) -> {
            NotificationPrivacyHelper.setBlurEnabled(requireContext(), targetId, isChecked);
            updateStatusLabel(txtStatus, isChecked);
            Toast.makeText(requireContext(),
                isChecked
                    ? "🔒 Notification images will be blurred"
                    : "🖼️ Notification images will show normally",
                Toast.LENGTH_SHORT).show();
        });

        // Make the whole card row clickable as well (easier tap target)
        v.findViewById(R.id.row_blur).setOnClickListener(x -> swBlur.toggle());

        // ── Done button ───────────────────────────────────────────────────────
        v.findViewById(R.id.btn_done).setOnClickListener(x -> dismissAllowingStateLoss());

        return v;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void updateStatusLabel(TextView tv, boolean blurEnabled) {
        if (tv == null) return;
        if (blurEnabled) {
            tv.setText("Images blurred 🔒");
            tv.setTextColor(Ink.c("#E53935"));
        } else {
            tv.setText("Images visible normally ✓");
            tv.setTextColor(Ink.c("#388E3C"));
        }
    }

    private void applyCardBg(View view) {
        if (view == null) return;
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.WHITE);
        gd.setCornerRadius(dp(14));
        view.setBackground(gd);
        view.setClipToOutline(true);
    }

    private int dp(int val) {
        return Math.round(val * requireContext().getResources().getDisplayMetrics().density);
    }
}
