package com.silu.minigram;

import android.content.*;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class MsgActionSheet extends BottomSheetDialogFragment {

    // ─── Action callback interface ────────────────────────────────────────
    public interface ActionListener {
        void onCopy(String text);
        void onDeleteForMe(String msgId);
        void onDeleteForAll(String msgId);
        void onEdit(String msgId, String currentText);
        void onPin(String msgId, boolean currentlyPinned);
        void onReply(String msgId, String preview, String senderName);
        void onForward(String text, String imageUrl);
        void onReact(String msgId, String emoji);
        default void onShare(String text, String imageUrl) {}
        default void onSelect(String msgId) {}
        default void onInfo(String msgId) {}
    }

    // ─── Args keys ───────────────────────────────────────────────────────
    private static final String A_MSG_ID  = "msg_id";
    private static final String A_TEXT    = "text";
    private static final String A_IMG_URL = "image_url";
    private static final String A_SENDER  = "sender_uid";
    private static final String A_MY_UID  = "my_uid";
    private static final String A_SNAME   = "sender_name";
    private static final String A_PINNED  = "is_pinned";
    private static final String A_TYPE    = "type";
    private static final String A_CHANNEL = "is_channel_mode";
    private static final String A_FWD_OK  = "forward_allowed";

    /** Original signature — chat context, unchanged behavior. */
    public static MsgActionSheet newInstance(String msgId, String text, String imageUrl,
            String senderUid, String myUid, String senderName, boolean isPinned, String type) {
        return newInstance(msgId, text, imageUrl, senderUid, myUid, senderName, isPinned, type, false);
    }

    /**
     * Channel context — when isChannelMode is true, Reply / Pin / Delete-for-me
     * are only shown to the channel owner (isOwn). Subscribers only ever see
     * Copy and Forward. Chat behavior (isChannelMode=false) is unaffected.
     */
    public static MsgActionSheet newInstance(String msgId, String text, String imageUrl,
            String senderUid, String myUid, String senderName, boolean isPinned, String type,
            boolean isChannelMode) {
        return newInstance(msgId, text, imageUrl, senderUid, myUid, senderName, isPinned, type,
            isChannelMode, true);
    }

    /**
     * Requirement 5 (channel forward security) — forwardAllowed controls
     * whether the Forward row is shown at all. true everywhere except: a
     * channel post, viewed by someone who isn't the owner, in a channel
     * where the owner has restricted forwarding. Chat context always passes
     * true (DMs have no such restriction) via the two overloads above.
     */
    public static MsgActionSheet newInstance(String msgId, String text, String imageUrl,
            String senderUid, String myUid, String senderName, boolean isPinned, String type,
            boolean isChannelMode, boolean forwardAllowed) {
        MsgActionSheet sheet = new MsgActionSheet();
        Bundle b = new Bundle();
        b.putString(A_MSG_ID,  msgId);
        b.putString(A_TEXT,    text);
        b.putString(A_IMG_URL, imageUrl);
        b.putString(A_SENDER,  senderUid);
        b.putString(A_MY_UID,  myUid);
        b.putString(A_SNAME,   senderName);
        b.putBoolean(A_PINNED, isPinned);
        b.putString(A_TYPE,    type);
        b.putBoolean(A_CHANNEL, isChannelMode);
        b.putBoolean(A_FWD_OK,  forwardAllowed);
        sheet.setArguments(b);
        return sheet;
    }

    private ActionListener listener;
    public void setListener(ActionListener l) { this.listener = l; }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.sheet_msg_action, container, false);

        Bundle b      = getArguments();
        String msgId  = b.getString(A_MSG_ID);
        String text   = b.getString(A_TEXT, "");
        String imgUrl = b.getString(A_IMG_URL, "");
        String sender = b.getString(A_SENDER);
        String myUid  = b.getString(A_MY_UID);
        String sName  = b.getString(A_SNAME, "User");
        boolean pinned = b.getBoolean(A_PINNED, false);
        String type   = b.getString(A_TYPE, "text");
        boolean isOwn = myUid != null && myUid.equals(sender);
        boolean hasText = text != null && !text.isEmpty();
        boolean isChannelMode = b.getBoolean(A_CHANNEL, false);
        boolean forwardAllowed = b.getBoolean(A_FWD_OK, true);
        // In channel mode, only the owner (isOwn) gets Reply/Pin/Delete-for-me —
        // subscribers only ever see Copy and Forward.
        boolean showOwnerOnlyRows = !isChannelMode || isOwn;

        // ── Material Icons typeface (agar XML mein font nahi set kiya ho) ─
        Typeface iconFont = null;
        try {
            iconFont = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.material_icons);
        } catch (Exception ignored) {}

        // ── Reaction row (channel subscribers get reactions too — always shown) ──
        final String[] emojis = {"❤️","👍","👎","😂","😮","😢","😡","🔥","👏","🎉","🤔","😍","🙏","💯"};
        int[] emojiIds = {R.id.react_love, R.id.react_like, R.id.react_dislike, R.id.react_laugh,
                          R.id.react_wow, R.id.react_sad, R.id.react_angry, R.id.react_fire,
                          R.id.react_clap, R.id.react_party, R.id.react_think, R.id.react_heart_eyes,
                          R.id.react_pray, R.id.react_100};
        final String fMsgId = msgId;
        for (int i = 0; i < emojiIds.length; i++) {
            final String emoji = emojis[i];
            View rv = view.findViewById(emojiIds[i]);
            if (rv != null) rv.setOnClickListener(v -> {
                if (listener != null) listener.onReact(fMsgId, emoji);
                dismiss();
            });
        }

        // ── Find all rows ────────────────────────────────────────────────
        LinearLayout rowCopy   = view.findViewById(R.id.row_copy);
        LinearLayout rowReply  = view.findViewById(R.id.row_reply);
        LinearLayout rowFwd    = view.findViewById(R.id.row_forward);
        LinearLayout rowPin    = view.findViewById(R.id.row_pin);
        LinearLayout rowEdit   = view.findViewById(R.id.row_edit);
        LinearLayout rowDelMe  = view.findViewById(R.id.row_delete_me);
        LinearLayout rowDelAll = view.findViewById(R.id.row_delete_all);
        LinearLayout rowShare  = view.findViewById(R.id.row_share);
        LinearLayout rowSelect = view.findViewById(R.id.row_select);
        LinearLayout rowInfo   = view.findViewById(R.id.row_info);

        // ── Set icon typeface if not already done via XML ────────────────
        if (iconFont != null) {
            ((TextView) view.findViewById(R.id.icon_copy)).setTypeface(iconFont);
            ((TextView) view.findViewById(R.id.icon_reply)).setTypeface(iconFont);
            ((TextView) view.findViewById(R.id.icon_forward)).setTypeface(iconFont);
            ((TextView) view.findViewById(R.id.icon_pin)).setTypeface(iconFont);
            ((TextView) view.findViewById(R.id.icon_edit)).setTypeface(iconFont);
            ((TextView) view.findViewById(R.id.icon_delete_me)).setTypeface(iconFont);
            ((TextView) view.findViewById(R.id.icon_delete_all)).setTypeface(iconFont);
        }
        rowShare.setVisibility(hasText || !imgUrl.isEmpty() ? View.VISIBLE : View.GONE);
        rowSelect.setVisibility(isChannelMode ? View.GONE : View.VISIBLE);
        rowInfo.setVisibility(isChannelMode ? View.GONE : View.VISIBLE);
        rowShare.setOnClickListener(v -> { if (listener != null) listener.onShare(text, imgUrl); dismiss(); });
        rowSelect.setOnClickListener(v -> { if (listener != null) listener.onSelect(msgId); dismiss(); });
        rowInfo.setOnClickListener(v -> { if (listener != null) listener.onInfo(msgId); dismiss(); });

        // ── Condition-based visibility & dynamic labels ──────────────────
        // Copy – only for text messages
        rowCopy.setVisibility(hasText ? View.VISIBLE : View.GONE);

        // Reply – always visible in chat; owner-only in channel mode
        rowReply.setVisibility(showOwnerOnlyRows ? View.VISIBLE : View.GONE);

        // Forward – always visible in chat; hidden in channel mode when the
        // owner has restricted forwarding and this viewer isn't the owner.
        rowFwd.setVisibility(forwardAllowed ? View.VISIBLE : View.GONE);

        // Pin / Unpin – always visible in chat; owner-only in channel mode
        rowPin.setVisibility(showOwnerOnlyRows ? View.VISIBLE : View.GONE);
        ((TextView) view.findViewById(R.id.label_pin))
                .setText(pinned ? "Unpin" : "Pin");

        // Edit – only own text (non-image)
        if (isOwn && !"sticker".equals(type) && !"file".equals(type)) {
            rowEdit.setVisibility(View.VISIBLE);
        } else {
            rowEdit.setVisibility(View.GONE);
        }

        // Delete for me – always visible in chat; owner-only in channel mode
        rowDelMe.setVisibility(showOwnerOnlyRows ? View.VISIBLE : View.GONE);

        // Delete for everyone – only own messages
        rowDelAll.setVisibility(isOwn ? View.VISIBLE : View.GONE);

        // ── Click listeners ──────────────────────────────────────────────
        rowCopy.setOnClickListener(v -> {
            if (listener != null) listener.onCopy(text);
            dismiss();
        });

        rowReply.setOnClickListener(v -> {
            String preview = "image".equals(type) ? "📷 Photo"
                    : (text.length() > 45 ? text.substring(0, 45) + "…" : text);
            if (listener != null) listener.onReply(msgId, preview, sName);
            dismiss();
        });

        rowFwd.setOnClickListener(v -> {
            if (listener != null) listener.onForward(text, imgUrl);
            dismiss();
        });

        rowPin.setOnClickListener(v -> {
            if (listener != null) listener.onPin(msgId, pinned);
            dismiss();
        });

        rowEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(msgId, text);
            dismiss();
        });

        rowDelMe.setOnClickListener(v -> {
            if (listener != null) listener.onDeleteForMe(msgId);
            dismiss();
        });

        rowDelAll.setOnClickListener(v -> {
            if (listener != null) listener.onDeleteForAll(msgId);
            dismiss();
        });

        return view;
    }
}