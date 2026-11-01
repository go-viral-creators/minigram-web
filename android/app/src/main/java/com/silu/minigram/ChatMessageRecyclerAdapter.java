package com.silu.minigram;

import android.animation.ObjectAnimator;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ChatMessageRecyclerAdapter — RecyclerView + DiffUtil replacement for
 * ChatMessageAdapter (which was a ListView/BaseAdapter).
 *
 * ═══════════════════════════ PHASE 1 OF 4 ═══════════════════════════════════
 * This file is added STANDALONE. It is not referenced by chat.xml or
 * ChatActivity yet — the app keeps running exactly as before on the old
 * ListView + ChatMessageAdapter until Phase 2 (layout swap) and Phase 3
 * (ChatActivity wiring cutover) are done, each as its own separate,
 * reviewable round. This file compiling cleanly on its own is a safe,
 * low-risk checkpoint before touching anything that's currently live.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Every behavior from ChatMessageAdapter is preserved 1:1:
 *   • Sent / received / date-header view types
 *   • Reply-quote bar, edited/pinned labels, reactions chip
 *   • Image messages: optimistic local-URI send (uploading/failed/retry),
 *     remote images with blur-until-tap-to-download, dynamic aspect-ratio
 *     sizing with rounded corners
 *   • Voice messages: shared MediaPlayer, live seek-bar ticker,
 *     download-gated playback, uploading/failed states
 *   • In-chat search highlighting (yellow span + amber focused-row)
 *   • Multi-select highlight, jump-to-message highlight
 *   • Swipe-to-reply gesture with haptic feedback
 *
 * WHAT'S DIFFERENT (intentionally, this is the whole point of the migration):
 *   • notifyDataSetChanged() and refreshWithoutScroll() are KEPT as public
 *     method names (so Phase 3 barely has to touch ChatActivity's ~40 call
 *     sites) but internally both now run a DiffUtil diff and dispatch only
 *     the actual inserts/removes/changes — no more full-rebind, no more
 *     manual "remember scroll position, rebuild, restore position" hack.
 *     DiffUtil naturally preserves scroll position for untouched items.
 *   • ViewHolder pattern: findViewById() runs once per row (in the
 *     ViewHolder constructor), never per bind — this is the main reason
 *     RecyclerView scrolls smoother than ListView under heavy content.
 */
