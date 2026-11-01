package com.silu.minigram;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.gms.ads.nativead.AdChoicesView;
import com.google.android.gms.ads.nativead.MediaView;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * ChannelPostRecyclerAdapter — RecyclerView + DiffUtil replacement for
 * ChannelPostAdapter (which was a ListView/BaseAdapter).
 *
 * ═══════════════════════════ PHASE 1 OF 4 ═══════════════════════════════════
 * Standalone file — not referenced by channel.xml or ChannelActivity yet.
 * The app keeps running exactly as before on the old ListView until Phase 2
 * (layout swap) and Phase 3 (ChannelActivity wiring) are done as their own
 * separate, reviewable rounds — same approach as the ChatActivity migration.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Ported 1:1, matching ChannelPostAdapter's CURRENT actual behavior:
 *   • Sent / received bubble styling + native-ad interleaving (channels only)
 *   • Reply-quote bar, edited/pinned labels, like button + count
 *   • Reply/comment button + count, view count (Issue 4)
 *   • Image posts: optimistic local-URI upload (uploading/failed/retry),
 *     thumbnail-first remote loading, dynamic aspect-ratio bubble sizing
 *   • Deleted-post state
 *
 * NOT wired up here — because they're not wired up in the CURRENT adapter
 * either, despite the item layouts having the views for them already:
 *   • txt_reactions, layout_voice_msg / voice playback, btn_download_image
 *   This isn't scope creep to fix here; flagging it as a separate, optional
 *   follow-up so nothing new gets silently introduced during a migration.
 *
 * WHAT'S DIFFERENT (the point of the migration):
 *   • notifyDataSetChanged() / refreshWithoutScroll() keep their names (so
 *     ChannelActivity's call sites barely change in Phase 3) but now run a
 *     real DiffUtil diff instead of a full rebind — no more manual "remember
 *     scroll position, disable transcript mode, restore" dance.
 *   • Native ad slots used to be computed on-the-fly via position math on
 *     every single access (getCount/getItem/getView all called
 *     _resolvePosition independently). They're now materialized once, into
 *     the same displayList DiffUtil already diffs — one source of truth
 *     instead of three call sites doing the same math.
 *   • ViewHolder pattern: findViewById() once per row, not per bind.
 */
public class ChannelPostRecyclerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SENT      = 0;
    private static final int TYPE_RECEIVED  = 1;
    private static final int TYPE_NATIVE_AD = 2;

    public interface PostListener {
        void onImageClick(String postId, String imageUrl);
        void onItemClick(String postId);
        void onItemLongClick(String postId);
        void onReplyClick(String postId);
        void onLikeToggle(String postId, boolean currentlyLiked);
        void onRetryUpload(String tempId);
    }

    // ─── State ────────────────────────────────────────────────────────────────
    private final Context                            context;
    private final ArrayList<HashMap<String, Object>> postList; // raw source of truth
    private final ArrayList<Object>                  displayList = new ArrayList<>(); // posts + AdSlotMarker, materialized
    private final RecyclerView                       recyclerView;
    private final String                             myUid;
    private final String                             channelId;
    private       PostListener                       listener;
    private       String                             channelOwnerUidOverride = null;

    private NativeAdManager nativeAdManager;
    public void setNativeAdManager(NativeAdManager manager) { this.nativeAdManager = manager; }
    public void setChannelOwnerUid(String ownerUid) { this.channelOwnerUidOverride = ownerUid; }

    private static class AdSlotMarker {
        final int slotId;
        AdSlotMarker(int slotId) { this.slotId = slotId; }
    }

    // ─── Constructor ─────────────────────────────────────────────────────────
    public ChannelPostRecyclerAdapter(Context context,
                                       ArrayList<HashMap<String, Object>> postList,
                                       RecyclerView recyclerView,
                                       String myUid,
                                       String channelId) {
        this.context      = context;
        this.postList     = postList;
        this.recyclerView = recyclerView;
        this.myUid        = myUid;
        this.channelId    = channelId;
        setHasStableIds(true);
        rebuildDisplayList();
    }

    public void setListener(PostListener l) { this.listener = l; }

    /** No-op — see class doc. Kept so ChannelActivity's onPause() call site still compiles. */
    public void stopVoicePlayback() {}

    // ─── Display list (posts + native-ad slots, materialized) ─────────────────
    private int _adInterval() {
        return (nativeAdManager != null && !nativeAdManager.isPro())
            ? AdConfig.NATIVE_AD_POST_INTERVAL : 0;
    }

    private void rebuildDisplayList() {
        displayList.clear();
        int interval = _adInterval();
        int sinceLastAd = 0;
        int nextSlotId = 0;
        for (HashMap<String, Object> post : postList) {
            displayList.add(post);
            sinceLastAd++;
            if (interval > 0 && sinceLastAd >= interval) {
                NativeAd ad = nativeAdManager.getAdForSlot(nextSlotId);
                if (ad != null) {
                    displayList.add(new AdSlotMarker(nextSlotId));
                    nextSlotId++;
                    sinceLastAd = 0;
                }
                // If the ad for this slot isn't ready yet, we simply don't
                // insert a slot here — keeps ad positions gap-free instead
                // of showing an empty placeholder row.
            }
        }
    }

    // ─── RecyclerView.Adapter core ─────────────────────────────────────────────
    @Override public int getItemCount() { return displayList.size(); }

    @Override
    public long getItemId(int position) {
        Object item = displayList.get(position);
        if (item instanceof AdSlotMarker) return ("ad_" + ((AdSlotMarker) item).slotId).hashCode();
        String postId = (String) ((HashMap<?, ?>) item).get("postId");
        return postId != null ? postId.hashCode() : position;
    }

    @Override
    public int getItemViewType(int position) {
        Object item = displayList.get(position);
        if (item instanceof AdSlotMarker) return TYPE_NATIVE_AD;
        @SuppressWarnings("unchecked")
        HashMap<String, Object> post = (HashMap<String, Object>) item;
        return _isSentByMe(post) ? TYPE_SENT : TYPE_RECEIVED;
    }

    private boolean _isSentByMe(HashMap<String, Object> post) {
        String senderUid = (String) post.get("sender_uid");
        if (senderUid == null || senderUid.isEmpty()) senderUid = channelOwnerUidOverride;
        if (senderUid == null || myUid == null) return true;
        return senderUid.equals(myUid);
    }

    // NOTE: RecyclerView.Adapter.notifyDataSetChanged() is FINAL — can't be
    // overridden. refreshWithoutScroll() below is the one method that does
    // the DiffUtil-based smart update. When Phase 3 wires this adapter into
    // ChannelActivity, use adapter.refreshWithoutScroll() everywhere (not
    // notifyDataSetChanged() — that would silently fall back to the
    // built-in full-redraw behavior instead of the DiffUtil path).

    /** DiffUtil naturally preserves scroll position — the old manual hack is gone. */
    public void refreshWithoutScroll() {
        _diffAndUpdate();
    }

    private void _diffAndUpdate() {
        ArrayList<Object> oldList = new ArrayList<>(displayList);
        rebuildDisplayList();
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new PostDiffCallback(oldList, displayList));
        result.dispatchUpdatesTo(this);
    }

    private static class PostDiffCallback extends DiffUtil.Callback {
        private final List<Object> oldList, newList;
        PostDiffCallback(List<Object> oldList, List<Object> newList) {
            this.oldList = oldList; this.newList = newList;
        }
        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            Object o = oldList.get(oldPos), n = newList.get(newPos);
            if (o instanceof AdSlotMarker && n instanceof AdSlotMarker)
                return ((AdSlotMarker) o).slotId == ((AdSlotMarker) n).slotId;
            if (o instanceof HashMap && n instanceof HashMap) {
                Object oId = ((HashMap<?, ?>) o).get("postId");
                Object nId = ((HashMap<?, ?>) n).get("postId");
                return oId != null && oId.equals(nId);
            }
            return false;
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            Object o = oldList.get(oldPos), n = newList.get(newPos);
            if (o instanceof AdSlotMarker) return true; // identity already confirmed same slotId
            return o.equals(n); // HashMap.equals() recursively compares every field
        }
    }

    // ─── ViewHolders ────────────────────────────────────────────────────────

    class PostVH extends RecyclerView.ViewHolder {
        final LinearLayout bubble, replyBar, layoutLinkPreview;
        final TextView     txtReply, txtTimeImg, txtCaption, txtMsg, txtEdited, txtPinned,
                            txtTimeMsg, btnLike, txtLikeCount, btnReply, txtReplyCnt, txtViewCount,
                            txtReactions, txtLinkPreviewSite, txtLinkPreviewTitle, txtLinkPreviewDesc;
        final ImageView    imgMsg, imgLinkPreview;
        final ProgressBar  imgLoading;

        PostVH(View v) {
            super(v);
            bubble       = v.findViewById(R.id.bubble_container);
            replyBar     = v.findViewById(R.id.layout_reply_quote);
            txtReply     = v.findViewById(R.id.txt_reply_quote);
            imgMsg       = v.findViewById(R.id.img_msg);
            imgLoading   = v.findViewById(R.id.img_loading);
            txtTimeImg   = v.findViewById(R.id.txt_time_image);
            txtCaption   = v.findViewById(R.id.txt_caption);
            txtMsg       = v.findViewById(R.id.txt_msg);
            txtEdited    = v.findViewById(R.id.txt_edited_label);
            txtPinned    = v.findViewById(R.id.txt_pinned_label);
            txtTimeMsg   = v.findViewById(R.id.txt_time_msg);
            btnLike      = v.findViewById(R.id.btn_like);
            txtLikeCount = v.findViewById(R.id.txt_like_count);
            btnReply     = v.findViewById(R.id.btn_reply);
            txtReplyCnt  = v.findViewById(R.id.txt_reply_count);
            txtViewCount = v.findViewById(R.id.txt_view_count);
            txtReactions = v.findViewById(R.id.txt_reactions);
            layoutLinkPreview   = v.findViewById(R.id.layout_link_preview);
            imgLinkPreview      = v.findViewById(R.id.img_link_preview);
            txtLinkPreviewSite  = v.findViewById(R.id.txt_link_preview_site);
            txtLinkPreviewTitle = v.findViewById(R.id.txt_link_preview_title);
            txtLinkPreviewDesc  = v.findViewById(R.id.txt_link_preview_desc);
        }
    }

    static class NativeAdVH extends RecyclerView.ViewHolder {
        final ImageView appIcon;
        final TextView headline, advertiser, body, cta;
        final MediaView media;
        final AdChoicesView adChoicesContainer;

        NativeAdVH(View v) {
            super(v);
            appIcon    = v.findViewById(R.id.ad_app_icon);
            headline   = v.findViewById(R.id.ad_headline);
            advertiser = v.findViewById(R.id.ad_advertiser);
            media      = v.findViewById(R.id.ad_media);
            body       = v.findViewById(R.id.ad_body);
            cta        = v.findViewById(R.id.ad_cta);
            adChoicesContainer = v.findViewById(R.id.ad_choices_container);
        }
    }

    // ─── onCreateViewHolder / onBindViewHolder ─────────────────────────────────

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_NATIVE_AD) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_native_ad, parent, false);
            return new NativeAdVH(v);
        }
        int layoutRes = (viewType == TYPE_SENT) ? R.layout.item_channel_post_sent : R.layout.item_channel_post_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new PostVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = displayList.get(position);
        if (item instanceof AdSlotMarker) {
            bindNativeAd((NativeAdVH) holder, ((AdSlotMarker) item).slotId);
            return;
        }
        @SuppressWarnings("unchecked")
        HashMap<String, Object> post = (HashMap<String, Object>) item;
        bindPost((PostVH) holder, post);
    }

    private void bindPost(PostVH h, HashMap<String, Object> post) {
        final String  postId      = (String)  post.get("postId");
        final String  postType    = (String)  post.get("type");
        String  text        = (String)  post.get("text");
        String  imgUrl      = (String)  post.get("image_url");
        String  caption     = (String)  post.get("caption");
        final Long    ts          = (Long)    post.get("timestamp");
        final Boolean isEdited    = (Boolean) post.get("is_edited");
        final Boolean isPinned    = (Boolean) post.get("is_pinned");
        final Boolean isForwarded = (Boolean) post.get("is_forwarded");
        final Boolean deletedAll  = (Boolean) post.get("is_deleted_for_all");
        final String  replyId     = (String)  post.get("reply_to_id");
        final String  replyPrev   = (String)  post.get("reply_preview");
        final String  replySender = (String)  post.get("reply_sender");
        final Long    replyCount  = (Long)    post.get("reply_count");

        final Long viewCount = post.get("view_count") instanceof Long
            ? (Long) post.get("view_count") : 0L;

        @SuppressWarnings("unchecked")
        final HashMap<String, Object> likes =
            post.get("likes") instanceof HashMap
                ? (HashMap<String, Object>) post.get("likes")
                : new HashMap<>();

        final String  localUri    = (String) post.get("local_uri");
        final String  uploadState = (String) post.get("upload_state");
        final boolean hasLocalUri = localUri != null && !localUri.isEmpty();

        if (text    == null) text    = "";
        if (imgUrl  == null) imgUrl  = "";
        if (caption == null) caption = "";
        if (postId  == null) return;

        final String finalPostId = postId;
        final String finalImgUrl = imgUrl;

        // ── Deleted post ─────────────────────────────────────────────────────
        if (Boolean.TRUE.equals(deletedAll)) {
            _hideMedia(h.imgMsg, h.imgLoading, h.txtTimeImg, h.txtCaption);
            if (h.replyBar != null) h.replyBar.setVisibility(View.GONE);
            if (h.btnLike != null) h.btnLike.setVisibility(View.GONE);
            if (h.txtLikeCount != null) h.txtLikeCount.setVisibility(View.GONE);
            if (h.btnReply != null) h.btnReply.setVisibility(View.GONE);
            if (h.txtReplyCnt != null) h.txtReplyCnt.setVisibility(View.GONE);
            if (h.txtViewCount != null) h.txtViewCount.setVisibility(View.GONE);
            if (h.txtReactions != null) h.txtReactions.setVisibility(View.GONE);
            if (h.layoutLinkPreview != null) h.layoutLinkPreview.setVisibility(View.GONE);
            if (h.txtMsg != null) {
                h.txtMsg.setVisibility(View.VISIBLE);
                h.txtMsg.setText("🚫 This post was deleted");
                h.txtMsg.setTextColor(Ink.c("#9E9E9E"));
                h.txtMsg.setTypeface(null, Typeface.ITALIC);
            }
            if (h.txtTimeMsg != null && ts != null) h.txtTimeMsg.setText(formatTime(ts));
            h.itemView.setOnClickListener(null);
            h.itemView.setOnLongClickListener(null);
            return;
        }

        // ── Timestamp ────────────────────────────────────────────────────────
        if (h.txtTimeMsg != null && ts != null) h.txtTimeMsg.setText(formatTime(ts));
        if (h.txtTimeImg != null && ts != null) h.txtTimeImg.setText(formatTime(ts));

        // ── Reply quote bar ───────────────────────────────────────────────────
        if (h.replyBar != null) {
            if (replyId != null && !replyId.isEmpty()) {
                h.replyBar.setVisibility(View.VISIBLE);
                if (h.txtReply != null) {
                    SpannableStringBuilder sb = new SpannableStringBuilder();
                    String label = replySender != null ? replySender : "Channel";
                    sb.append(label).append("\n");
                    int end = sb.length();
                    sb.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(Ink.c("#1B5E20")), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.append(replyPrev != null ? replyPrev : "");
                    h.txtReply.setMaxLines(2);
                    h.txtReply.setText(sb);
                }
            } else {
                h.replyBar.setVisibility(View.GONE);
            }
        }

        // ── IMAGE vs TEXT ─────────────────────────────────────────────────────
        boolean isImagePost = "image".equals(postType) && (hasLocalUri || !imgUrl.isEmpty());

        if (h.bubble != null) {
            ViewGroup.LayoutParams lp = h.bubble.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            h.bubble.setLayoutParams(lp);
        }

        if (isImagePost) {
            bindImagePost(h, finalPostId, finalImgUrl, caption, localUri, uploadState,
                hasLocalUri, isForwarded);
            if (h.layoutLinkPreview != null) h.layoutLinkPreview.setVisibility(View.GONE);
        } else {
            _hideMedia(h.imgMsg, h.imgLoading, h.txtTimeImg, h.txtCaption);
            if (h.txtMsg != null) {
                h.txtMsg.setVisibility(View.VISIBLE);
                MsgRenderer.render(context, h.txtMsg, text);
                SmartLinkMovementMethod.apply(h.txtMsg);
                if (Boolean.TRUE.equals(isForwarded)) _prependForwarded(h.txtMsg);
            }
            _bindLinkPreview(h, finalPostId, text, post);
        }

        // ── Labels ────────────────────────────────────────────────────────────
        if (h.txtEdited != null)
            h.txtEdited.setVisibility(Boolean.TRUE.equals(isEdited) ? View.VISIBLE : View.GONE);
        if (h.txtPinned != null)
            h.txtPinned.setVisibility(Boolean.TRUE.equals(isPinned) ? View.VISIBLE : View.GONE);

        // ── Like button ───────────────────────────────────────────────────────
        boolean liked     = likes.containsKey(myUid);
        int     likeCount = likes.size();
        if (h.btnLike != null) {
            h.btnLike.setVisibility(View.VISIBLE);
            h.btnLike.setText(liked ? "favorite" : "favorite_border");
            h.btnLike.setTextColor(liked ? Ink.c("#E53935") : Ink.c("#9E9E9E"));
            final boolean _liked = liked;
            h.btnLike.setOnClickListener(v -> {
                if (listener != null && !finalPostId.startsWith("temp_"))
                    listener.onLikeToggle(finalPostId, _liked);
            });
        }
        if (h.txtLikeCount != null) {
            h.txtLikeCount.setVisibility(View.VISIBLE);
            h.txtLikeCount.setText(_formatCount(likeCount));
        }

        // ── Reply / Comments button ───────────────────────────────────────────
        if (h.btnReply != null) {
            h.btnReply.setVisibility(View.VISIBLE);
            h.btnReply.setOnClickListener(v -> {
                if (listener != null && !finalPostId.startsWith("temp_"))
                    listener.onReplyClick(finalPostId);
            });
        }
        if (h.txtReplyCnt != null) {
            h.txtReplyCnt.setVisibility(View.VISIBLE);
            long rc = replyCount != null ? replyCount : 0L;
            h.txtReplyCnt.setText(_formatCount(rc));
        }

        // ── View count (Issue 4) ─────────────────────────────────────────────
        if (h.txtViewCount != null) {
            if (postId.startsWith("temp_")) {
                h.txtViewCount.setVisibility(View.GONE);
            } else {
                h.txtViewCount.setVisibility(View.VISIBLE);
                h.txtViewCount.setText(_formatCount(viewCount != null ? viewCount : 0L));
            }
        }

        // ── Reactions ─────────────────────────────────────────────────────────
        if (h.txtReactions != null) {
            Object rcObj = post.get("reaction_counts");
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
                        if (listener != null && !finalPostId.startsWith("temp_"))
                            listener.onItemClick(finalPostId);
                    });
                }
            } else {
                h.txtReactions.setVisibility(View.GONE);
            }
        }

        // ── Click + Long-press → action sheet ────────────────────────────────
        boolean isTemp = postId.startsWith("temp_");
        if (!isTemp) {
            View target = h.bubble != null ? h.bubble : h.itemView;
            target.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(finalPostId);
            });
            target.setOnLongClickListener(v -> {
                if (listener != null) listener.onItemLongClick(finalPostId);
                return true;
            });
        } else {
            if (h.bubble != null) { h.bubble.setOnClickListener(null); h.bubble.setOnLongClickListener(null); }
            h.itemView.setOnClickListener(null);
            h.itemView.setOnLongClickListener(null);
        }
    }

    private void bindImagePost(PostVH h, String finalPostId, String finalImgUrl, String caption,
                                String localUri, String uploadState, boolean hasLocalUri,
                                Boolean isForwarded) {
        if (h.txtMsg != null) h.txtMsg.setVisibility(View.GONE);
        if (h.imgMsg == null) return;

        h.imgMsg.setVisibility(View.VISIBLE);
        if (h.txtTimeImg != null) h.txtTimeImg.setVisibility(View.VISIBLE);
        h.imgMsg.setScaleType(ImageView.ScaleType.FIT_XY);

        if (hasLocalUri) {
            int origW = 100, origH = 100;
            try {
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeStream(
                    context.getContentResolver().openInputStream(Uri.parse(localUri)), null, options);
                origW = options.outWidth;
                origH = options.outHeight;
            } catch (Exception ignored) {}
            int[] localSize = _adjustImageViewSize(h.imgMsg, origW, origH, h.bubble);

            Glide.with(context).clear(h.imgMsg);
            h.imgMsg.setTag(localUri);
            final ImageView _localIv  = h.imgMsg;
            final String    _localUri = localUri;

            Glide.with(context)
                .load(Uri.parse(localUri))
                .apply(new RequestOptions()
                    .placeholder(R.drawable.img_placeholder)
                    .override(localSize[0], localSize[1]))
                .into(new CustomTarget<Drawable>() {
                    @Override public void onResourceReady(@NonNull Drawable r, Transition<? super Drawable> t) {
                        if (_localUri.equals(_localIv.getTag())) {
                            _localIv.setScaleType(ImageView.ScaleType.FIT_XY);
                            _localIv.setImageDrawable(r);
                        }
                    }
                    @Override public void onLoadCleared(Drawable p) { _localIv.setImageDrawable(p); }
                });

            if ("uploading".equals(uploadState)) {
                if (h.imgLoading != null) { h.imgLoading.setVisibility(View.VISIBLE); h.imgLoading.setIndeterminate(true); }
                h.imgMsg.setAlpha(0.75f);
                h.imgMsg.setColorFilter(null);
                h.imgMsg.setOnClickListener(null);
            } else if ("failed".equals(uploadState)) {
                if (h.imgLoading != null) h.imgLoading.setVisibility(View.GONE);
                h.imgMsg.setAlpha(0.55f);
                h.imgMsg.setColorFilter(Color.parseColor("#80FF4444"), android.graphics.PorterDuff.Mode.SRC_ATOP);
                if (h.txtCaption != null) {
                    h.txtCaption.setVisibility(View.VISIBLE);
                    h.txtCaption.setText("⚠  Upload failed  ·  Tap to retry");
                    h.txtCaption.setTextColor(Ink.c("#FF6B6B"));
                }
                h.imgMsg.setOnClickListener(v -> { if (listener != null) listener.onRetryUpload(finalPostId); });
            }

        } else {
            h.imgMsg.setAlpha(1f);
            h.imgMsg.setColorFilter(null);
            if (h.imgLoading != null) h.imgLoading.setVisibility(View.VISIBLE);

            Glide.with(context).clear(h.imgMsg);
            h.imgMsg.setImageDrawable(null);
            h.imgMsg.setTag(finalImgUrl);

            final ProgressBar _pr = h.imgLoading;
            final ImageView   _iv = h.imgMsg;
            final LinearLayout _bubble = h.bubble;

            Glide.with(context)
                .load(finalImgUrl)
                .apply(new RequestOptions()
                    .placeholder(R.drawable.img_placeholder)
                    .error(R.drawable.img_placeholder))
                .thumbnail(0.2f)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(new CustomTarget<Drawable>() {
                    @Override
                    public void onResourceReady(@NonNull Drawable r, Transition<? super Drawable> t) {
                        if (finalImgUrl.equals(_iv.getTag())) {
                            _adjustImageViewSize(_iv, r.getIntrinsicWidth(), r.getIntrinsicHeight(), _bubble);
                            _iv.setScaleType(ImageView.ScaleType.FIT_XY);
                            _iv.setImageDrawable(r);
                        }
                        if (_pr != null) _pr.setVisibility(View.GONE);
                    }
                    @Override public void onLoadCleared(Drawable p) { _iv.setImageDrawable(p); }
                    @Override public void onLoadFailed(Drawable e) {
                        if (finalImgUrl.equals(_iv.getTag())) _iv.setImageDrawable(e);
                        if (_pr != null) _pr.setVisibility(View.GONE);
                    }
                });

            h.imgMsg.setOnClickListener(v -> {
                if (listener != null) listener.onImageClick(finalPostId, finalImgUrl);
            });
        }

        if (h.txtCaption != null && !"failed".equals(uploadState)) {
            boolean hasCaption = !caption.isEmpty();
            h.txtCaption.setVisibility(hasCaption || Boolean.TRUE.equals(isForwarded) ? View.VISIBLE : View.GONE);
            if (hasCaption) {
                h.txtCaption.setText(caption);
                h.txtCaption.setTextColor(Ink.c("#1A1A1A"));
                SmartLinkMovementMethod.apply(h.txtCaption);
                if (Boolean.TRUE.equals(isForwarded)) _prependForwarded(h.txtCaption);
            }
        }
    }

    // ─── Native ad rendering (channels only) ───────────────────────────────────
    private void bindNativeAd(NativeAdVH h, int slotId) {
        NativeAdView adView = (NativeAdView) h.itemView;
        NativeAd nativeAd = nativeAdManager != null ? nativeAdManager.getAdForSlot(slotId) : null;
        if (nativeAd == null) {
            adView.setVisibility(View.GONE);
            return;
        }
        adView.setVisibility(View.VISIBLE);

        adView.setHeadlineView(h.headline);
        adView.setMediaView(h.media);
        adView.setBodyView(h.body);
        adView.setCallToActionView(h.cta);
        adView.setIconView(h.appIcon);
        adView.setAdvertiserView(h.advertiser);
        adView.setAdChoicesView(h.adChoicesContainer); // AdMob draws the required icon inside this — never skip

        h.headline.setText(nativeAd.getHeadline());

        if (nativeAd.getBody() != null) {
            h.body.setText(nativeAd.getBody());
            h.body.setVisibility(View.VISIBLE);
        } else {
            h.body.setVisibility(View.INVISIBLE);
        }

        if (nativeAd.getCallToAction() != null) {
            h.cta.setText(nativeAd.getCallToAction());
            h.cta.setVisibility(View.VISIBLE);
        } else {
            h.cta.setVisibility(View.INVISIBLE);
        }

        if (nativeAd.getIcon() != null) {
            h.appIcon.setImageDrawable(nativeAd.getIcon().getDrawable());
            h.appIcon.setVisibility(View.VISIBLE);
        } else {
            h.appIcon.setVisibility(View.GONE);
        }

        if (nativeAd.getAdvertiser() != null) {
            h.advertiser.setText(nativeAd.getAdvertiser());
            h.advertiser.setVisibility(View.VISIBLE);
        } else {
            h.advertiser.setVisibility(View.GONE);
        }

        // This MUST be the last call — it finalizes all the view bindings above.
        adView.setNativeAd(nativeAd);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static final int IMG_CORNER_RADIUS_DP = 12;

    private int _getListContentWidth() {
        int w = recyclerView.getWidth();
        if (w <= 0) return context.getResources().getDisplayMetrics().widthPixels;
        return w - recyclerView.getPaddingLeft() - recyclerView.getPaddingRight();
    }

    private int[] _adjustImageViewSize(ImageView iv, int imgW, int imgH, LinearLayout bubbleContainer) {
        if (imgW <= 0 || imgH <= 0) { imgW = 100; imgH = 100; }

        int screenWidth = _getListContentWidth();
        int paddingLeft = bubbleContainer.getPaddingLeft();
        int paddingRight = bubbleContainer.getPaddingRight();

        int maxWidth = screenWidth - dp(56) - dp(12) - dp(5) - dp(5) - paddingLeft - paddingRight;

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
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, widthPx, heightPx, radiusPx);
            }
        });
        iv.setClipToOutline(true);
    }

    private void _bindLinkPreview(PostVH h, String postId, String text, HashMap<String, Object> post) {
        if (h.layoutLinkPreview == null) return;

        String embeddedTitle = (String) post.get("link_preview_title");
        String embeddedUrl   = (String) post.get("link_preview_url");
        if (embeddedUrl != null && !embeddedUrl.isEmpty()) {
            h.layoutLinkPreview.setTag(null);
            h.layoutLinkPreview.setVisibility(View.VISIBLE);
            String site = (String) post.get("link_preview_site");
            String desc = (String) post.get("link_preview_desc");
            String img  = (String) post.get("link_preview_image");
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

        String url = LinkPreviewHelper.extractFirstUrl(text);
        if (url == null) {
            h.layoutLinkPreview.setVisibility(View.GONE);
            h.layoutLinkPreview.setTag(null);
            return;
        }
        h.layoutLinkPreview.setVisibility(View.GONE);
        h.layoutLinkPreview.setTag(postId);
        LinkPreviewHelper.fetch(url, preview -> {
            if (!postId.equals(h.layoutLinkPreview.getTag())) return;
            if (preview == null) return;
            h.layoutLinkPreview.setVisibility(View.VISIBLE);
            h.txtLinkPreviewSite.setText(preview.siteName != null ? preview.siteName : "");
            h.txtLinkPreviewTitle.setText(preview.title != null ? preview.title : "");
            h.txtLinkPreviewDesc.setText(preview.description != null ? preview.description : "");
            h.txtLinkPreviewDesc.setVisibility(
                preview.description != null && !preview.description.isEmpty() ? View.VISIBLE : View.GONE);
            if (preview.imageUrl != null && !preview.imageUrl.isEmpty()) {
                h.imgLinkPreview.setVisibility(View.VISIBLE);
                Glide.with(context).load(preview.imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(h.imgLinkPreview);
            } else {
                h.imgLinkPreview.setVisibility(View.GONE);
            }
        });
    }

    private void _hideMedia(ImageView img, ProgressBar pb, TextView timeImg, TextView caption) {
        if (img     != null) { img.setVisibility(View.GONE); img.setAlpha(1f); img.setColorFilter(null); }
        if (pb      != null) pb.setVisibility(View.GONE);
        if (timeImg != null) timeImg.setVisibility(View.GONE);
        if (caption != null) caption.setVisibility(View.GONE);
    }

    private void _prependForwarded(TextView tv) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        ssb.append("↪ Forwarded\n");
        ssb.setSpan(new StyleSpan(Typeface.ITALIC),      0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new ForegroundColorSpan(Color.GRAY), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new RelativeSizeSpan(0.82f),         0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (tv.getText() != null) ssb.append(tv.getText());
        tv.setText(ssb);
    }

    /** Compact count formatting shared by the like / comment / view counters. */
    private String _formatCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.getDefault(), "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String formatTime(long ts) {
        return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(ts));
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
