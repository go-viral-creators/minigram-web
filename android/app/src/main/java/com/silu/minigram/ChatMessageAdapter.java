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
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import java.text.*;
import java.util.*;

/**
 * ChatMessageAdapter
 *  - ViewType 0 = sent message
 *  - ViewType 1 = received message
 *  - ViewType 2 = date header (Today / Yesterday / Tue, 29 Apr etc.)
 *
 * NEW in this version:
 *  ┌─────────────────────────────────────────────────────────────────────────┐
 *  │  WhatsApp-style INSTANT IMAGE SEND:                                     │
 *  │  • local_uri field  → shows image from device immediately               │
 *  │  • upload_state field → "uploading" | "failed" | null(sent)             │
 *  │  • Uploading: semi-dim + indeterminate spinner                          │
 *  │  • Failed: red-tinted + "⚠ Tap to retry" caption                       │
 *  │  • Tick icon: schedule=pending, error=failed, check/done_all=sent       │
 *  │  • onRetryUpload callback to ChatActivity                               │
 *  └─────────────────────────────────────────────────────────────────────────┘
 *  - Slide right-to-reply gesture on every bubble
 *  - WhatsApp-style date chips between messages
 *  - Glide DiskCacheStrategy.ALL for offline image access
 */
public class ChatMessageAdapter extends BaseAdapter {

    // ─── Constants ────────────────────────────────────────────────────────────
    private static final int TYPE_SENT   = 0;
    private static final int TYPE_RECV   = 1;
    private static final int TYPE_DATE   = 2;

    private static final int SWIPE_THRESHOLD_DP = 72;
    private static final int SWIPE_MAX_DP       = 100;
    private static android.graphics.Typeface materialTypeface = null;

    // ─── Interfaces ───────────────────────────────────────────────────────────
    public interface AdapterListener {
        void onReplyClick(String msgId);
        void onImageClick(String msgId, String imgUrl);
        void onItemClick(HashMap<String, Object> msg);
        void onItemLongClick(String msgId);
        void onSwipeReply(HashMap<String, Object> msg);
        /** NEW: called when user taps failed-upload image to retry */
        void onRetryUpload(String tempId);
    }

    // ─── State ────────────────────────────────────────────────────────────────
    private final Context                            context;
    private final ArrayList<HashMap<String, Object>> messageList;
    private final ArrayList<Object>                  displayList = new ArrayList<>();
    private final String   myUid;
    private final String   peerName;
    private final String   peerPhoto;
    private final ListView listView;
    private final Set<String> selectedMsgs;
    private AdapterListener listener;
    private String highlightedMsgId = null;
    private String searchQuery = null;         // current in-chat search term
    private String searchHighlightMsgId = null; // msgId of the currently focused match
    // Tracks which image URLs the user has manually tapped-to-download this
    // app session — static so it persists across reopening the same chat.
    private static final java.util.Set<String> downloadedImages = new java.util.HashSet<>();
    private static final java.util.Set<String> downloadedVoices = new java.util.HashSet<>();

    // ── Voice message playback ──────────────────────────────────────────────
    private MediaPlayer voicePlayer     = null;
    private String      playingMsgId    = null;
    private Runnable    voiceSeekRunnable = null;
    private final Handler voiceSeekHandler = new Handler(Looper.getMainLooper());

    // ─── Constructor ─────────────────────────────────────────────────────────
    public ChatMessageAdapter(Context context,
                               ArrayList<HashMap<String, Object>> messageList,
                               String myUid,
                               String peerName,
                               String peerPhoto,
                               ListView listView,
                               Set<String> selectedMsgs) {
        this.context      = context;
        this.messageList  = messageList;
        this.myUid        = myUid;
        this.peerName     = peerName;
        this.peerPhoto    = peerPhoto;
        this.listView     = listView;
        this.selectedMsgs = selectedMsgs;
        rebuildDisplayList();
    }

    // ─── Public setters ───────────────────────────────────────────────────────
    public void setListener(AdapterListener l)     { this.listener = l; }
    public void setHighlightedMsgId(String id)     { this.highlightedMsgId = id; }
    public String getHighlightedMsgId()            { return highlightedMsgId; }
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