public class ChatMessageRecyclerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // ─── Constants ────────────────────────────────────────────────────────────
    private static final int TYPE_SENT = 0;
    private static final int TYPE_RECV = 1;
    private static final int TYPE_DATE = 2;

    private static final int SWIPE_THRESHOLD_DP = 72;
    private static final int SWIPE_MAX_DP       = 100;
    private static Typeface materialTypeface = null;

    // ─── Interfaces ───────────────────────────────────────────────────────────
    public interface AdapterListener {
        void onReplyClick(String msgId);
        void onImageClick(String msgId, String imgUrl);
        void onItemClick(HashMap<String, Object> msg);
        void onItemLongClick(String msgId);
        void onSwipeReply(HashMap<String, Object> msg);
        void onRetryUpload(String tempId);
    }

    // ─── State ────────────────────────────────────────────────────────────────
    private final Context                            context;
    private final ArrayList<HashMap<String, Object>> messageList;
    private final ArrayList<Object>                  displayList = new ArrayList<>();
    private final String   myUid;
    private final String   peerPhoto;
    // BUG FIX: this was accepted by the constructor but never actually
    // stored anywhere — the field simply didn't exist, so the parameter
    // was silently dropped. Needed now for the initials fallback below.
    private final String   peerName;
    private final RecyclerView recyclerView;
    private final Set<String> selectedMsgs;
    private AdapterListener listener;
    private String highlightedMsgId = null;
    private String searchQuery = null;
    private String searchHighlightMsgId = null;

    private static final Set<String> downloadedImages = new java.util.HashSet<>();
    private static final Set<String> downloadedVoices = new java.util.HashSet<>();

    // ── Voice message playback (unchanged from the ListView version — a
    //    single shared MediaPlayer, guarded by msgId so a recycled row
    //    that used to be playing can never accidentally control playback
    //    for whatever message it now shows) ──────────────────────────────────
    private MediaPlayer voicePlayer      = null;
    private String      playingMsgId     = null;
    private Runnable    voiceSeekRunnable = null;
    private final Handler voiceSeekHandler = new Handler(Looper.getMainLooper());

    // ─── Constructor ─────────────────────────────────────────────────────────
    public ChatMessageRecyclerAdapter(Context context,
                                       ArrayList<HashMap<String, Object>> messageList,
                                       String myUid,
                                       String peerName,
                                       String peerPhoto,
                                       RecyclerView recyclerView,
                                       Set<String> selectedMsgs) {
        this.context      = context;
        this.messageList  = messageList;
        this.myUid        = myUid;
        this.peerPhoto    = peerPhoto;
        this.peerName     = peerName;
        this.recyclerView = recyclerView;
        this.selectedMsgs = selectedMsgs;
        setHasStableIds(true);
        rebuildDisplayList();
    }

    // ─── Public setters ───────────────────────────────────────────────────────
    public void setListener(AdapterListener l) { this.listener = l; }

    public void setHighlightedMsgId(String id) { this.highlightedMsgId = id; }
    public String getHighlightedMsgId()        { return highlightedMsgId; }

    public void setSearchQuery(String q) {
        this.searchQuery = (q == null || q.isEmpty()) ? null : q;
        refreshWithoutScroll();
    }

    public void setHighlightedPosition(int pos) {
        // pos indexes into the raw messageList; translate to a msgId so the
        // adapter (whose displayList also contains date headers) can match it.
        if (pos >= 0 && pos < messageList.size()) {
            this.searchHighlightMsgId = (String) messageList.get(pos).get("msgId");
        } else {
            this.searchHighlightMsgId = null;
        }
        refreshWithoutScroll();
    }

    /** Call from ChatActivity.onDestroy()/onPause() to release the voice player. */
    public void stopVoicePlayback() {
        _stopVoicePlayback();
    }

    // ─── Display list (with date headers) ─────────────────────────────────────
    private void rebuildDisplayList() {
        displayList.clear();
        String lastDateKey = "";
        for (HashMap<String, Object> msg : messageList) {
            Long ts = (Long) msg.get("timestamp");
            if (ts == null) ts = System.currentTimeMillis();
            String dateKey = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date(ts));
            if (!dateKey.equals(lastDateKey)) {
                displayList.add(new DateHeader(formatDateLabel(ts), dateKey));
                lastDateKey = dateKey;
            }
            displayList.add(msg);
        }
    }

    private String formatDateLabel(long ts) {
        Calendar msgCal = Calendar.getInstance(); msgCal.setTimeInMillis(ts);
        Calendar today     = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance(); yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(msgCal, today))     return "Today";
        if (isSameDay(msgCal, yesterday)) return "Yesterday";
        Calendar weekAgo = Calendar.getInstance(); weekAgo.add(Calendar.DAY_OF_YEAR, -6);
        if (msgCal.after(weekAgo))
            return new SimpleDateFormat("EEEE", Locale.getDefault()).format(new Date(ts));
        return new SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(new Date(ts));
    }

    private boolean isSameDay(Calendar c1, Calendar c2) {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
            && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }

    private static class DateHeader {
        final String label;
        final String dateKey; // stable identity for DiffUtil, independent of label text
        DateHeader(String label, String dateKey) { this.label = label; this.dateKey = dateKey; }
    }

    // ─── RecyclerView.Adapter core ─────────────────────────────────────────────
    @Override public int getItemCount() { return displayList.size(); }

    @Override
    public long getItemId(int position) {
        Object item = displayList.get(position);
        if (item instanceof DateHeader) return ("date_" + ((DateHeader) item).dateKey).hashCode();
        @SuppressWarnings("unchecked")
        String msgId = (String) ((HashMap<String, Object>) item).get("msgId");
        return msgId != null ? msgId.hashCode() : position;
    }

    /**
     * Finds this message's CURRENT adapter position, or -1.
     *
     * Needed for the image-download-unlock fix below: downloadedImages is
     * adapter-side state, not part of the message HashMap itself, so it never
     * shows up as a change to DiffUtil's areContentsTheSame() (the HashMap is
     * byte-for-byte identical before and after a tap) — refreshWithoutScroll()
     * silently no-ops for that row. notifyItemChanged(pos) bypasses the diff
     * entirely and forces onBindViewHolder to re-run immediately.
     */
    private int _positionOfMessage(String msgId) {
        if (msgId == null) return -1;
        for (int i = 0; i < displayList.size(); i++) {
            Object item = displayList.get(i);
            if (item instanceof HashMap && msgId.equals(((HashMap<?, ?>) item).get("msgId"))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Public wrapper so ChatActivity.scrollToMessage() can force a specific
     * row to rebind after changing highlightedMsgId — same bug as the
     * download button above: highlightedMsgId is adapter-side state, not
     * part of the message HashMap, so DiffUtil's areContentsTheSame() never
     * sees it change and the old refreshWithoutScroll() call silently never
     * showed the highlight (or never cleared it 2.5s later) at all.
     */
    public void notifyMessageChanged(String msgId) {
        int pos = _positionOfMessage(msgId);
        if (pos != -1) notifyItemChanged(pos);
    }

    @Override
    public int getItemViewType(int position) {
        Object item = displayList.get(position);
        if (item instanceof DateHeader) return TYPE_DATE;
        @SuppressWarnings("unchecked")
        HashMap<String, Object> msg = (HashMap<String, Object>) item;
        String sUid = (String) msg.get("sender_uid");
        return myUid.equals(sUid) ? TYPE_SENT : TYPE_RECV;
    }

    /**
     * Kept with the SAME name as the old ListView adapter's method
     * (BaseAdapter.notifyDataSetChanged()) so ChatActivity's many existing
     * call sites don't need to change in Phase 3 — but internally this now
     * does a real DiffUtil diff instead of a full rebind.
     */
    // NOTE: RecyclerView.Adapter.notifyDataSetChanged() is declared FINAL —
    // it cannot be overridden (unlike ListView's BaseAdapter, where this
    // worked). refreshWithoutScroll() below is the one public method that
    // does the DiffUtil-based smart update; every ChatActivity call site
    // that used to call adapter.notifyDataSetChanged() now calls
    // adapter.refreshWithoutScroll() instead — otherwise those call sites
    // would still compile (they'd just silently use RecyclerView's built-in
    // notifyDataSetChanged(), which redraws everything and doesn't get the
    // DiffUtil scroll-preservation benefit).

    /**
     * In the ListView version this had to manually remember/restore scroll
     * position around a full notifyDataSetChanged(), because ListView jumps
     * to the top otherwise. DiffUtil doesn't have that problem — untouched
     * rows are never touched, so scroll position is naturally preserved.
     * Kept as a separate method (identical behavior now) purely so Phase 3
     * doesn't need to rename any of ChatActivity's ~10 call sites.
     */
    public void refreshWithoutScroll() {
        _diffAndUpdate();
    }

    private void _diffAndUpdate() {
        ArrayList<Object> oldList = new ArrayList<>(displayList);
        rebuildDisplayList();
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new MessageDiffCallback(oldList, displayList));
        result.dispatchUpdatesTo(this);
    }

    private static class MessageDiffCallback extends DiffUtil.Callback {
        private final List<Object> oldList, newList;
        MessageDiffCallback(List<Object> oldList, List<Object> newList) {
            this.oldList = oldList; this.newList = newList;
        }
        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            Object o = oldList.get(oldPos), n = newList.get(newPos);
            if (o instanceof DateHeader && n instanceof DateHeader)
                return ((DateHeader) o).dateKey.equals(((DateHeader) n).dateKey);
            if (o instanceof HashMap && n instanceof HashMap) {
                Object oId = ((HashMap<?, ?>) o).get("msgId");
                Object nId = ((HashMap<?, ?>) n).get("msgId");
                return oId != null && oId.equals(nId);
            }
            return false;
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            Object o = oldList.get(oldPos), n = newList.get(newPos);
            if (o instanceof DateHeader && n instanceof DateHeader)
                return ((DateHeader) o).label.equals(((DateHeader) n).label);
            // HashMap.equals() recursively compares every key/value — exactly
            // "did anything about this message change" for free.
            return o.equals(n);
        }
    }

    // ─── ViewHolders ────────────────────────────────────────────────────────

    static class DateVH extends RecyclerView.ViewHolder {
        final TextView txtDateLabel;
        DateVH(View v) {
            super(v);
            txtDateLabel = v.findViewById(R.id.txt_date_label);
        }
    }

    class MsgVH extends RecyclerView.ViewHolder {
        final int viewType;
        final TextView     txtMsg, txtCaption, txtEdited, txtReactions, txtPinned, txtTick,
                            txtTimeImage, txtTimeMsg, txtReplyQuote,
                            btnDownloadImage, btnVoicePlay, txtVoiceDuration,
                            txtLinkPreviewSite, txtLinkPreviewTitle, txtLinkPreviewDesc;
        final ImageView    imgMsg, imgLinkPreview;
        final ImageView    imgAvatar; // null for TYPE_SENT
        final ProgressBar  imgLoading;
        final LinearLayout replyBar, layoutVoiceMsg, layoutLinkPreview;
        final SeekBar      seekVoice;
        final View         bubbleView;
        final TextView     txtOriginal, txtTranslateTag;

        MsgVH(View v, int viewType) {
            super(v);
            this.viewType   = viewType;
            txtMsg          = v.findViewById(R.id.txt_msg);
            txtOriginal     = v.findViewById(R.id.txt_original);
            txtTranslateTag = v.findViewById(R.id.txt_translate_tag);
            imgMsg          = v.findViewById(R.id.img_msg);
            imgLoading      = v.findViewById(R.id.img_loading);
            btnDownloadImage= v.findViewById(R.id.btn_download_image);
            txtTimeImage    = v.findViewById(R.id.txt_time_image);
            txtTimeMsg      = v.findViewById(R.id.txt_time_msg);
            replyBar        = v.findViewById(R.id.layout_reply_quote);
            txtReplyQuote   = v.findViewById(R.id.txt_reply_quote);
            txtEdited       = v.findViewById(R.id.txt_edited_label);
            txtReactions    = v.findViewById(R.id.txt_reactions);
            txtPinned       = v.findViewById(R.id.txt_pinned_label);
            txtCaption      = v.findViewById(R.id.txt_caption);
            txtTick         = v.findViewById(R.id.txt_tick_status);
            layoutVoiceMsg  = v.findViewById(R.id.layout_voice_msg);
            btnVoicePlay    = v.findViewById(R.id.btn_voice_play);
            seekVoice       = v.findViewById(R.id.seek_voice);
            txtVoiceDuration= v.findViewById(R.id.txt_voice_duration);
            bubbleView      = v.findViewById(viewType == TYPE_SENT ? R.id.linear77 : R.id.linear35);
            imgAvatar       = viewType == TYPE_RECV ? v.findViewById(R.id.img_avatar) : null;
            layoutLinkPreview   = v.findViewById(R.id.layout_link_preview);
            imgLinkPreview      = v.findViewById(R.id.img_link_preview);
            txtLinkPreviewSite  = v.findViewById(R.id.txt_link_preview_site);
            txtLinkPreviewTitle = v.findViewById(R.id.txt_link_preview_title);
            txtLinkPreviewDesc  = v.findViewById(R.id.txt_link_preview_desc);
        }
    }

    // ─── onCreateViewHolder / onBindViewHolder ─────────────────────────────────

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_DATE) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_date_header, parent, false);
            return new DateVH(v);
        }
        int layout = (viewType == TYPE_SENT) ? R.layout.item_msg_sent : R.layout.item_msg_recv;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new MsgVH(v, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof DateVH) {
            DateHeader dh = (DateHeader) displayList.get(position);
            ((DateVH) holder).txtDateLabel.setText(dh.label);
            holder.itemView.setOnClickListener(null);
            holder.itemView.setOnLongClickListener(null);
            return;
        }
        bindMessage((MsgVH) holder, position);
        _applyTranslationUi((MsgVH) holder, position);
    }

    /** Called when the user taps "Translate (approximate)" under a Hinglish message. */
    public interface TranslateListener { void onTranslateHinglish(String msgId); void onNeedPack(String lang); }
    private TranslateListener translateListener;
    public void setTranslateListener(TranslateListener l) { this.translateListener = l; }

    /** Shows the original text + "translated" tag under auto-translated incoming messages (design 03). */
    @SuppressWarnings("unchecked")
    private void _applyTranslationUi(MsgVH h, int pos) {
        if (h.txtOriginal == null) return;
        h.txtOriginal.setVisibility(View.GONE);
        h.txtTranslateTag.setVisibility(View.GONE);
        Object o = displayList.get(pos);
        if (!(o instanceof HashMap)) return;
        final HashMap<String, Object> m = (HashMap<String, Object>) o;
        final String id = (String) m.get("msgId");
        Context c = h.itemView.getContext();
        String orig = (String) m.get("original_text");
        if (orig != null) {
            if (com.silu.minigram.translate.LangPrefs.showOriginal(c)) {
                h.txtOriginal.setText(orig);
                h.txtOriginal.setVisibility(View.VISIBLE);
            }
            String from = com.silu.minigram.translate.TranslationManager.displayName(
                    String.valueOf(m.get("translated_from")).split("-")[0], java.util.Locale.getDefault());
            boolean approx = Boolean.TRUE.equals(m.get("translation_approx"));
            h.txtTranslateTag.setText("\uD83C\uDF10 translated from " + from + (approx ? " (approximate)" : ""));
            h.txtTranslateTag.setOnClickListener(null);
            h.txtTranslateTag.setVisibility(View.VISIBLE);
        } else if (Boolean.TRUE.equals(m.get("hinglish_offer"))) {
            h.txtTranslateTag.setText("\uD83C\uDF10 Hinglish \u00b7 tap to translate (approximate)");
            h.txtTranslateTag.setOnClickListener(v -> { if (translateListener != null) translateListener.onTranslateHinglish(id); });
            h.txtTranslateTag.setVisibility(View.VISIBLE);
        } else if (m.get("translate_pack") != null) {
            final String lang = String.valueOf(m.get("translate_pack"));
            h.txtTranslateTag.setText("\uD83C\uDF10 Download " + com.silu.minigram.translate.TranslationManager.displayName(lang, java.util.Locale.getDefault()) + " pack to translate");
            h.txtTranslateTag.setOnClickListener(v -> { if (translateListener != null) translateListener.onNeedPack(lang); });
            h.txtTranslateTag.setVisibility(View.VISIBLE);
        }
    }

    private void bindMessage(MsgVH h, int pos) {
        int viewType = h.viewType;

        @SuppressWarnings("unchecked")
        HashMap<String, Object> msg = (HashMap<String, Object>) displayList.get(pos);

        final String  msgId       = (String)  msg.get("msgId");
        final String  msgType     = (String)  msg.get("type");
        String  text        = (String)  msg.get("text");
        String  imgUrl      = (String)  msg.get("image_url");
        String  caption     = (String)  msg.get("caption");
        final Long    ts          = (Long)    msg.get("timestamp");
        final Boolean isEdited    = (Boolean) msg.get("is_edited");
        final Boolean isPinned    = (Boolean) msg.get("is_pinned");
        final Boolean isForwarded = (Boolean) msg.get("is_forwarded");
        final Boolean deletedAll  = (Boolean) msg.get("is_deleted_for_all");
        final String  replyId     = (String)  msg.get("reply_to_id");
        final String  replyPrev   = (String)  msg.get("reply_preview");
        final String  replySender = (String)  msg.get("reply_sender");
        final String  status      = (String)  msg.get("status");

        final String  localUri    = (String)  msg.get("local_uri");
        final String  uploadState = (String)  msg.get("upload_state");
        final boolean hasLocalUri = localUri != null && !localUri.isEmpty();

        if (text == null)    text    = "";
        if (imgUrl == null)  imgUrl  = "";
        if (caption == null) caption = "";
        if (msgId == null)   return;

        // ── Highlight / selection background ──────────────────────────────────
        // Selection is checked FIRST on purpose: it's a state the user is
        // actively holding, whereas the search/jump highlights are transient.
        // Previously a selected row that also happened to be the jump target
        // showed the jump colour instead, so it didn't look selected at all.
        if (selectedMsgs.contains(msgId)) {
            // Stronger than the old #402196F3 (25% alpha), which was faint
            // enough on the light chat background to be easy to miss. Uses a
            // drawable rather than a flat colour so it carries a bottom rule
            // — without it, two adjacent selected rows merged into one solid
            // block with no visible boundary between them.
            h.itemView.setBackgroundResource(R.drawable.bg_row_selected);
        } else if (msgId.equals(searchHighlightMsgId)) {
            h.itemView.setBackgroundColor(Color.parseColor("#66FFC107"));
        } else if (msgId.equals(highlightedMsgId)) {
            h.itemView.setBackgroundColor(Color.parseColor("#401A73E8"));
        } else {
            h.itemView.setBackgroundColor(Color.TRANSPARENT);
        }

        h.txtTimeImage.setVisibility(View.GONE);
        h.txtTimeMsg.setVisibility(View.GONE);

        // ── Avatar (received) ─────────────────────────────────────────────────
        if (viewType == TYPE_RECV && h.imgAvatar != null) {
            // BUG FIX: this used to do nothing at all when peerPhoto was
            // empty — no Glide call, nothing — so the row just kept
            // whatever the ImageView happened to be showing before this
            // bind (a recycled view's leftover image, or the layout's
            // default), which is why a generic grey person icon showed up
            // for anyone without a DP instead of anything meaningful.
            // AvatarHelper now handles both cases: real photo if there is
            // one, otherwise deterministic first+last-word initials on a
            // coloured circle — same as the chat header and chat list.
            AvatarHelper.load(context, h.imgAvatar, peerPhoto, peerName);
            h.imgAvatar.setOnClickListener(v2 -> {
                if (listener != null) listener.onImageClick(null, null);
            });
        }

        // ── Read receipt tick ────────────────────────────────────────────────
        if (h.txtTick != null && viewType == TYPE_SENT) {
            _setTickState(h.txtTick, uploadState, hasLocalUri, status);
        }

        // ── Deleted message ───────────────────────────────────────────────────
        if (Boolean.TRUE.equals(deletedAll)) {
            h.imgMsg.setVisibility(View.GONE);
            h.imgMsg.setAlpha(1.0f);
            h.imgMsg.setColorFilter(null);
            if (h.imgLoading != null) h.imgLoading.setVisibility(View.GONE);
            if (h.btnDownloadImage != null) h.btnDownloadImage.setVisibility(View.GONE);
            if (h.txtCaption != null) h.txtCaption.setVisibility(View.GONE);
            if (h.replyBar != null) h.replyBar.setVisibility(View.GONE);
            if (h.layoutVoiceMsg != null) h.layoutVoiceMsg.setVisibility(View.GONE);
            if (h.layoutLinkPreview != null) h.layoutLinkPreview.setVisibility(View.GONE);
            h.txtMsg.setVisibility(View.VISIBLE);
            h.txtMsg.setText("🚫 This message was deleted");
            h.txtMsg.setTextColor(Ink.c("#9E9E9E"));
            h.txtMsg.setTypeface(null, Typeface.ITALIC);
            h.txtMsg.setMovementMethod(null);
            h.txtMsg.setOnTouchListener(null);
            if (ts != null) { h.txtTimeMsg.setText(formatTime(ts)); h.txtTimeMsg.setVisibility(View.VISIBLE); }
            h.itemView.setOnLongClickListener(null);
            h.itemView.setOnClickListener(null);
            clearSwipe(h.bubbleView);
            return;
        }

        // ── Reply bar ─────────────────────────────────────────────────────────
        if (h.replyBar != null) {
            if (replyId != null && !replyId.isEmpty()) {
                h.replyBar.setVisibility(View.VISIBLE);
                if (h.txtReplyQuote != null) {
                    if (replySender != null && replyPrev != null) {
                        SpannableStringBuilder sb = new SpannableStringBuilder();
                        sb.append(replySender).append("\n");
                        int end = sb.length();
                        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        if ("You".equals(replySender))
                            sb.setSpan(new ForegroundColorSpan(Ink.c("#25D366")), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.append(replyPrev);
                        h.txtReplyQuote.setText(sb);
                    } else {
                        h.txtReplyQuote.setText(replyPrev != null ? replyPrev : "Original message");
                    }
                }
                final String rId = replyId;
                h.replyBar.setOnClickListener(v2 -> {
                    if (listener != null) listener.onReplyClick(rId);
                });
            } else {
                h.replyBar.setVisibility(View.GONE);
            }
        }

        final String finalImgUrl = imgUrl;
        final String finalMsgId  = msgId;
        final HashMap<String, Object> finalMsg = msg;

        boolean isImageMsg = "image".equals(msgType) && (hasLocalUri || !imgUrl.isEmpty());
        boolean isVoiceMsg = "voice".equals(msgType);

        if (isImageMsg) {
            bindImageMessage(h, finalMsg, finalMsgId, finalImgUrl, caption, localUri,
                uploadState, hasLocalUri, viewType);
        } else if (isVoiceMsg) {
            bindVoiceMessage(h, finalMsg, finalMsgId, caption, uploadState);
        } else {
            // ── TEXT MESSAGE ─────────────────────────────────────────────────
            h.imgMsg.setVisibility(View.GONE);
            h.imgMsg.setAlpha(1.0f);
            h.imgMsg.setColorFilter(null);
            if (h.imgLoading != null) h.imgLoading.setVisibility(View.GONE);
            if (h.btnDownloadImage != null) h.btnDownloadImage.setVisibility(View.GONE);
            if (h.txtCaption != null) h.txtCaption.setVisibility(View.GONE);
            if (h.layoutVoiceMsg != null) h.layoutVoiceMsg.setVisibility(View.GONE);
            h.txtMsg.setVisibility(View.VISIBLE);
            MsgRenderer.render(context, h.txtMsg, text);
            _applySearchHighlight(h.txtMsg);
            SmartLinkMovementMethod.apply(h.txtMsg);
            if (Boolean.TRUE.equals(isForwarded)) prependForwarded(h.txtMsg);
            h.txtTimeMsg.setVisibility(View.VISIBLE);
            _bindLinkPreview(h, finalMsgId, text, finalMsg);
        }

        // ── Labels ────────────────────────────────────────────────────────────
        if (h.txtEdited != null)
            h.txtEdited.setVisibility(Boolean.TRUE.equals(isEdited) ? View.VISIBLE : View.GONE);
        if (h.txtPinned != null)
            h.txtPinned.setVisibility(Boolean.TRUE.equals(isPinned) ? View.VISIBLE : View.GONE);

        // ── Reactions ─────────────────────────────────────────────────────────
        if (h.txtReactions != null) {
            Object rcObj = finalMsg.get("reaction_counts");
            if (rcObj instanceof HashMap) {
                @SuppressWarnings("unchecked")
                HashMap<String, Integer> counts = (HashMap<String, Integer>) rcObj;
                if (counts.isEmpty()) {
                    h.txtReactions.setVisibility(View.GONE);
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (HashMap.Entry<String, Integer> e : counts.entrySet()) {
                        if (sb.length() > 0) sb.append("  ");
                        sb.append(e.getKey());
                        if (e.getValue() != null && e.getValue() > 1) sb.append(" ").append(e.getValue());
                    }
                    h.txtReactions.setText(sb.toString());
                    h.txtReactions.setVisibility(View.VISIBLE);
                    h.txtReactions.setOnClickListener(v -> {
                        if (listener != null) listener.onItemClick(finalMsg);
                    });
                }
            } else {
                h.txtReactions.setVisibility(View.GONE);
            }
        }

        // ── Timestamps ────────────────────────────────────────────────────────
        if (ts != null) {
            String time = formatTime(ts);
            h.txtTimeImage.setText(time);
            h.txtTimeMsg.setText(time);
        }

        // ── Bubble click / long-click ─────────────────────────────────────────
        if (!hasLocalUri || "sent".equals(uploadState) || uploadState == null) {
            h.itemView.setOnLongClickListener(v2 -> {
                if (listener != null) listener.onItemLongClick(finalMsgId);
                return true;
            });
            h.itemView.setOnClickListener(v2 -> {
                if (listener != null) listener.onItemClick(finalMsg);
            });
        } else {
            h.itemView.setOnLongClickListener(null);
            h.itemView.setOnClickListener(null);
        }

        // ── Slide-to-reply gesture ────────────────────────────────────────────
        if (!hasLocalUri) {
            setupSwipeToReply(h.itemView, h.bubbleView, finalMsg, viewType);
        } else {
            clearSwipe(h.bubbleView);
            h.itemView.setOnTouchListener(null);
        }
    }

    // ─── Image message binding ──────────────────────────────────────────────
    private void bindImageMessage(MsgVH h, HashMap<String, Object> finalMsg,
                                   String finalMsgId, String finalImgUrl, String caption,
                                   String localUri, String uploadState, boolean hasLocalUri,
                                   int viewType) {
        if (h.layoutVoiceMsg != null) h.layoutVoiceMsg.setVisibility(View.GONE);
        if (h.layoutLinkPreview != null) h.layoutLinkPreview.setVisibility(View.GONE);
        boolean hasCaption = !caption.isEmpty();
        h.txtMsg.setVisibility(View.GONE);
        h.imgMsg.setVisibility(View.VISIBLE);

        if (hasLocalUri) {
            if (h.btnDownloadImage != null) h.btnDownloadImage.setVisibility(View.GONE);

            int origW = 100, origH = 100;
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(
                    context.getContentResolver().openInputStream(Uri.parse(localUri)), null, opts);
                origW = opts.outWidth;
                origH = opts.outHeight;
            } catch (Exception ignored) {}
            _adjustImageViewSize(h.imgMsg, origW, origH, h.bubbleView, viewType == TYPE_SENT);

            Glide.with(context)
                .load(Uri.parse(localUri))
                .apply(new RequestOptions().placeholder(R.drawable.img_placeholder))
                .into(h.imgMsg);

            if ("uploading".equals(uploadState)) {
                if (h.imgLoading != null) { h.imgLoading.setVisibility(View.VISIBLE); h.imgLoading.setIndeterminate(true); }
                h.imgMsg.setAlpha(0.75f);
                h.imgMsg.setColorFilter(null);
                h.imgMsg.setOnClickListener(null);
                h.imgMsg.setOnLongClickListener(null);
            } else if ("failed".equals(uploadState)) {
                if (h.imgLoading != null) h.imgLoading.setVisibility(View.GONE);
                h.imgMsg.setAlpha(0.5f);
                h.imgMsg.setColorFilter(Color.parseColor("#80FF4444"), PorterDuff.Mode.SRC_ATOP);
                if (h.txtCaption != null) {
                    h.txtCaption.setVisibility(View.VISIBLE);
                    h.txtCaption.setText("⚠  Upload failed  ·  Tap to retry");
                    h.txtCaption.setTextColor(Ink.c("#FF6B6B"));
                }
                final String fTempId = finalMsgId;
                h.imgMsg.setOnClickListener(v2 -> { if (listener != null) listener.onRetryUpload(fTempId); });
                h.imgMsg.setOnLongClickListener(v2 -> {
                    if (listener != null) listener.onItemLongClick(fTempId);
                    return true;
                });
            }

            if (h.txtCaption == null || h.txtCaption.getVisibility() != View.VISIBLE || "failed".equals(uploadState)) {
                if (!hasCaption && !"failed".equals(uploadState))
                    h.txtTimeImage.setVisibility(View.VISIBLE);
                else
                    h.txtTimeMsg.setVisibility(View.VISIBLE);
            }

        } else {
            h.imgMsg.setAlpha(1.0f);
            h.imgMsg.setColorFilter(null);

            boolean isMine = myUid != null && myUid.equals(finalMsg.get("sender_uid"));
            boolean isDownloaded = isMine || downloadedImages.contains(finalImgUrl)
                || DataStorageSheet.isAutoDownloadImages(context);

            final ImageView _imgMsg = h.imgMsg;
            final ProgressBar _imgProgress = h.imgLoading;
            final TextView _btnDownload = h.btnDownloadImage;
            final View _bubbleView = h.bubbleView;
            final boolean _isSent = viewType == TYPE_SENT;

            if (!isDownloaded) {
                if (_imgProgress != null) _imgProgress.setVisibility(View.GONE);
                if (_btnDownload != null) _btnDownload.setVisibility(View.VISIBLE);
                _adjustImageViewSize(h.imgMsg, 300, 225, h.bubbleView, _isSent);
                Glide.with(context)
                    .load(finalImgUrl)
                    .apply(new RequestOptions()
                        .placeholder(R.drawable.img_placeholder)
                        .error(R.drawable.img_placeholder)
                        .override(24, 24))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(h.imgMsg);

                final String fMsgIdForDownload = finalMsgId;
                View.OnClickListener downloadTap = v2 -> {
                    // Instant feedback right on this view, before the rebind
                    // below even runs — this is the "downloading" state the
                    // button was missing entirely before.
                    if (_btnDownload != null) _btnDownload.setVisibility(View.GONE);
                    if (_imgProgress != null) {
                        _imgProgress.setVisibility(View.VISIBLE);
                        _imgProgress.setIndeterminate(true);
                    }
                    downloadedImages.add(finalImgUrl);
                    // NOT refreshWithoutScroll(): that's a DiffUtil diff, and
                    // downloadedImages isn't part of the message HashMap, so
                    // areContentsTheSame() sees this message as byte-for-byte
                    // unchanged and skips rebinding this row entirely — the
                    // tap silently did nothing until the chat was reopened,
                    // which runs a real bind pass for every row regardless of
                    // any diff. notifyItemChanged() forces that same real
                    // rebind for just this one row, right now.
                    int pos = _positionOfMessage(fMsgIdForDownload);
                    if (pos != -1) notifyItemChanged(pos);
                    else refreshWithoutScroll(); // defensive fallback
                };
                h.imgMsg.setOnClickListener(downloadTap);
                if (_btnDownload != null) _btnDownload.setOnClickListener(downloadTap);
                h.imgMsg.setOnLongClickListener(v2 -> {
                    if (listener != null) listener.onItemLongClick(finalMsgId);
                    return true;
                });
            } else {
                if (_btnDownload != null) _btnDownload.setVisibility(View.GONE);
                if (h.imgLoading != null) h.imgLoading.setVisibility(View.VISIBLE);

                Glide.with(context)
                    .load(finalImgUrl)
                    .apply(new RequestOptions()
                        .placeholder(R.drawable.img_placeholder)
                        .error(R.drawable.img_placeholder))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(new CustomTarget<Drawable>() {
                        @Override
                        public void onResourceReady(@NonNull Drawable resource, Transition<? super Drawable> t) {
                            _adjustImageViewSize(_imgMsg, resource.getIntrinsicWidth(),
                                resource.getIntrinsicHeight(), _bubbleView, _isSent);
                            _imgMsg.setImageDrawable(resource);
                            if (_imgProgress != null) _imgProgress.setVisibility(View.GONE);
                        }
                        @Override public void onLoadCleared(@Nullable Drawable placeholder) {
                            _imgMsg.setImageDrawable(placeholder);
                        }
                        @Override public void onLoadFailed(@Nullable Drawable errorDrawable) {
                            _imgMsg.setImageDrawable(errorDrawable);
                            if (_imgProgress != null) _imgProgress.setVisibility(View.GONE);
                        }
                    });

                h.imgMsg.setOnClickListener(v2 -> {
                    if (listener != null) listener.onImageClick(finalMsgId, finalImgUrl);
                });
                h.imgMsg.setOnLongClickListener(v2 -> {
                    if (listener != null) listener.onItemLongClick(finalMsgId);
                    return true;
                });
            }

            if (h.txtCaption != null) {
                if (hasCaption || Boolean.TRUE.equals(finalMsg.get("is_forwarded"))) {
                    h.txtCaption.setVisibility(View.VISIBLE);
                    h.txtCaption.setTextColor(Ink.c("#FFFFFF"));
                    MsgRenderer.render(context, h.txtCaption, caption);
                    SmartLinkMovementMethod.apply(h.txtCaption);
                    if (Boolean.TRUE.equals(finalMsg.get("is_forwarded"))) prependForwarded(h.txtCaption);
                } else {
                    h.txtCaption.setVisibility(View.GONE);
                }
            }
            if (!hasCaption) h.txtTimeImage.setVisibility(View.VISIBLE);
            else             h.txtTimeMsg.setVisibility(View.VISIBLE);
        }
    }

    // ─── Voice message binding ───────────────────────────────────────────────
    private void bindVoiceMessage(MsgVH h, HashMap<String, Object> finalMsg,
                                   String finalMsgId, String caption, String uploadState) {
        h.imgMsg.setVisibility(View.GONE);
        h.imgMsg.setAlpha(1.0f);
        h.imgMsg.setColorFilter(null);
        if (h.imgLoading != null) h.imgLoading.setVisibility(View.GONE);
        if (h.btnDownloadImage != null) h.btnDownloadImage.setVisibility(View.GONE);
        h.txtMsg.setVisibility(View.GONE);
        if (h.layoutLinkPreview != null) h.layoutLinkPreview.setVisibility(View.GONE);
        if (h.layoutVoiceMsg != null) h.layoutVoiceMsg.setVisibility(View.VISIBLE);

        if (h.txtCaption != null) {
            if ("failed".equals(uploadState)) {
                h.txtCaption.setVisibility(View.VISIBLE);
                h.txtCaption.setText("⚠  Upload failed  ·  Tap to retry");
                h.txtCaption.setTextColor(Ink.c("#FF6B6B"));
            } else if (caption != null && !caption.isEmpty()) {
                h.txtCaption.setVisibility(View.VISIBLE);
                h.txtCaption.setTextColor(Ink.c("#FFFFFF"));
                MsgRenderer.render(context, h.txtCaption, caption);
                SmartLinkMovementMethod.apply(h.txtCaption);
            } else {
                h.txtCaption.setVisibility(View.GONE);
            }
        }

        int durationSec = 0;
        Object durObj = finalMsg.get("duration");
        if (durObj instanceof Number) durationSec = ((Number) durObj).intValue();
        final int finalDuration = durationSec;
        Object urlObj = finalMsg.get("voice_url");
        final String voiceUrl = urlObj != null ? urlObj.toString() : "";

        if ("uploading".equals(uploadState)) {
            if (h.btnVoicePlay != null) { h.btnVoicePlay.setText("schedule"); h.btnVoicePlay.setOnClickListener(null); }
            if (h.seekVoice != null) { h.seekVoice.setProgress(0); h.seekVoice.setEnabled(false); }
            if (h.txtVoiceDuration != null) h.txtVoiceDuration.setText("Uploading…");

        } else if ("failed".equals(uploadState)) {
            if (h.btnVoicePlay != null) {
                h.btnVoicePlay.setText("error_outline");
                final String fTempId = finalMsgId;
                h.btnVoicePlay.setOnClickListener(v2 -> { if (listener != null) listener.onRetryUpload(fTempId); });
            }
            if (h.seekVoice != null) h.seekVoice.setEnabled(false);
            if (h.txtVoiceDuration != null) h.txtVoiceDuration.setText("Failed");

        } else {
            boolean isMineVoice = myUid != null && myUid.equals(finalMsg.get("sender_uid"));
            boolean isDownloaded = isMineVoice || downloadedVoices.contains(voiceUrl)
                || DataStorageSheet.isAutoDownloadVoice(context);

            if (h.seekVoice != null) h.seekVoice.setEnabled(true);

            if (!isDownloaded) {
                if (h.btnVoicePlay != null) {
                    h.btnVoicePlay.setText("file_download");
                    final SeekBar fSeek = h.seekVoice;
                    final TextView fDurationView = h.txtVoiceDuration;
                    final TextView fBtn = h.btnVoicePlay;
                    h.btnVoicePlay.setOnClickListener(v2 -> {
                        downloadedVoices.add(voiceUrl);
                        _toggleVoicePlayback(finalMsgId, voiceUrl, finalDuration, fBtn, fSeek, fDurationView);
                    });
                }
                if (h.seekVoice != null) h.seekVoice.setProgress(0);
                if (h.txtVoiceDuration != null) h.txtVoiceDuration.setText(
                    String.format(Locale.US, "%d:%02d", finalDuration / 60, finalDuration % 60));

            } else {
                boolean isThisPlaying = finalMsgId.equals(playingMsgId) && voicePlayer != null && voicePlayer.isPlaying();
                if (h.btnVoicePlay != null) h.btnVoicePlay.setText(isThisPlaying ? "pause" : "play_arrow");
                int shownSec = isThisPlaying ? voicePlayer.getCurrentPosition() / 1000 : finalDuration;
                if (h.seekVoice != null) {
                    int maxMs = isThisPlaying && voicePlayer.getDuration() > 0 ? voicePlayer.getDuration() : finalDuration * 1000;
                    h.seekVoice.setMax(Math.max(maxMs, 1));
                    h.seekVoice.setProgress(isThisPlaying ? voicePlayer.getCurrentPosition() : 0);
                }
                if (h.txtVoiceDuration != null) {
                    h.txtVoiceDuration.setText(String.format(Locale.US, "%d:%02d", shownSec / 60, shownSec % 60));
                }
                if (h.btnVoicePlay != null) {
                    final SeekBar fSeek = h.seekVoice;
                    final TextView fDurationView = h.txtVoiceDuration;
                    final TextView fBtn = h.btnVoicePlay;
                    h.btnVoicePlay.setOnClickListener(v2 ->
                        _toggleVoicePlayback(finalMsgId, voiceUrl, finalDuration, fBtn, fSeek, fDurationView));
                }
            }
        }
        h.txtTimeMsg.setVisibility(View.VISIBLE);
    }

    // ─── Voice playback engine (unchanged logic from ChatMessageAdapter) ───────
    private void _toggleVoicePlayback(String msgId, String url, int durationSec,
                                       TextView btnPlay, SeekBar seekBar, TextView txtDuration) {
        if (url == null || url.isEmpty()) return;

        if (msgId.equals(playingMsgId) && voicePlayer != null && voicePlayer.isPlaying()) {
            voicePlayer.pause();
            if (btnPlay != null) btnPlay.setText("play_arrow");
            if (voiceSeekRunnable != null) voiceSeekHandler.removeCallbacks(voiceSeekRunnable);
            return;
        }

        _stopVoicePlayback();

        try {
            voicePlayer = new MediaPlayer();
            voicePlayer.setDataSource(url);
            playingMsgId = msgId;
            if (btnPlay != null) { btnPlay.setText("schedule"); btnPlay.setOnClickListener(null); }

            voicePlayer.setOnPreparedListener(mp -> {
                mp.start();
                if (btnPlay != null) {
                    btnPlay.setText("pause");
                    btnPlay.setOnClickListener(v2 -> _toggleVoicePlayback(msgId, url, durationSec, btnPlay, seekBar, txtDuration));
                }
                if (seekBar != null) seekBar.setMax(Math.max(mp.getDuration(), 1));
                voiceSeekRunnable = new Runnable() {
                    @Override public void run() {
                        if (voicePlayer == null || !msgId.equals(playingMsgId)) return; // view recycled to another msg
                        try {
                            int posMs = voicePlayer.getCurrentPosition();
                            if (seekBar != null) {
                                if (android.os.Build.VERSION.SDK_INT >= 24) seekBar.setProgress(posMs, true);
                                else seekBar.setProgress(posMs);
                            }
                            int posSec = posMs / 1000;
                            if (txtDuration != null) txtDuration.setText(
                                String.format(Locale.US, "%d:%02d", posSec / 60, posSec % 60));
                            voiceSeekHandler.postDelayed(this, 80);
                        } catch (Exception ignored) {}
                    }
                };
                voiceSeekHandler.post(voiceSeekRunnable);
            });

            voicePlayer.setOnCompletionListener(mp -> {
                if (btnPlay != null) btnPlay.setText("play_arrow");
                if (seekBar != null) seekBar.setProgress(0);
                if (txtDuration != null) txtDuration.setText(
                    String.format(Locale.US, "%d:%02d", durationSec / 60, durationSec % 60));
                if (voiceSeekRunnable != null) voiceSeekHandler.removeCallbacks(voiceSeekRunnable);
                playingMsgId = null;
            });

            voicePlayer.prepareAsync();
        } catch (Exception e) {
            playingMsgId = null;
            if (btnPlay != null) btnPlay.setText("play_arrow");
        }
    }

    private void _stopVoicePlayback() {
        if (voiceSeekRunnable != null) voiceSeekHandler.removeCallbacks(voiceSeekRunnable);
        if (voicePlayer != null) {
            try { voicePlayer.release(); } catch (Exception ignored) {}
            voicePlayer = null;
        }
        playingMsgId = null;
    }

    private void _setTickState(TextView txtTick, String uploadState, boolean hasLocalUri, String msgStatus) {
        if (txtTick == null) return;
        if (hasLocalUri) {
            if ("uploading".equals(uploadState) || uploadState == null) {
                txtTick.setText("schedule");
                txtTick.setTextColor(Ink.res(R.color.ink_on_sent_2));
            } else if ("failed".equals(uploadState)) {
                txtTick.setText("error_outline");
                txtTick.setTextColor(Ink.c("#FF6B6B"));
            }
        } else {
            if ("read".equals(msgStatus)) {
                txtTick.setText("done_all");
                txtTick.setTextColor(Ink.res(R.color.colorReplyAccentSent));
            } else if ("delivered".equals(msgStatus)) {
                txtTick.setText("done_all");
                txtTick.setTextColor(Ink.res(R.color.ink_on_sent_2));
            } else {
                txtTick.setText("check");
                txtTick.setTextColor(Ink.res(R.color.ink_on_sent_2));
            }
        }
    }

    // ─── Slide to reply (unchanged logic, works identically on RecyclerView
    //     item views — this was already per-view touch handling, not
    //     ListView-specific) ────────────────────────────────────────────────
    private void setupSwipeToReply(View rowView, View bubbleView, HashMap<String, Object> msg, int viewType) {
        if (bubbleView == null) return;

        final float thresholdPx = dp(SWIPE_THRESHOLD_DP);
        final float maxPx       = dp(SWIPE_MAX_DP);

        TextView replyIcon = (TextView) rowView.getTag(R.id.btn_attach_icon);
        if (replyIcon == null) {
            replyIcon = new TextView(context);
            if (materialTypeface == null) {
                try {
                    materialTypeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.material_icons);
                } catch (Exception e) {
                    materialTypeface = Typeface.DEFAULT;
                }
            }
            replyIcon.setTypeface(materialTypeface);
            replyIcon.setText("reply");
            replyIcon.setTextSize(20);
            replyIcon.setTextColor(Ink.c("#1A73E8"));
            replyIcon.setAlpha(0f);
            replyIcon.setVisibility(View.VISIBLE);
            if (rowView instanceof ViewGroup) {
                try {
                    ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(dp(36), dp(36));
                    ((ViewGroup) rowView).addView(replyIcon, lp);
                    rowView.setTag(R.id.btn_attach_icon, replyIcon);
                } catch (Exception ignored) {}
            }
        }

        final TextView finalReplyIcon = replyIcon;
        final float[]  startX    = {0f};
        final boolean[] triggered = {false};

        rowView.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0]    = e.getRawX();
                    triggered[0] = false;
                    bubbleView.setTranslationX(0f);
                    finalReplyIcon.setAlpha(0f);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - startX[0];
                    if (dx < 0) dx = 0;
                    if (dx > maxPx) dx = maxPx;
                    float fraction = Math.min(dx / thresholdPx, 1f);
                    bubbleView.setTranslationX(dx);
                    finalReplyIcon.setAlpha(fraction);
                    finalReplyIcon.setScaleX(0.5f + 0.5f * fraction);
                    finalReplyIcon.setScaleY(0.5f + 0.5f * fraction);
                    if (dx >= thresholdPx && !triggered[0]) {
                        triggered[0] = true;
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (triggered[0] && listener != null) listener.onSwipeReply(msg);
                    ObjectAnimator.ofFloat(bubbleView, "translationX", bubbleView.getTranslationX(), 0f)
                        .setDuration(200).start();
                    ObjectAnimator animAlpha = ObjectAnimator.ofFloat(finalReplyIcon, "alpha", finalReplyIcon.getAlpha(), 0f);
                    animAlpha.setDuration(150);
                    animAlpha.start();
                    triggered[0] = false;
                    break;
            }
            return false;
        });
    }

    private void clearSwipe(View bubbleView) {
        if (bubbleView != null) bubbleView.setTranslationX(0f);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────
    private void prependForwarded(TextView tv) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        ssb.append("↪ Forwarded\n");
        ssb.setSpan(new StyleSpan(Typeface.ITALIC), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new ForegroundColorSpan(Color.GRAY), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new RelativeSizeSpan(0.85f), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (tv.getText() != null) ssb.append(tv.getText());
        tv.setText(ssb);
    }

    private String formatTime(long ts) {
        return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(ts));
    }

    /**
     * Shows a link-preview card under a text message if it contains a URL
     * and a preview was fetchable. Guards against the classic RecyclerView
     * recycling bug: by the time the async fetch completes, this ViewHolder
     * might already be showing a completely different message — the tag
     * check below makes sure the result is thrown away if that happened,
     * rather than a stale preview attaching itself to the wrong row.
     */
    private void _bindLinkPreview(MsgVH h, String msgId, String text, HashMap<String, Object> msg) {
        if (h.layoutLinkPreview == null) return;

        // Already embedded by the sender (composed with a live preview,
        // Telegram-style) — render directly, no fetch needed for anyone.
        String embeddedTitle = (String) msg.get("link_preview_title");
        String embeddedUrl   = (String) msg.get("link_preview_url");
        if (embeddedUrl != null && !embeddedUrl.isEmpty()) {
            h.layoutLinkPreview.setTag(null); // no async fetch in flight, nothing to guard against
            h.layoutLinkPreview.setVisibility(View.VISIBLE);
            String site = (String) msg.get("link_preview_site");
            String desc = (String) msg.get("link_preview_desc");
            String img  = (String) msg.get("link_preview_image");
            h.txtLinkPreviewSite.setText(site != null ? site : "");
            h.txtLinkPreviewTitle.setText(embeddedTitle != null ? embeddedTitle : embeddedUrl);
            h.txtLinkPreviewDesc.setText(desc != null ? desc : "");
            h.txtLinkPreviewDesc.setVisibility(desc != null && !desc.isEmpty() ? View.VISIBLE : View.GONE);
            if (img != null && !img.isEmpty()) {
                h.imgLinkPreview.setVisibility(View.VISIBLE);
                Glide.with(context).load(img)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(h.imgLinkPreview);
            } else {
                h.imgLinkPreview.setVisibility(View.GONE);
            }
            return;
        }

        // Fallback: older message sent before embedded previews existed —
        // fetch live (same as before), still cached by LinkPreviewHelper
        // so it's at least not re-fetched on every scroll.
        String url = LinkPreviewHelper.extractFirstUrl(text);
        if (url == null) {
            h.layoutLinkPreview.setVisibility(View.GONE);
            h.layoutLinkPreview.setTag(null);
            return;
        }
        h.layoutLinkPreview.setVisibility(View.GONE);
        h.layoutLinkPreview.setTag(msgId);
        LinkPreviewHelper.fetch(url, preview -> {
            if (!msgId.equals(h.layoutLinkPreview.getTag())) return;
            if (preview == null) return;
            h.layoutLinkPreview.setVisibility(View.VISIBLE);
            h.txtLinkPreviewSite.setText(preview.siteName != null ? preview.siteName : "");
            h.txtLinkPreviewTitle.setText(preview.title != null ? preview.title : "");
            h.txtLinkPreviewDesc.setText(preview.description != null ? preview.description : "");
            h.txtLinkPreviewDesc.setVisibility(
                preview.description != null && !preview.description.isEmpty() ? View.VISIBLE : View.GONE);
            if (preview.imageUrl != null && !preview.imageUrl.isEmpty()) {
                // CRASH FIX: link preview fetches are network calls that can
                // land long after the user has left the chat. Glide throws
                // outright if handed a destroyed Activity, so bail out here.
                if (context instanceof android.app.Activity) {
                    android.app.Activity act = (android.app.Activity) context;
                    if (act.isFinishing() || act.isDestroyed()) return;
                }
                h.imgLinkPreview.setVisibility(View.VISIBLE);
                Glide.with(context).load(preview.imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(h.imgLinkPreview);
            } else {
                h.imgLinkPreview.setVisibility(View.GONE);
            }
        });
    }

    private void _applySearchHighlight(TextView tv) {
        if (searchQuery == null || tv.getText() == null) return;
        String hay = tv.getText().toString();
        String needle = searchQuery.toLowerCase(Locale.getDefault());
        String lowerHay = hay.toLowerCase(Locale.getDefault());
        int idx = lowerHay.indexOf(needle);
        if (idx < 0) return;
        SpannableStringBuilder sb = new SpannableStringBuilder(tv.getText());
        while (idx >= 0) {
            sb.setSpan(new BackgroundColorSpan(Ink.c("#FFF176")),
                idx, idx + needle.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Ink.c("#000000")),
                idx, idx + needle.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            idx = lowerHay.indexOf(needle, idx + needle.length());
        }
        tv.setText(sb);
    }

    private int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density);
    }

    // ── Dynamic image sizing (unchanged formula from ChatMessageAdapter) ──────
    private static final int IMG_CORNER_RADIUS_DP = 12;

    private int _getListContentWidth() {
        int w = recyclerView.getWidth();
        if (w <= 0) return context.getResources().getDisplayMetrics().widthPixels;
        return w - recyclerView.getPaddingLeft() - recyclerView.getPaddingRight();
    }

    private int[] _adjustImageViewSize(ImageView iv, int imgW, int imgH, View bubbleContainer, boolean isSent) {
        if (imgW <= 0 || imgH <= 0) { imgW = 100; imgH = 100; }

        int screenWidth   = _getListContentWidth();
        int paddingLeft   = bubbleContainer.getPaddingLeft();
        int paddingRight  = bubbleContainer.getPaddingRight();

        int chrome = isSent ? dp(64) + dp(12)
                            : dp(8) + dp(64) + dp(34) + dp(6);
        int maxWidth = screenWidth - chrome - dp(5) - dp(5) - paddingLeft - paddingRight;

        int minHeight = dp(120);
        int maxHeight = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.55f);

        float ratio = (float) imgW / (float) imgH;
        int targetWidth  = maxWidth;
        int targetHeight = Math.round(targetWidth / ratio);

        if (targetHeight < minHeight) {
            int widthAtMinHeight = Math.round(minHeight * ratio);
            if (widthAtMinHeight <= maxWidth) {
                targetWidth = widthAtMinHeight; targetHeight = minHeight;
            } else {
                targetWidth = maxWidth; targetHeight = Math.round(targetWidth / ratio);
            }
        } else if (targetHeight > maxHeight) {
            int widthAtMaxHeight = Math.round(maxHeight * ratio);
            if (widthAtMaxHeight <= maxWidth) {
                targetWidth = widthAtMaxHeight; targetHeight = maxHeight;
            } else {
                targetWidth = maxWidth; targetHeight = Math.round(targetWidth / ratio);
            }
        }

        if (targetWidth > maxWidth) {
            targetWidth = maxWidth;
            targetHeight = Math.round(targetWidth / ratio);
        }

        ViewGroup.LayoutParams lp = iv.getLayoutParams();
        if (lp == null) lp = new ViewGroup.LayoutParams(targetWidth, targetHeight);
        lp.width  = targetWidth;
        lp.height = targetHeight;
        iv.setLayoutParams(lp);

        _applyUniformRoundedCorners(iv, targetWidth, targetHeight);
        return new int[]{targetWidth, targetHeight};
    }

    private void _applyUniformRoundedCorners(ImageView iv, int widthPx, int heightPx) {
        final int radiusPx = dp(IMG_CORNER_RADIUS_DP);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, widthPx, heightPx, radiusPx);
            }
        });
        iv.setClipToOutline(true);
    }
}