    // ─── Display list (with date headers) ─────────────────────────────────────
    private void rebuildDisplayList() {
        displayList.clear();
        String lastDateKey = "";
        for (HashMap<String, Object> msg : messageList) {
            Long ts = (Long) msg.get("timestamp");
            if (ts == null) ts = System.currentTimeMillis();
            String dateKey = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date(ts));
            if (!dateKey.equals(lastDateKey)) {
                displayList.add(new DateHeader(formatDateLabel(ts)));
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
        DateHeader(String l) { label = l; }
    }

    // ─── BaseAdapter ─────────────────────────────────────────────────────────
    @Override public int getCount()        { return displayList.size(); }
    @Override public Object getItem(int p) { return displayList.get(p); }
    @Override public long getItemId(int p) { return p; }
    @Override public int getViewTypeCount(){ return 3; }

    @Override
    public int getItemViewType(int pos) {
        Object item = displayList.get(pos);
        if (item instanceof DateHeader) return TYPE_DATE;
        @SuppressWarnings("unchecked")
        HashMap<String, Object> msg = (HashMap<String, Object>) item;
        String sUid = (String) msg.get("sender_uid");
        return myUid.equals(sUid) ? TYPE_SENT : TYPE_RECV;
    }

    @Override
    public void notifyDataSetChanged() {
        rebuildDisplayList();
        super.notifyDataSetChanged();
    }

    public void refreshWithoutScroll() {
        int index = listView.getFirstVisiblePosition();
        View v   = listView.getChildAt(0);
        int top  = (v == null) ? 0 : (v.getTop() - listView.getPaddingTop());
        int curMode = listView.getTranscriptMode();
        listView.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
        rebuildDisplayList();
        super.notifyDataSetChanged();
        listView.post(() -> {
            listView.setSelectionFromTop(index, top);
            listView.setTranscriptMode(curMode);
        });
    }

    // ─── getView ─────────────────────────────────────────────────────────────
    @Override
    public View getView(int pos, View cv, ViewGroup parent) {
        int viewType = getItemViewType(pos);

        // ── DATE HEADER ───────────────────────────────────────────────────────
        if (viewType == TYPE_DATE) {
            if (cv == null)
                cv = LayoutInflater.from(context).inflate(R.layout.item_date_header, parent, false);
            DateHeader dh = (DateHeader) displayList.get(pos);
            ((TextView) cv.findViewById(R.id.txt_date_label)).setText(dh.label);
            cv.setOnClickListener(null);
            cv.setOnLongClickListener(null);
            return cv;
        }

        // ── MESSAGE ───────────────────────────────────────────────────────────
        if (cv == null) {
            int layout = (viewType == TYPE_SENT) ? R.layout.item_msg_sent : R.layout.item_msg_recv;
            cv = LayoutInflater.from(context).inflate(layout, parent, false);
        }

        @SuppressWarnings("unchecked")
        HashMap<String, Object> msg = (HashMap<String, Object>) displayList.get(pos);

        final String  msgId       = (String)  msg.get("msgId");
        final String  senderUid   = (String)  msg.get("sender_uid");
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

        // ── NEW: Upload state fields ──────────────────────────────────────────
        final String  localUri    = (String)  msg.get("local_uri");
        final String  uploadState = (String)  msg.get("upload_state");
        final boolean hasLocalUri = localUri != null && !localUri.isEmpty();

        if (text == null)    text    = "";
        if (imgUrl == null)  imgUrl  = "";
        if (caption == null) caption = "";
        if (msgId == null)   return cv;

        // ── Highlight / selection background ──────────────────────────────────
        if (msgId.equals(searchHighlightMsgId)) {
            cv.setBackgroundColor(Color.parseColor("#66FFC107")); // focused search match — amber
        } else if (msgId.equals(highlightedMsgId)) {
            cv.setBackgroundColor(Color.parseColor("#401A73E8"));
        } else {
            cv.setBackgroundColor(selectedMsgs.contains(msgId)
                ? Color.parseColor("#402196F3") : Color.TRANSPARENT);
        }

        // ── Find views ────────────────────────────────────────────────────────
        TextView     txtMsg       = cv.findViewById(R.id.txt_msg);
        ImageView    imgMsg       = cv.findViewById(R.id.img_msg);
        ProgressBar  imgProgress  = cv.findViewById(R.id.img_loading);
        TextView     btnDownloadImage = cv.findViewById(R.id.btn_download_image);
        TextView     txtTimeImage = cv.findViewById(R.id.txt_time_image);
        TextView     txtTimeMsg   = cv.findViewById(R.id.txt_time_msg);
        LinearLayout replyBar     = cv.findViewById(R.id.layout_reply_quote);
        TextView     txtReply     = cv.findViewById(R.id.txt_reply_quote);
        TextView     txtEdited    = cv.findViewById(R.id.txt_edited_label);
        TextView     txtReactions = cv.findViewById(R.id.txt_reactions);
        TextView     txtPinned    = cv.findViewById(R.id.txt_pinned_label);
        TextView     txtCaption   = cv.findViewById(R.id.txt_caption);
        TextView     txtTick      = cv.findViewById(R.id.txt_tick_status);
        LinearLayout layoutVoiceMsg  = cv.findViewById(R.id.layout_voice_msg);
        TextView     btnVoicePlay    = cv.findViewById(R.id.btn_voice_play);
        SeekBar      seekVoice       = cv.findViewById(R.id.seek_voice);
        TextView     txtVoiceDuration = cv.findViewById(R.id.txt_voice_duration);
        View         bubbleView   = cv.findViewById(viewType == TYPE_SENT ? R.id.linear77 : R.id.linear35);

        txtTimeImage.setVisibility(View.GONE);
        txtTimeMsg.setVisibility(View.GONE);

        // ── Avatar (received) ─────────────────────────────────────────────────
        if (viewType == TYPE_RECV) {
            ImageView imgAv = cv.findViewById(R.id.img_avatar);
            if (imgAv != null) {
                if (peerPhoto != null && !peerPhoto.isEmpty()) {
                    Glide.with(context).load(peerPhoto)
                        .apply(RequestOptions.circleCropTransform()
                            .placeholder(R.drawable.ic_person)
                            .error(R.drawable.ic_person))
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(imgAv);
                }
                imgAv.setOnClickListener(v2 -> {
                    if (listener != null) listener.onImageClick(null, null);
                });
            }
        }

        // ── Read receipt tick  ────────────────────────────────────────────────
        // NEW: show schedule/error icons for upload states
        if (txtTick != null && viewType == TYPE_SENT) {
            _setTickState(txtTick, uploadState, hasLocalUri, status);
        }

        // ── Deleted message ───────────────────────────────────────────────────
        if (Boolean.TRUE.equals(deletedAll)) {
            imgMsg.setVisibility(View.GONE);
            imgMsg.setAlpha(1.0f);
            imgMsg.setColorFilter(null);
            if (imgProgress != null) imgProgress.setVisibility(View.GONE);
            if (btnDownloadImage != null) btnDownloadImage.setVisibility(View.GONE);
            if (txtCaption  != null) txtCaption.setVisibility(View.GONE);
            if (replyBar    != null) replyBar.setVisibility(View.GONE);
            if (layoutVoiceMsg != null) layoutVoiceMsg.setVisibility(View.GONE);
            txtMsg.setVisibility(View.VISIBLE);
            txtMsg.setText("🚫 This message was deleted");
            txtMsg.setTextColor(Color.parseColor("#9E9E9E"));
            txtMsg.setTypeface(null, Typeface.ITALIC);
            txtMsg.setMovementMethod(null);
            txtMsg.setOnTouchListener(null);
            if (ts != null) { txtTimeMsg.setText(formatTime(ts)); txtTimeMsg.setVisibility(View.VISIBLE); }
            cv.setOnLongClickListener(null);
            cv.setOnClickListener(null);
            clearSwipe(bubbleView);
            return cv;
        }

        // ── Reply bar ─────────────────────────────────────────────────────────
        if (replyBar != null) {
            if (replyId != null && !replyId.isEmpty()) {
                replyBar.setVisibility(View.VISIBLE);
                if (txtReply != null) {
                    if (replySender != null && replyPrev != null) {
                        SpannableStringBuilder sb = new SpannableStringBuilder();
                        sb.append(replySender).append("\n");
                        int end = sb.length();
                        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        if ("You".equals(replySender))
                            sb.setSpan(new ForegroundColorSpan(Color.parseColor("#25D366")), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.append(replyPrev);
                        txtReply.setText(sb);
                    } else {
                        txtReply.setText(replyPrev != null ? replyPrev : "Original message");
                    }
                }
                final String rId = replyId;
                replyBar.setOnClickListener(v2 -> {
                    if (listener != null) listener.onReplyClick(rId);
                });
            } else {
                replyBar.setVisibility(View.GONE);
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        //  IMAGE / TEXT SECTION
        //  NEW: handles local_uri (upload in progress/failed) + remote URL
        // ─────────────────────────────────────────────────────────────────────
        final String finalImgUrl = imgUrl;
        final String finalMsgId  = msgId;
        @SuppressWarnings("unchecked")
        final HashMap<String, Object> finalMsg = msg;

        // Determine whether this is an image message (local OR remote)
        boolean isImageMsg = "image".equals(msgType) && (hasLocalUri || !imgUrl.isEmpty());
        boolean isVoiceMsg = "voice".equals(msgType);

        if (isImageMsg) {
            if (layoutVoiceMsg != null) layoutVoiceMsg.setVisibility(View.GONE);
            boolean hasCaption = !caption.isEmpty();
            txtMsg.setVisibility(View.GONE);
            imgMsg.setVisibility(View.VISIBLE);

            // ── LOCAL URI: upload in progress or failed ───────────────────────
            if (hasLocalUri) {
                if (btnDownloadImage != null) btnDownloadImage.setVisibility(View.GONE);

                // Decode just the bounds (cheap) to size the bubble by real aspect ratio
                int origW = 100, origH = 100;
                try {
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeStream(
                        context.getContentResolver().openInputStream(Uri.parse(localUri)), null, opts);
                    origW = opts.outWidth;
                    origH = opts.outHeight;
                } catch (Exception ignored) {}
                _adjustImageViewSize(imgMsg, origW, origH, bubbleView, viewType == TYPE_SENT);

                // Load from local URI for instant preview
                Glide.with(context)
                    .load(Uri.parse(localUri))
                    .apply(new RequestOptions()
                        .placeholder(R.drawable.img_placeholder))
                    .into(imgMsg);

                if ("uploading".equals(uploadState)) {
                    // Show indeterminate spinner, dim image slightly
                    if (imgProgress != null) {
                        imgProgress.setVisibility(View.VISIBLE);
                        imgProgress.setIndeterminate(true);
                    }
                    imgMsg.setAlpha(0.75f);
                    imgMsg.setColorFilter(null);
                    imgMsg.setOnClickListener(null);  // no action while uploading
                    imgMsg.setOnLongClickListener(null);

                } else if ("failed".equals(uploadState)) {
                    // Red tint + retry message
                    if (imgProgress != null) imgProgress.setVisibility(View.GONE);
                    imgMsg.setAlpha(0.5f);
                    imgMsg.setColorFilter(Color.parseColor("#80FF4444"),
                        android.graphics.PorterDuff.Mode.SRC_ATOP);

                    // Show retry hint in caption area
                    if (txtCaption != null) {
                        txtCaption.setVisibility(View.VISIBLE);
                        txtCaption.setText("⚠  Upload failed  ·  Tap to retry");
                        txtCaption.setTextColor(Color.parseColor("#FF6B6B"));
                    }

                    // Tap image to retry
                    final String fTempId = finalMsgId;
                    imgMsg.setOnClickListener(v2 -> {
                        if (listener != null) listener.onRetryUpload(fTempId);
                    });
                    imgMsg.setOnLongClickListener(v2 -> {
                        if (listener != null) listener.onItemLongClick(fTempId);
                        return true;
                    });
                }

                // Time for local images
                if (txtCaption == null || !txtCaption.isShown() || "failed".equals(uploadState)) {
                    if (!hasCaption && !"failed".equals(uploadState))
                        txtTimeImage.setVisibility(View.VISIBLE);
                    else
                        txtTimeMsg.setVisibility(View.VISIBLE);
                }

            } else {
                // ── REMOTE URL: blur placeholder unless downloaded/auto-download ──
                imgMsg.setAlpha(1.0f);
                imgMsg.setColorFilter(null);

                boolean isMine = myUid != null && myUid.equals(finalMsg.get("sender_uid"));
                boolean isDownloaded = isMine || downloadedImages.contains(finalImgUrl)
                    || DataStorageSheet.isAutoDownloadImages(context);

                final ImageView _imgMsg      = imgMsg;
                final ProgressBar _imgProgress = imgProgress;
                final TextView _btnDownload  = btnDownloadImage;

                if (!isDownloaded) {
                    // Cheap "blur"-like effect: load a heavily downscaled thumbnail and
                    // let the ImageView's bilinear filter stretch it — no extra library needed.
                    if (_imgProgress != null) _imgProgress.setVisibility(View.GONE);
                    if (_btnDownload != null) _btnDownload.setVisibility(View.VISIBLE);
                    _adjustImageViewSize(imgMsg, 300, 225, bubbleView, viewType == TYPE_SENT); // ratio unknown pre-download — reasonable default
                    Glide.with(context)
                        .load(imgUrl)
                        .apply(new RequestOptions()
                            .placeholder(R.drawable.img_placeholder)
                            .error(R.drawable.img_placeholder)
                            .override(24, 24))
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(imgMsg);

                    View.OnClickListener downloadTap = v2 -> {
                        downloadedImages.add(finalImgUrl);
                        refreshWithoutScroll();
                    };
                    imgMsg.setOnClickListener(downloadTap);
                    if (_btnDownload != null) _btnDownload.setOnClickListener(downloadTap);
                    imgMsg.setOnLongClickListener(v2 -> {
                        if (listener != null) listener.onItemLongClick(finalMsgId);
                        return true;
                    });

                } else {
                    if (_btnDownload != null) _btnDownload.setVisibility(View.GONE);
                    if (imgProgress != null) imgProgress.setVisibility(View.VISIBLE);

                    // ✅ CustomTarget (SimpleTarget deprecated) + DiskCacheStrategy.ALL
                    Glide.with(context)
                        .load(imgUrl)
                        .apply(new RequestOptions()
                            .placeholder(R.drawable.img_placeholder)
                            .error(R.drawable.img_placeholder))
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(new CustomTarget<Drawable>() {
                            @Override
                            public void onResourceReady(@androidx.annotation.NonNull Drawable resource,
                                                        Transition<? super Drawable> t) {
                                _adjustImageViewSize(_imgMsg,
                                    resource.getIntrinsicWidth(), resource.getIntrinsicHeight(), bubbleView, viewType == TYPE_SENT);
                                _imgMsg.setImageDrawable(resource);
                                if (_imgProgress != null) _imgProgress.setVisibility(View.GONE);
                            }
                            @Override
                            public void onLoadCleared(Drawable placeholder) {
                                _imgMsg.setImageDrawable(placeholder);
                            }
                            @Override
                            public void onLoadFailed(Drawable errorDrawable) {
                                _imgMsg.setImageDrawable(errorDrawable);
                                if (_imgProgress != null) _imgProgress.setVisibility(View.GONE);
                            }
                        });

                    imgMsg.setOnClickListener(v2 -> {
                        if (listener != null) listener.onImageClick(finalMsgId, finalImgUrl);
                    });
                    imgMsg.setOnLongClickListener(v2 -> {
                        if (listener != null) listener.onItemLongClick(finalMsgId);
                        return true;
                    });
                }

                // Caption for remote image
                if (txtCaption != null) {
                    if (hasCaption || Boolean.TRUE.equals(isForwarded)) {
                        txtCaption.setVisibility(View.VISIBLE);
                        txtCaption.setTextColor(Color.parseColor("#FFFFFF")); // reset color
                        MsgRenderer.render(context, txtCaption, caption);
                        SmartLinkMovementMethod.apply(txtCaption);
                        if (Boolean.TRUE.equals(isForwarded)) prependForwarded(txtCaption);
                    } else {
                        txtCaption.setVisibility(View.GONE);
                    }
                }
                if (!hasCaption) txtTimeImage.setVisibility(View.VISIBLE);
                else             txtTimeMsg.setVisibility(View.VISIBLE);
            }

        } else if (isVoiceMsg) {
            // ── VOICE MESSAGE ────────────────────────────────────────────────
            imgMsg.setVisibility(View.GONE);
            imgMsg.setAlpha(1.0f);
            imgMsg.setColorFilter(null);
            if (imgProgress != null) imgProgress.setVisibility(View.GONE);
            if (btnDownloadImage != null) btnDownloadImage.setVisibility(View.GONE);
            txtMsg.setVisibility(View.GONE);
            if (layoutVoiceMsg != null) layoutVoiceMsg.setVisibility(View.VISIBLE);

            if (txtCaption != null) {
                if ("failed".equals(uploadState)) {
                    txtCaption.setVisibility(View.VISIBLE);
                    txtCaption.setText("⚠  Upload failed  ·  Tap to retry");
                    txtCaption.setTextColor(Color.parseColor("#FF6B6B"));
                } else if (caption != null && !caption.isEmpty()) {
                    txtCaption.setVisibility(View.VISIBLE);
                    txtCaption.setTextColor(Color.parseColor("#FFFFFF"));
                    MsgRenderer.render(context, txtCaption, caption);
                    SmartLinkMovementMethod.apply(txtCaption);
                } else {
                    txtCaption.setVisibility(View.GONE);
                }
            }

            int durationSec = 0;
            Object durObj = finalMsg.get("duration");
            if (durObj instanceof Number) durationSec = ((Number) durObj).intValue();
            final int finalDuration = durationSec;
            Object urlObj = finalMsg.get("voice_url");
            final String voiceUrl = urlObj != null ? urlObj.toString() : "";

            // ── Uploading (sender's own pending recording) ──────────────────
            if ("uploading".equals(uploadState)) {
                if (btnVoicePlay != null) { btnVoicePlay.setText("schedule"); btnVoicePlay.setOnClickListener(null); }
                if (seekVoice != null) { seekVoice.setProgress(0); seekVoice.setEnabled(false); }
                if (txtVoiceDuration != null) txtVoiceDuration.setText("Uploading…");

            } else if ("failed".equals(uploadState)) {
                if (btnVoicePlay != null) {
                    btnVoicePlay.setText("error_outline");
                    final String fTempId = finalMsgId;
                    btnVoicePlay.setOnClickListener(v2 -> { if (listener != null) listener.onRetryUpload(fTempId); });
                }
                if (seekVoice != null) seekVoice.setEnabled(false);
                if (txtVoiceDuration != null) txtVoiceDuration.setText("Failed");

            } else {
                // ── Sent/received — gate playback behind download-on-demand ───
                boolean isMineVoice = myUid != null && myUid.equals(finalMsg.get("sender_uid"));
                boolean isDownloaded = isMineVoice || downloadedVoices.contains(voiceUrl)
                    || DataStorageSheet.isAutoDownloadVoice(context);

                if (seekVoice != null) seekVoice.setEnabled(true);

                if (!isDownloaded) {
                    if (btnVoicePlay != null) {
                        btnVoicePlay.setText("file_download");
                        final SeekBar fSeek = seekVoice;
                        final TextView fDurationView = txtVoiceDuration;
                        final TextView fBtn = btnVoicePlay;
                        btnVoicePlay.setOnClickListener(v2 -> {
                            downloadedVoices.add(voiceUrl);
                            // Download = play immediately, like Telegram/WhatsApp —
                            // _toggleVoicePlayback shows its own loading state
                            // ("schedule" icon) while the audio buffers.
                            _toggleVoicePlayback(finalMsgId, voiceUrl, finalDuration, fBtn, fSeek, fDurationView);
                        });
                    }
                    if (seekVoice != null) seekVoice.setProgress(0);
                    if (txtVoiceDuration != null) txtVoiceDuration.setText(
                        String.format(Locale.US, "%d:%02d", finalDuration / 60, finalDuration % 60));

                } else {
                    boolean isThisPlaying = finalMsgId.equals(playingMsgId) && voicePlayer != null && voicePlayer.isPlaying();
                    if (btnVoicePlay != null) btnVoicePlay.setText(isThisPlaying ? "pause" : "play_arrow");
                    int shownSec = isThisPlaying ? voicePlayer.getCurrentPosition() / 1000 : finalDuration;
                    if (seekVoice != null) {
                        int maxMs = isThisPlaying && voicePlayer.getDuration() > 0 ? voicePlayer.getDuration() : finalDuration * 1000;
                        seekVoice.setMax(Math.max(maxMs, 1));
                        seekVoice.setProgress(isThisPlaying ? voicePlayer.getCurrentPosition() : 0);
                    }
                    if (txtVoiceDuration != null) {
                        txtVoiceDuration.setText(String.format(Locale.US, "%d:%02d", shownSec / 60, shownSec % 60));
                    }
                    if (btnVoicePlay != null) {
                        final SeekBar fSeek = seekVoice;
                        final TextView fDurationView = txtVoiceDuration;
                        final TextView fBtn = btnVoicePlay;
                        btnVoicePlay.setOnClickListener(v2 ->
                            _toggleVoicePlayback(finalMsgId, voiceUrl, finalDuration, fBtn, fSeek, fDurationView));
                    }
                }
            }
            txtTimeMsg.setVisibility(View.VISIBLE);

        } else {
            // ── TEXT MESSAGE ─────────────────────────────────────────────────
            imgMsg.setVisibility(View.GONE);
            imgMsg.setAlpha(1.0f);
            imgMsg.setColorFilter(null);
            if (imgProgress != null) imgProgress.setVisibility(View.GONE);
            if (btnDownloadImage != null) btnDownloadImage.setVisibility(View.GONE);
            if (txtCaption  != null) txtCaption.setVisibility(View.GONE);
            if (layoutVoiceMsg != null) layoutVoiceMsg.setVisibility(View.GONE);
            txtMsg.setVisibility(View.VISIBLE);
            MsgRenderer.render(context, txtMsg, text);
            _applySearchHighlight(txtMsg);
            SmartLinkMovementMethod.apply(txtMsg);
            if (Boolean.TRUE.equals(isForwarded)) prependForwarded(txtMsg);
            txtTimeMsg.setVisibility(View.VISIBLE);
        }

        // ── Labels ────────────────────────────────────────────────────────────
        if (txtEdited != null)
            txtEdited.setVisibility(Boolean.TRUE.equals(isEdited) ? View.VISIBLE : View.GONE);
        if (txtPinned != null)
            txtPinned.setVisibility(Boolean.TRUE.equals(isPinned) ? View.VISIBLE : View.GONE);

        // ── Reactions ───────────────────────────────────────────────────────────
        if (txtReactions != null) {
            Object rcObj = finalMsg.get("reaction_counts");
            if (rcObj instanceof HashMap) {
                @SuppressWarnings("unchecked")
                HashMap<String, Integer> counts = (HashMap<String, Integer>) rcObj;
                if (counts.isEmpty()) {
                    txtReactions.setVisibility(View.GONE);
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (HashMap.Entry<String, Integer> e : counts.entrySet()) {
                        if (sb.length() > 0) sb.append("  ");
                        sb.append(e.getKey());
                        if (e.getValue() != null && e.getValue() > 1) sb.append(" ").append(e.getValue());
                    }
                    txtReactions.setText(sb.toString());
                    txtReactions.setVisibility(View.VISIBLE);
                    // Tapping the chip re-opens the reaction picker for quick change
                    final String rMsgId = finalMsgId;
                    txtReactions.setOnClickListener(v -> {
                        if (listener != null) listener.onItemClick(finalMsg);
                    });
                }
            } else {
                txtReactions.setVisibility(View.GONE);
            }
        }

        // ── Timestamps ────────────────────────────────────────────────────────
        if (ts != null) {
            String time = formatTime(ts);
            txtTimeImage.setText(time);
            txtTimeMsg.setText(time);
        }

        // ── Bubble click / long-click ─────────────────────────────────────────
        // Only set if not already set for upload-state handling above
        if (!hasLocalUri || "sent".equals(uploadState) || uploadState == null) {
            cv.setOnLongClickListener(v2 -> {
                if (listener != null) listener.onItemLongClick(finalMsgId);
                return true;
            });
            cv.setOnClickListener(v2 -> {
                if (listener != null) listener.onItemClick(finalMsg);
            });
        } else {
            // During upload: disable long-click context menu on bubble row
            cv.setOnLongClickListener(null);
            cv.setOnClickListener(null);
        }

        // ── Slide-to-reply gesture ────────────────────────────────────────────
        // Only enable when message is fully sent (not uploading)
        if (!hasLocalUri) {
            setupSwipeToReply(cv, bubbleView, finalMsg, viewType);
        } else {
            clearSwipe(bubbleView);
            cv.setOnTouchListener(null);
        }

        return cv;
    }

    // ─── Tick state helper ────────────────────────────────────────────────────

    /**
     * Sets the WhatsApp-style tick/status icon including new upload states.
     * States:
     *   uploading/pending  → schedule (clock)
     *   failed             → error    (!)
     *   read               → done_all (blue)
     *   delivered          → done_all (grey)
     *   default/sent       → check    (grey)
     */
    // ─── Voice message playback ───────────────────────────────────────────────

    private void _toggleVoicePlayback(String msgId, String url, int durationSec,
                                       TextView btnPlay, SeekBar seekBar, TextView txtDuration) {
        if (url == null || url.isEmpty()) return;

        // Tapping the item that is currently playing → pause it
        if (msgId.equals(playingMsgId) && voicePlayer != null && voicePlayer.isPlaying()) {
            voicePlayer.pause();
            if (btnPlay != null) btnPlay.setText("play_arrow");
            if (voiceSeekRunnable != null) voiceSeekHandler.removeCallbacks(voiceSeekRunnable);
            return;
        }

        // Stop whatever else was playing (different message)
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
                if (seekBar != null) seekBar.setMax(Math.max(mp.getDuration(), 1)); // ms, not seconds
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
                            voiceSeekHandler.postDelayed(this, 80); // fast tick = smooth glide, not a once-a-second jump
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

    /** Call this from ChatActivity.onDestroy()/onPause() to release the player. */
    public void stopVoicePlayback() {
        _stopVoicePlayback();
    }

    private void _stopVoicePlayback() {
        if (voiceSeekRunnable != null) voiceSeekHandler.removeCallbacks(voiceSeekRunnable);
        if (voicePlayer != null) {
            try { voicePlayer.release(); } catch (Exception ignored) {}
            voicePlayer = null;
        }
        playingMsgId = null;
    }

    private void _setTickState(TextView txtTick, String uploadState,
                                boolean hasLocalUri, String msgStatus) {
        if (txtTick == null) return;
        if (hasLocalUri) {
            if ("uploading".equals(uploadState) || uploadState == null) {
                txtTick.setText("schedule");
                txtTick.setTextColor(Color.parseColor("#9E9E9E"));
            } else if ("failed".equals(uploadState)) {
                txtTick.setText("error_outline");
                txtTick.setTextColor(Color.parseColor("#FF6B6B"));
            }
        } else {
            if ("read".equals(msgStatus)) {
                txtTick.setText("done_all");
                txtTick.setTextColor(Color.parseColor("#1A73E8"));
            } else if ("delivered".equals(msgStatus)) {
                txtTick.setText("done_all");
                txtTick.setTextColor(Color.parseColor("#9E9E9E"));
            } else {
                txtTick.setText("check");
                txtTick.setTextColor(Color.parseColor("#9E9E9E"));
            }
        }
    }

    // ─── Slide to reply ───────────────────────────────────────────────────────
    private void setupSwipeToReply(View rowView, View bubbleView,
                                    HashMap<String, Object> msg, int viewType) {
        if (bubbleView == null) return;

        final float   thresholdPx = dp(SWIPE_THRESHOLD_DP);
        final float   maxPx       = dp(SWIPE_MAX_DP);

        // Reuse or create the reply icon for this row
        TextView replyIcon = (TextView) rowView.getTag(R.id.btn_attach_icon);
        if (replyIcon == null) {
            replyIcon = new TextView(context);
            if (materialTypeface == null) {
                try {
                    materialTypeface = android.graphics.Typeface.createFromAsset(
                        context.getAssets(), "fonts/materialfont.ttf");
                } catch (Exception e) {
                    materialTypeface = android.graphics.Typeface.DEFAULT;
                }
            }
            replyIcon.setTypeface(materialTypeface);
            replyIcon.setText("reply");
            replyIcon.setTextSize(20);
            replyIcon.setTextColor(Color.parseColor("#1A73E8"));
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
        final float[]  startX         = {0f};
        final boolean[]triggered      = {false};

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
                    ObjectAnimator.ofFloat(bubbleView, "translationX",
                        bubbleView.getTranslationX(), 0f)
                        .setDuration(200).start();
                    ObjectAnimator animAlpha = ObjectAnimator.ofFloat(
                        finalReplyIcon, "alpha", finalReplyIcon.getAlpha(), 0f);
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
        ssb.setSpan(new StyleSpan(Typeface.ITALIC),       0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new ForegroundColorSpan(Color.GRAY),  0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new RelativeSizeSpan(0.85f),          0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (tv.getText() != null) ssb.append(tv.getText());
        tv.setText(ssb);
    }

    private String formatTime(long ts) {
        return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(ts));
    }

    private void _applySearchHighlight(TextView tv) {
        if (searchQuery == null || tv.getText() == null) return;
        String hay = tv.getText().toString();
        String needle = searchQuery.toLowerCase(Locale.getDefault());
        String lowerHay = hay.toLowerCase(Locale.getDefault());
        int idx = lowerHay.indexOf(needle);
        if (idx < 0) return;
        android.text.SpannableStringBuilder sb =
            new android.text.SpannableStringBuilder(tv.getText());
        while (idx >= 0) {
            sb.setSpan(new android.text.style.BackgroundColorSpan(Color.parseColor("#FFF176")),
                idx, idx + needle.length(),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Color.parseColor("#000000")),
                idx, idx + needle.length(),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            idx = lowerHay.indexOf(needle, idx + needle.length());
        }
        tv.setText(sb);
    }

    private int dp(int v) {
        return (int)(v * context.getResources().getDisplayMetrics().density);
    }

    // ── Dynamic image sizing (same aspect-ratio-fit approach as channel posts) ──
    private static final int IMG_CORNER_RADIUS_DP = 12;

    private int _getListContentWidth() {
        int w = listView.getWidth();
        if (w <= 0) {
            return context.getResources().getDisplayMetrics().widthPixels;
        }
        return w - listView.getPaddingLeft() - listView.getPaddingRight();
    }

    private int[] _adjustImageViewSize(ImageView iv, int imgW, int imgH, View bubbleContainer, boolean isSent) {
        if (imgW <= 0 || imgH <= 0) { imgW = 100; imgH = 100; }

        int screenWidth = _getListContentWidth();
        int paddingLeft  = bubbleContainer.getPaddingLeft();
        int paddingRight = bubbleContainer.getPaddingRight();

        // Identical formula to ChannelPostAdapter — root horizontal chrome +
        // the image's own 5dp+5dp margins. Chat's chrome differs per side:
        //   sent row : root paddingLeft 64dp + paddingRight 12dp
        //   recv row : root padding 8dp+64dp, plus the 34dp avatar + its 6dp margin
        int chrome = isSent ? dp(64) + dp(12)
                            : dp(8) + dp(64) + dp(34) + dp(6);
        int maxWidth = screenWidth - chrome - dp(5) - dp(5) - paddingLeft - paddingRight;

        int minHeight = dp(120);
        int maxHeight = (int)(context.getResources().getDisplayMetrics().heightPixels * 0.55f);

        float ratio = (float) imgW / (float) imgH;

        int targetWidth = maxWidth;
        int targetHeight = Math.round(targetWidth / ratio);

        if (targetHeight < minHeight) {
            int widthAtMinHeight = Math.round(minHeight * ratio);
            if (widthAtMinHeight <= maxWidth) {
                targetWidth = widthAtMinHeight;
                targetHeight = minHeight;
            } else {
                targetWidth = maxWidth;
                targetHeight = Math.round(targetWidth / ratio);
            }
        } else if (targetHeight > maxHeight) {
            int widthAtMaxHeight = Math.round(maxHeight * ratio);
            if (widthAtMaxHeight <= maxWidth) {
                targetWidth = widthAtMaxHeight;
                targetHeight = maxHeight;
            } else {
                targetWidth = maxWidth;
                targetHeight = Math.round(targetWidth / ratio);
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
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, widthPx, heightPx, radiusPx);
            }
        });
        iv.setClipToOutline(true);
    }

    @interface NonNull {}
}
