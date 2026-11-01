package com.silu.minigram;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import java.text.*;
import java.util.*;

import android.text.util.Linkify;
import android.text.method.LinkMovementMethod;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdView;
import com.google.android.gms.ads.nativead.MediaView;

public class ChannelPostAdapter extends BaseAdapter {
	
	// ─── Listener ────────────────────────────────────────────────────────────
	public interface PostListener {
		void onImageClick(String postId, String imageUrl);
		void onItemClick(String postId);
		void onItemLongClick(String postId);
		void onReplyClick(String postId);
		void onLikeToggle(String postId, boolean currentlyLiked);
		void onRetryUpload(String tempId);
	}
	
	// ─── State ───────────────────────────────────────────────────────────────
	private final Context                            context;
	private final ArrayList<HashMap<String, Object>> postList;
	private final ListView                           listView;
	private final String                             myUid;
	private final String                             channelId;
	private       PostListener                       listener;
	// Phase 3 — MyProfileActivity's "View" action scrolls here and briefly
	// highlights the target post, same treatment ChatActivity already had
	// for chat messages.
	private       String                             highlightedPostId = null;
	public void setHighlightedPostId(String id) { this.highlightedPostId = id; }
	/** Multi-select state, mirroring ChatActivity's message selection so
	 *  long-pressing a post feels identical in both screens. */
	private java.util.Set<String> selectedPosts = new java.util.HashSet<>();
	public void setSelectedPosts(java.util.Set<String> sel) { this.selectedPosts = sel; }
	
	// ─── Sent / Received bubble styling (Telegram-style) ───────────────────────
	private static final int TYPE_SENT     = 0; // my own post → right side, green
	private static final int TYPE_RECEIVED = 1; // someone else's post → left side, white
	private static final int TYPE_NATIVE_AD = 2; // in-feed native ad — channels only

	// ─── Native ads (channels only, Pro users never see any) ──────────────────
	private NativeAdManager nativeAdManager;
	public void setNativeAdManager(NativeAdManager manager) { this.nativeAdManager = manager; }

	private int _adInterval() {
		return (nativeAdManager != null && !nativeAdManager.isPro())
			? AdConfig.NATIVE_AD_POST_INTERVAL : 0;
	}

	/** How many ad slots are actually ready to show right now (contiguous from slot 0 — no gaps). */
	private int _readyAdSlotCount(int realCount, int interval) {
		if (interval <= 0) return 0;
		int maxSlots = realCount / interval;
		int ready = 0;
		for (int k = 0; k < maxSlots; k++) {
			if (nativeAdManager.getAdForSlot(k) != null) ready++;
			else break; // keep slots contiguous so positions never have gaps
		}
		return ready;
	}

	/**
	 * Maps a DISPLAY position (what the ListView sees, real posts + ad slots
	 * interleaved) back to either "this is ad slot #N" or "this is real post
	 * index #N" — result[0] = 1 for ad / 0 for real post, result[1] = the id.
	 *
	 * BUG FIXED (channel posts not appearing / big empty gap): this used to
	 * treat EVERY interval boundary as an ad slot, without checking how many
	 * ad slots are actually ready. getCount() only counts READY slots, so the
	 * two disagreed. Concrete case: 9 posts, 0 ads ready → getCount() = 9,
	 * but this mapped display position 8 to "ad slot 0" — a slot with no ad —
	 * so that row rendered as an empty gap, AND real post #8 (the newest one,
	 * the "Hello" that was just sent) was never mapped to any row at all, so
	 * it simply never showed. Posting again shifted everything by one, which
	 * is why the previous post would suddenly appear while the newest one
	 * stayed invisible.
	 *
	 * Now takes readySlots into account: a boundary is only an ad slot if
	 * that slot's ad is actually ready; otherwise it's a normal post row.
	 */
	private int[] _resolvePosition(int displayPos) {
		int interval = _adInterval();
		if (interval <= 0) return new int[]{0, displayPos};
		int readySlots = _readyAdSlotCount(postList.size(), interval);
		if (readySlots <= 0) return new int[]{0, displayPos};

		int groupSize = interval + 1; // N real posts + 1 ad
		if ((displayPos + 1) % groupSize == 0) {
			int slotId = (displayPos + 1) / groupSize - 1;
			// Only a real ad row if this slot actually has an ad ready.
			if (slotId < readySlots) return new int[]{1, slotId};
		}
		int adsBefore = Math.min(displayPos / groupSize, readySlots);
		return new int[]{0, displayPos - adsBefore};
	}
	
	// Optional fallback: if a post map has no "sender_uid" key (e.g. a channel
	// where every post simply belongs to the one channel owner/admin and isn't
	// tagged per-post), set this once via setChannelOwnerUid() right after
	// creating the adapter and every post will be judged against it instead.
	private String channelOwnerUidOverride = null;
	public void setChannelOwnerUid(String ownerUid) { this.channelOwnerUidOverride = ownerUid; }
	
	// ─── Constructor ─────────────────────────────────────────────────────────
	public ChannelPostAdapter(Context context,
	ArrayList<HashMap<String, Object>> postList,
	ListView listView,
	String myUid,
	String channelId) {
		this.context   = context;
		this.postList  = postList;
		this.listView  = listView;
		this.myUid     = myUid;
		this.channelId = channelId;
	}
	
	public void setListener(PostListener l) { this.listener = l; }

	// ─── Voice note playback (ListView-based; single shared MediaPlayer) ───────
	// Only one voice note plays at a time across the whole list — same as
	// Telegram/WhatsApp. playingVoicePostId tracks WHICH post owns the
	// currently active MediaPlayer so getView() can restore the correct
	// play/pause icon + progress when a bound row scrolls back into view.
	private MediaPlayer voicePlayer        = null;
	private String      playingVoicePostId = null;
	private final Handler voiceProgressHandler = new Handler(Looper.getMainLooper());
	private Runnable      voiceProgressRunnable;

	/**
	 * Called from ChannelActivity.onPause() to stop any playing voice note
	 * when the screen backgrounds.
	 */
	public void stopVoicePlayback() {
		if (voicePlayer != null) {
			try { if (voicePlayer.isPlaying()) voicePlayer.stop(); } catch (Exception ignored) {}
			voicePlayer.release();
			voicePlayer = null;
		}
		voiceProgressHandler.removeCallbacksAndMessages(null);
		playingVoicePostId = null;
	}
	
	// ─── Position-stable scroll helper ───────────────────────────────────────
	public void refreshWithoutScroll() {
		int  index = listView.getFirstVisiblePosition();
		View v     = listView.getChildAt(0);
		int  top   = (v == null) ? 0 : (v.getTop() - listView.getPaddingTop());
		int  mode  = listView.getTranscriptMode();
		listView.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
		super.notifyDataSetChanged();
		listView.post(() -> {
			listView.setSelectionFromTop(index, top);
			listView.setTranscriptMode(mode);
		});
	}
	
	// ─── BaseAdapter ─────────────────────────────────────────────────────────
	@Override public int getCount() {
		int real = postList.size();
		int interval = _adInterval();
		if (interval <= 0) return real;
		return real + _readyAdSlotCount(real, interval);
	}

	@Override public Object getItem(int position) {
		int[] r = _resolvePosition(position);
		return r[0] == 1 ? null : postList.get(r[1]); // null for ad slots — no post data
	}

	@Override public long getItemId(int p) { return p; }

	// Three distinct row layouts (sent / received / native ad) so ListView
	// recycles each type into its own pool — required whenever getView()
	// inflates different layout resources depending on the row's data.
	@Override public int getViewTypeCount() { return 3; }
	@Override public int getItemViewType(int position) {
		int[] r = _resolvePosition(position);
		if (r[0] == 1) return TYPE_NATIVE_AD;
		return _isSentByMe(postList.get(r[1])) ? TYPE_SENT : TYPE_RECEIVED;
	}
	
	/**
	 * true  → this post is mine → sent-style bubble (right, green)
	 * false → someone else's post → received-style bubble (left, white)
	 *
	 * Looks for a "sender_uid" key on the post first (works for channels
	 * where multiple people/admins can post). If that key isn't present,
	 * falls back to channelOwnerUidOverride (works for single-admin
	 * broadcast channels — see setChannelOwnerUid() above). If neither is
	 * available, defaults to "sent" so nothing breaks/looks empty.
	 */
	private boolean _isSentByMe(HashMap<String, Object> post) {
		String senderUid = (String) post.get("sender_uid");
		if (senderUid == null || senderUid.isEmpty()) senderUid = channelOwnerUidOverride;
		if (senderUid == null || myUid == null) return true;
		return senderUid.equals(myUid);
	}
	
	/**
	 * Converts a DATA index (index into postList) into the DISPLAY position
	 * the ListView actually uses, accounting for any ad rows interleaved
	 * before it. Callers that scroll to a specific post (e.g.
	 * ChannelActivity.scrollToPost) must use this — passing a raw data index
	 * to setSelection() lands on the wrong row once ads are present.
	 */
	public int displayPositionForDataIndex(int dataIndex) {
		int interval = _adInterval();
		if (interval <= 0) return dataIndex;
		int readySlots = _readyAdSlotCount(postList.size(), interval);
		if (readySlots <= 0) return dataIndex;
		int groupSize = interval + 1;
		int adsBefore = Math.min(dataIndex / interval, readySlots);
		return dataIndex + adsBefore;
	}

	// ─── getView ─────────────────────────────────────────────────────────────
	@Override
	public View getView(int pos, View cv, ViewGroup parent) {
		int[] resolved = _resolvePosition(pos);
		if (resolved[0] == 1) {
			return _getNativeAdView(resolved[1], cv, parent);
		}
		HashMap<String, Object> post = postList.get(resolved[1]);
		boolean sentByMe = _isSentByMe(post);
		
		if (cv == null) {
			int layoutRes = sentByMe ? R.layout.item_channel_post_sent : R.layout.item_channel_post_received;
			cv = LayoutInflater.from(context).inflate(layoutRes, parent, false);
		}
		
		final String  postId      = (String)  post.get("postId");
		if (postId != null) {
			// Selection wins over the transient jump-highlight — a post the
			// user is actively holding selected should always read as
			// selected. Same colours as the chat screen for consistency.
			if (selectedPosts != null && selectedPosts.contains(postId)) {
				// Drawable (not a flat colour) so adjacent selected posts
				// keep a visible boundary between them.
				cv.setBackgroundResource(R.drawable.bg_row_selected);
			} else {
				cv.setBackgroundColor(postId.equals(highlightedPostId)
					? Color.parseColor("#401A73E8") : Color.TRANSPARENT);
			}
		}
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
		
		// ── Issue 4: view count ───────────────────────────────────────────────
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
		
		String voiceUrl = (String) post.get("voice_url");
		// "duration" comes back as Long from Firebase but as Integer on the
		// optimistic temp post created before upload — handle both safely.
		long durationSec = 0;
		Object durObj = post.get("duration");
		if (durObj instanceof Long) durationSec = (Long) durObj;
		else if (durObj instanceof Integer) durationSec = (Integer) durObj;
		
		if (text     == null) text     = "";
		if (imgUrl   == null) imgUrl   = "";
		if (caption  == null) caption  = "";
		if (voiceUrl == null) voiceUrl = "";
		if (postId   == null) return cv;
		
		// ── Bind views ────────────────────────────────────────────────────────
		LinearLayout bubble       = cv.findViewById(R.id.bubble_container);
		LinearLayout replyBar     = cv.findViewById(R.id.layout_reply_quote);
		TextView     txtReply     = cv.findViewById(R.id.txt_reply_quote);
		ImageView    imgMsg       = cv.findViewById(R.id.img_msg);
		ProgressBar  imgLoading   = cv.findViewById(R.id.img_loading);
		TextView     txtTimeImg   = cv.findViewById(R.id.txt_time_image);
		TextView     txtCaption   = cv.findViewById(R.id.txt_caption);
		TextView     txtMsg       = cv.findViewById(R.id.txt_msg);
		TextView     txtEdited    = cv.findViewById(R.id.txt_edited_label);
		TextView     txtPinned    = cv.findViewById(R.id.txt_pinned_label);
		TextView     txtTimeMsg   = cv.findViewById(R.id.txt_time_msg);
		TextView     btnLike      = cv.findViewById(R.id.btn_like);
		TextView     txtLikeCount = cv.findViewById(R.id.txt_like_count);
		TextView     btnReply     = cv.findViewById(R.id.btn_reply);
		TextView     txtReplyCnt  = cv.findViewById(R.id.txt_reply_count);
		// Issue 4: view count view
		TextView     txtViewCount = cv.findViewById(R.id.txt_view_count);
		TextView     txtReactions = cv.findViewById(R.id.txt_reactions);
		LinearLayout layoutVoiceMsg   = cv.findViewById(R.id.layout_voice_msg);
		TextView     btnVoicePlay     = cv.findViewById(R.id.btn_voice_play);
		SeekBar      seekVoice        = cv.findViewById(R.id.seek_voice);
		TextView     txtVoiceDuration = cv.findViewById(R.id.txt_voice_duration);
		LinearLayout layoutLinkPreview   = cv.findViewById(R.id.layout_link_preview);
		ImageView    imgLinkPreview      = cv.findViewById(R.id.img_link_preview);
		TextView     txtLinkPreviewSite  = cv.findViewById(R.id.txt_link_preview_site);
		TextView     txtLinkPreviewTitle = cv.findViewById(R.id.txt_link_preview_title);
		TextView     txtLinkPreviewDesc  = cv.findViewById(R.id.txt_link_preview_desc);
		
		final String finalPostId = postId;
		final String finalImgUrl = imgUrl;
		
		// ── Deleted post ─────────────────────────────────────────────────────
		if (Boolean.TRUE.equals(deletedAll)) {
			_hideMedia(imgMsg, imgLoading, txtTimeImg, txtCaption);
			if (layoutVoiceMsg != null) layoutVoiceMsg.setVisibility(View.GONE);
			if (postId.equals(playingVoicePostId)) stopVoicePlayback();
			if (layoutLinkPreview != null) layoutLinkPreview.setVisibility(View.GONE);
			if (replyBar  != null) replyBar.setVisibility(View.GONE);
			if (btnLike   != null) btnLike.setVisibility(View.GONE);
			if (txtLikeCount != null) txtLikeCount.setVisibility(View.GONE);
			if (btnReply  != null) btnReply.setVisibility(View.GONE);
			if (txtReplyCnt  != null) txtReplyCnt.setVisibility(View.GONE);
			if (txtViewCount != null) txtViewCount.setVisibility(View.GONE);
			if (txtReactions != null) txtReactions.setVisibility(View.GONE);
			if (txtMsg    != null) {
				txtMsg.setVisibility(View.VISIBLE);
				txtMsg.setText("🚫 This post was deleted");
				txtMsg.setTextColor(Ink.c("#9E9E9E"));
				txtMsg.setTypeface(null, Typeface.ITALIC);
			}
			if (txtTimeMsg != null && ts != null) txtTimeMsg.setText(formatTime(ts));
			cv.setOnClickListener(null);
			cv.setOnLongClickListener(null);
			return cv;
		}
		
		// ── Timestamp ────────────────────────────────────────────────────────
		if (txtTimeMsg != null && ts != null) txtTimeMsg.setText(formatTime(ts));
		if (txtTimeImg != null && ts != null) txtTimeImg.setText(formatTime(ts));
		
		// ── Reply quote bar ───────────────────────────────────────────────────
		if (replyBar != null) {
			if (replyId != null && !replyId.isEmpty()) {
				replyBar.setVisibility(View.VISIBLE);
				if (txtReply != null) {
					SpannableStringBuilder sb = new SpannableStringBuilder();
					String label = replySender != null ? replySender : "Channel";
					sb.append(label).append("\n");
					int end = sb.length();
					sb.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
					sb.setSpan(new ForegroundColorSpan(Ink.c("#1B5E20")), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
					sb.append(replyPrev != null ? replyPrev : "");
					txtReply.setMaxLines(2);
					txtReply.setText(sb);
				}
			} else {
				replyBar.setVisibility(View.GONE);
			}
		}
		
		// ── IMAGE vs VOICE vs TEXT ───────────────────────────────────────────
		boolean isImagePost = "image".equals(postType) && (hasLocalUri || !imgUrl.isEmpty());
		boolean isVoicePost = "voice".equals(postType);
		
		// Bubble always hugs its content (Telegram-style) — for images this
		// means it tightly wraps the image's own computed width (landscape
		// images naturally reach the max width; portrait images stay
		// narrower, and the bubble shrinks with them instead of leaving a
		// gap of leftover background on the side).
		if (bubble != null) {
			ViewGroup.LayoutParams lp = bubble.getLayoutParams();
			lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
			bubble.setLayoutParams(lp);
		}
		
		if (isImagePost) {
			if (txtMsg != null) txtMsg.setVisibility(View.GONE);
			if (layoutVoiceMsg != null) layoutVoiceMsg.setVisibility(View.GONE);
			if (layoutLinkPreview != null) layoutLinkPreview.setVisibility(View.GONE);
			if (imgMsg != null) {
				imgMsg.setVisibility(View.VISIBLE);
				if (txtTimeImg != null) txtTimeImg.setVisibility(View.VISIBLE);
				
				// scaleType FIT_XY: image fills the exact computed px box without
				// distortion because _adjustImageViewSize already preserves ratio.
				imgMsg.setScaleType(ImageView.ScaleType.FIT_XY);
				
				if (hasLocalUri) {
					// ── Local Image Sizing (Instant Upload) ───────────────────
					int origW = 100;
					int origH = 100;
					try {
						android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
						options.inJustDecodeBounds = true;
						android.graphics.BitmapFactory.decodeStream(
						context.getContentResolver().openInputStream(Uri.parse(localUri)), null, options);
						origW = options.outWidth;
						origH = options.outHeight;
					} catch (Exception e) {
						// Fallback to 100, 100 already set
					}
					int[] localSize = _adjustImageViewSize(imgMsg, origW, origH, bubble);
					
					// Recycling-safety: cancel any stale in-flight request for this
					// view and tag it, mirroring the remote-image branch below.
					Glide.with(context).clear(imgMsg);
					imgMsg.setTag(localUri);
					final ImageView _localIv  = imgMsg;
					final String    _localUri = localUri;
					
					// override(w, h) makes Glide decode straight to the on-screen
					// size instead of the full original resolution — this is why
					// the preview now appears the instant the image is picked,
					// rather than lagging behind (or only appearing after) upload.
					Glide.with(context)
					.load(Uri.parse(localUri))
					.apply(new RequestOptions()
					.placeholder(R.drawable.img_placeholder)
					.override(localSize[0], localSize[1]))
					.into(new CustomTarget<Drawable>() {
						@Override public void onResourceReady(@NonNull Drawable r, Transition<? super Drawable> t) {
							if (_localUri.equals(_localIv.getTag())) {
								_localIv.setScaleType(ImageView.ScaleType.FIT_XY); // ✅ Force scaleType to prevent stale recycling
								_localIv.setImageDrawable(r);
							}
						}
						@Override public void onLoadCleared(Drawable p) { _localIv.setImageDrawable(p); }
					});
					
					if ("uploading".equals(uploadState)) {
						if (imgLoading != null) { imgLoading.setVisibility(View.VISIBLE); imgLoading.setIndeterminate(true); }
						imgMsg.setAlpha(0.75f);
						imgMsg.setColorFilter(null);
						imgMsg.setOnClickListener(null);
					} else if ("failed".equals(uploadState)) {
						if (imgLoading != null) imgLoading.setVisibility(View.GONE);
						imgMsg.setAlpha(0.55f);
						imgMsg.setColorFilter(Color.parseColor("#80FF4444"), android.graphics.PorterDuff.Mode.SRC_ATOP);
						if (txtCaption != null) {
							txtCaption.setVisibility(View.VISIBLE);
							txtCaption.setText("⚠  Upload failed  ·  Tap to retry");
							txtCaption.setTextColor(Ink.c("#FF6B6B"));
						}
						imgMsg.setOnClickListener(v -> { if (listener != null) listener.onRetryUpload(finalPostId); });
					}
					
				} else {
					// ── Remote URL (Server Image) ─────────────────────────────────
					imgMsg.setAlpha(1f);
					imgMsg.setColorFilter(null);
					if (imgLoading != null) imgLoading.setVisibility(View.VISIBLE);
					
					Glide.with(context).clear(imgMsg);
					imgMsg.setImageDrawable(null);
					imgMsg.setTag(imgUrl);
					
					final ProgressBar _pr = imgLoading;
					final ImageView   _iv = imgMsg;
					
					Glide.with(context)
					.load(imgUrl)
					.apply(new RequestOptions()
					.placeholder(R.drawable.img_placeholder)
					.error(R.drawable.img_placeholder))
					.thumbnail(0.2f)
					.diskCacheStrategy(DiskCacheStrategy.ALL)
					.into(new CustomTarget<Drawable>() {
						@Override
						public void onResourceReady(@NonNull Drawable r, com.bumptech.glide.request.transition.Transition<? super Drawable> t) {
							if (finalImgUrl.equals(_iv.getTag())) {
								int intrinsicWidth  = r.getIntrinsicWidth();
								int intrinsicHeight = r.getIntrinsicHeight();
								_adjustImageViewSize(_iv, intrinsicWidth, intrinsicHeight, bubble);
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
					
					imgMsg.setOnClickListener(v -> {
						if (listener != null) listener.onImageClick(finalPostId, finalImgUrl);
					});
				}
				
				// Caption Logic
				if (txtCaption != null && !"failed".equals(uploadState)) {
					boolean hasCaption = !caption.isEmpty();
					txtCaption.setVisibility(hasCaption || Boolean.TRUE.equals(isForwarded) ? View.VISIBLE : View.GONE);
					if (hasCaption) {
						txtCaption.setText(caption);
						txtCaption.setTextColor(Ink.c("#1A1A1A"));
						SmartLinkMovementMethod.apply(txtCaption);
						if (Boolean.TRUE.equals(isForwarded)) _prependForwarded(txtCaption);
					}
				}
			}
		} else if (isVoicePost) {
			// ── Voice post ───────────────────────────────────────────────────
			_hideMedia(imgMsg, imgLoading, txtTimeImg, txtCaption);
			if (txtMsg != null) txtMsg.setVisibility(View.GONE);
			if (layoutLinkPreview != null) layoutLinkPreview.setVisibility(View.GONE);
			if (layoutVoiceMsg != null) layoutVoiceMsg.setVisibility(View.VISIBLE);

			final String finalVoiceUrl   = voiceUrl;
			final long   finalDurationSec = durationSec;

			if ("uploading".equals(uploadState)) {
				if (btnVoicePlay != null) {
					btnVoicePlay.setText("hourglass_empty");
					btnVoicePlay.setAlpha(0.6f);
					btnVoicePlay.setOnClickListener(null);
				}
				if (seekVoice != null) { seekVoice.setEnabled(false); seekVoice.setProgress(0); }
				if (txtVoiceDuration != null) {
					txtVoiceDuration.setTextColor(Ink.c("#757575"));
					txtVoiceDuration.setText("Sending…");
				}
			} else if ("failed".equals(uploadState)) {
				if (btnVoicePlay != null) {
					btnVoicePlay.setText("refresh");
					btnVoicePlay.setAlpha(1f);
					btnVoicePlay.setOnClickListener(v -> { if (listener != null) listener.onRetryUpload(finalPostId); });
				}
				if (seekVoice != null) { seekVoice.setEnabled(false); seekVoice.setProgress(0); }
				if (txtVoiceDuration != null) {
					txtVoiceDuration.setTextColor(Ink.c("#FF6B6B"));
					txtVoiceDuration.setText("Failed · tap to retry");
				}
			} else {
				if (btnVoicePlay != null) btnVoicePlay.setAlpha(1f);
				if (seekVoice != null) seekVoice.setEnabled(true);
				if (txtVoiceDuration != null) txtVoiceDuration.setTextColor(Ink.c("#757575"));
				_bindVoicePlayback(finalPostId, finalVoiceUrl, finalDurationSec, btnVoicePlay, seekVoice, txtVoiceDuration);
				if (layoutVoiceMsg != null) {
					final TextView _btnPlayRef = btnVoicePlay;
					layoutVoiceMsg.setOnClickListener(v -> { if (_btnPlayRef != null) _btnPlayRef.performClick(); });
				}
			}
		} else {
			// ── Text post ─────────────────────────────────────────────────────
			_hideMedia(imgMsg, imgLoading, txtTimeImg, txtCaption);
			if (layoutVoiceMsg != null) layoutVoiceMsg.setVisibility(View.GONE);
			if (txtMsg != null) {
				txtMsg.setVisibility(View.VISIBLE);
				MsgRenderer.render(context, txtMsg, text);
				SmartLinkMovementMethod.apply(txtMsg);
				if (Boolean.TRUE.equals(isForwarded)) _prependForwarded(txtMsg);
			}
			_bindLinkPreview(layoutLinkPreview, imgLinkPreview, txtLinkPreviewSite,
				txtLinkPreviewTitle, txtLinkPreviewDesc, finalPostId, text, post);
		}
		
		// ── Labels ────────────────────────────────────────────────────────────
		if (txtEdited != null)
		txtEdited.setVisibility(Boolean.TRUE.equals(isEdited) ? View.VISIBLE : View.GONE);
		if (txtPinned != null)
		txtPinned.setVisibility(Boolean.TRUE.equals(isPinned) ? View.VISIBLE : View.GONE);

		// ── Reactions ─────────────────────────────────────────────────────────
		// ChannelActivity has always READ reactions into the post map and its
		// onReact() handler has always WRITTEN them to Firebase — but nothing
		// ever rendered them, so reacting appeared to do nothing at all. This
		// is the missing display half (same chip rendering as chat messages).
		if (txtReactions != null) {
			Object rcObj = post.get("reaction_counts");
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
					// Tapping the chip reopens the action sheet so the user can
					// change or remove their reaction.
					txtReactions.setOnClickListener(v -> {
						if (listener != null && !finalPostId.startsWith("temp_"))
						listener.onItemClick(finalPostId);
					});
				}
			} else {
				txtReactions.setVisibility(View.GONE);
			}
		}
		
		// ── Like button ───────────────────────────────────────────────────────
		boolean liked     = likes.containsKey(myUid);
		int     likeCount = likes.size();
		if (btnLike != null) {
			btnLike.setVisibility(View.VISIBLE);
			btnLike.setText(liked ? "favorite" : "favorite_border");
			btnLike.setTextColor(liked ? Ink.c("#E53935") : Ink.c("#9E9E9E"));
			final boolean _liked = liked;
			btnLike.setOnClickListener(v -> {
				if (listener != null && !finalPostId.startsWith("temp_"))
				listener.onLikeToggle(finalPostId, _liked);
			});
		}
		if (txtLikeCount != null) {
			txtLikeCount.setVisibility(View.VISIBLE);
			// Always show the number, including 0 — previously a 0 count
			// rendered as an empty string, so the counter simply looked
			// missing next to the heart icon.
			txtLikeCount.setText(_formatCount(likeCount));
		}
		
		// ── Reply / Comments button ───────────────────────────────────────────
		if (btnReply != null) {
			btnReply.setVisibility(View.VISIBLE);
			btnReply.setOnClickListener(v -> {
				if (listener != null && !finalPostId.startsWith("temp_"))
				listener.onReplyClick(finalPostId);
			});
		}
		if (txtReplyCnt != null) {
			txtReplyCnt.setVisibility(View.VISIBLE);
			long rc = replyCount != null ? replyCount : 0L;
			txtReplyCnt.setText(_formatCount(rc));
		}
		
		// ── Issue 4: View count display ───────────────────────────────────────
		if (txtViewCount != null) {
			if (postId.startsWith("temp_")) {
				// Still uploading — no meaningful count to show yet.
				txtViewCount.setVisibility(View.GONE);
			} else {
				txtViewCount.setVisibility(View.VISIBLE);
				txtViewCount.setText(_formatCount(viewCount != null ? viewCount : 0L));
			}
		}
		
		// ── Click + Long-press → action sheet ────────────────────────────────
		boolean isTemp = postId.startsWith("temp_");
		if (!isTemp) {
			View target = bubble != null ? bubble : cv;
			target.setOnClickListener(v -> {
				if (listener != null) listener.onItemClick(finalPostId);
			});
			target.setOnLongClickListener(v -> {
				if (listener != null) listener.onItemLongClick(finalPostId);
				return true;
			});
		} else {
			if (bubble != null) { bubble.setOnClickListener(null); bubble.setOnLongClickListener(null); }
			cv.setOnClickListener(null);
			cv.setOnLongClickListener(null);
		}
		
		return cv;
	}
	
	// ─── Native ad rendering (channels only) ───────────────────────────────────
	private View _getNativeAdView(int slotId, View cv, ViewGroup parent) {
		if (cv == null || !(cv instanceof NativeAdView)) {
			cv = LayoutInflater.from(context).inflate(R.layout.item_channel_native_ad, parent, false);
		}
		NativeAdView adView = (NativeAdView) cv;

		NativeAd nativeAd = nativeAdManager != null ? nativeAdManager.getAdForSlot(slotId) : null;
		if (nativeAd == null) {
			// Not loaded / not ready. Note that setVisibility(GONE) alone is
			// NOT enough on a ListView row: the row still occupies its
			// measured height, which is exactly what produced the big blank
			// gap in the feed. Collapse the height to 0 as well so the row
			// takes no space at all. (With the _resolvePosition fix above
			// this branch should now be unreachable in practice, but keeping
			// it correct means a future timing edge can never reintroduce a
			// blank gap.)
			adView.setVisibility(View.GONE);
			ViewGroup.LayoutParams lp = adView.getLayoutParams();
			if (lp == null) {
				lp = new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
			} else {
				lp.height = 0;
			}
			adView.setLayoutParams(lp);
			return adView;
		}
		adView.setVisibility(View.VISIBLE);
		// Restore height in case this recycled view was previously collapsed
		// by the branch above.
		ViewGroup.LayoutParams lpVisible = adView.getLayoutParams();
		if (lpVisible != null && lpVisible.height == 0) {
			lpVisible.height = ViewGroup.LayoutParams.WRAP_CONTENT;
			adView.setLayoutParams(lpVisible);
		}

		ImageView appIcon    = adView.findViewById(R.id.ad_app_icon);
		TextView  headline   = adView.findViewById(R.id.ad_headline);
		TextView  advertiser = adView.findViewById(R.id.ad_advertiser);
		MediaView media      = adView.findViewById(R.id.ad_media);
		TextView  body       = adView.findViewById(R.id.ad_body);
		TextView  cta        = adView.findViewById(R.id.ad_cta);
		com.google.android.gms.ads.nativead.AdChoicesView adChoicesContainer = adView.findViewById(R.id.ad_choices_container);

		adView.setHeadlineView(headline);
		adView.setMediaView(media);
		adView.setBodyView(body);
		adView.setCallToActionView(cta);
		adView.setIconView(appIcon);
		adView.setAdvertiserView(advertiser);
		adView.setAdChoicesView(adChoicesContainer); // AdMob draws the required icon inside this — never skip

		headline.setText(nativeAd.getHeadline());

		if (nativeAd.getBody() != null) {
			body.setText(nativeAd.getBody());
			body.setVisibility(View.VISIBLE);
		} else {
			body.setVisibility(View.INVISIBLE);
		}

		if (nativeAd.getCallToAction() != null) {
			cta.setText(nativeAd.getCallToAction());
			cta.setVisibility(View.VISIBLE);
		} else {
			cta.setVisibility(View.INVISIBLE);
		}

		if (nativeAd.getIcon() != null) {
			appIcon.setImageDrawable(nativeAd.getIcon().getDrawable());
			appIcon.setVisibility(View.VISIBLE);
		} else {
			appIcon.setVisibility(View.GONE);
		}

		if (nativeAd.getAdvertiser() != null) {
			advertiser.setText(nativeAd.getAdvertiser());
			advertiser.setVisibility(View.VISIBLE);
		} else {
			advertiser.setVisibility(View.GONE);
		}

		// This MUST be the last call — it finalizes all the view bindings above.
		adView.setNativeAd(nativeAd);

		return adView;
	}
	
	// ─── Helpers ─────────────────────────────────────────────────────────────
	
	// Exact horizontal insets between the ImageView and the screen edge:
	// root padding (56dp left + 12dp right) + img_msg's own margins (5dp + 5dp)
	private static final int H_INSET_DP = 56 + 12 + 5 + 5;
	// Uniform corner radius applied to every image, regardless of the
	// (asymmetric, "tail"-shaped) bubble background — guarantees all 4
	// corners are always rounded the same, like Telegram.
	private static final int IMG_CORNER_RADIUS_DP = 12;
	
	/**
	 * Real available width inside the ListView (its measured width minus its
	 * own left/right padding), instead of the raw device screen width. This
	 * is what fixes the landscape-image right-margin bug — see comment above.
	 * Falls back to display width only if the list hasn't been measured yet
	 * (e.g. very first getView() call before the first layout pass).
	 */
	private int _getListContentWidth() {
		int w = listView.getWidth();
		if (w <= 0) {
			return context.getResources().getDisplayMetrics().widthPixels;
		}
		return w - listView.getPaddingLeft() - listView.getPaddingRight();
	}
	
	private int[] _adjustImageViewSize(ImageView iv, int imgW, int imgH, LinearLayout bubbleContainer) {
		if (imgW <= 0 || imgH <= 0) { imgW = 100; imgH = 100; }
		
		// ✅ REAL ROOT-CAUSE FIX:
		// Raw device screen width (displayMetrics) does NOT know about any
		// horizontal padding applied on the ListView itself (e.g. a chat-style
		// list often has paddingLeft/Right + clipToPadding="false" for breathing
		// room). That padding was never subtracted here, so the computed
		// maxWidth came out WIDER than the space actually available inside the
		// list. Portrait images never hit maxWidth (they're capped by maxHeight
		// first) so the bug stayed invisible for them — but landscape images
		// always try to use the full maxWidth, so they overflowed past the
		// list's real right edge and ate into the right margin.
		// Fix: measure the ListView's own content width (its measured width
		// minus its own left/right padding) instead of the raw display width.
		int screenWidth = _getListContentWidth();
		
		int paddingLeft = bubbleContainer.getPaddingLeft();
		int paddingRight = bubbleContainer.getPaddingRight();
		
		// Calculate exact max width by subtracting:
		// 1. Root outer padding (56dp left + 12dp right)
		// 2. ImageView margins (5dp left + 5dp right)
		// 3. Bubble background drawable's internal padding (left + right)
		int maxWidth = screenWidth - dp(56) - dp(12) - dp(5) - dp(5) - paddingLeft - paddingRight;
		
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
		// Creates a fresh provider with the exact target bounds, guaranteeing
		// that the outline correctly matches the recycled view's new dimensions.
		iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
			@Override
			public void getOutline(View view, android.graphics.Outline outline) {
				outline.setRoundRect(0, 0, widthPx, heightPx, radiusPx);
			}
		});
		iv.setClipToOutline(true);
	}
	
	/**
	 * Shows a link-preview card under a text post if it contains a URL and a
	 * preview was fetchable. Guards against the row being recycled to a
	 * different post before the async fetch completes (tag check).
	 */
	private void _bindLinkPreview(LinearLayout layoutLinkPreview, ImageView imgLinkPreview,
	                               TextView txtSite, TextView txtTitle, TextView txtDesc,
	                               String postId, String text, HashMap<String, Object> post) {
		if (layoutLinkPreview == null) return;

		// Already embedded by the sender (composed with a live preview) —
		// render directly, no fetch needed.
		String embeddedTitle = (String) post.get("link_preview_title");
		String embeddedUrl   = (String) post.get("link_preview_url");
		if (embeddedUrl != null && !embeddedUrl.isEmpty()) {
			layoutLinkPreview.setTag(null);
			layoutLinkPreview.setVisibility(View.VISIBLE);
			String site = (String) post.get("link_preview_site");
			String desc = (String) post.get("link_preview_desc");
			String img  = (String) post.get("link_preview_image");
			txtSite.setText(site != null ? site : "");
			txtTitle.setText(embeddedTitle != null ? embeddedTitle : embeddedUrl);
			txtDesc.setText(desc != null ? desc : "");
			txtDesc.setVisibility(desc != null && !desc.isEmpty() ? View.VISIBLE : View.GONE);
			if (img != null && !img.isEmpty() && imgLinkPreview != null) {
				imgLinkPreview.setVisibility(View.VISIBLE);
				Glide.with(context).load(img)
					.diskCacheStrategy(DiskCacheStrategy.ALL)
					.into(imgLinkPreview);
			} else if (imgLinkPreview != null) {
				imgLinkPreview.setVisibility(View.GONE);
			}
			return;
		}

		// Fallback: older post from before embedded previews existed.
		String url = LinkPreviewHelper.extractFirstUrl(text);
		if (url == null) {
			layoutLinkPreview.setVisibility(View.GONE);
			layoutLinkPreview.setTag(null);
			return;
		}
		layoutLinkPreview.setVisibility(View.GONE);
		layoutLinkPreview.setTag(postId);
		LinkPreviewHelper.fetch(url, preview -> {
			if (!postId.equals(layoutLinkPreview.getTag())) return;
			if (preview == null) return;
			layoutLinkPreview.setVisibility(View.VISIBLE);
			txtSite.setText(preview.siteName != null ? preview.siteName : "");
			txtTitle.setText(preview.title != null ? preview.title : "");
			txtDesc.setText(preview.description != null ? preview.description : "");
			txtDesc.setVisibility(preview.description != null && !preview.description.isEmpty() ? View.VISIBLE : View.GONE);
			if (preview.imageUrl != null && !preview.imageUrl.isEmpty() && imgLinkPreview != null) {
				imgLinkPreview.setVisibility(View.VISIBLE);
				Glide.with(context).load(preview.imageUrl)
					.diskCacheStrategy(DiskCacheStrategy.ALL)
					.into(imgLinkPreview);
			} else if (imgLinkPreview != null) {
				imgLinkPreview.setVisibility(View.GONE);
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
	
	/** Issue 4: compact view count format (1.2K, 4.5M etc) */
	/** Compact count formatting shared by the like / comment / view counters. */
	private String _formatCount(long n) {
		if (n >= 1_000_000) return String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0);
		if (n >= 1_000)     return String.format(Locale.getDefault(), "%.1fK", n / 1_000.0);
		return String.valueOf(n);
	}
	
	private String formatTime(long ts) {
		return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(ts));
	}

	// ─── Voice note playback ────────────────────────────────────────────────

	/** mm:ss formatting for voice note duration / playback position. */
	private String _formatDuration(long totalSeconds) {
		if (totalSeconds < 0) totalSeconds = 0;
		long min = totalSeconds / 60;
		long sec = totalSeconds % 60;
		return min + ":" + (sec < 10 ? "0" : "") + sec;
	}

	/**
	 * Binds one row's voice UI (play/pause icon, seekbar, duration text +
	 * their listeners) to whatever the CURRENT playback state is for this
	 * postId. Called on every getView() bind for an uploaded voice post —
	 * cheap since it only touches 3 views, no network/disk work.
	 */
	private void _bindVoicePlayback(final String postId, final String voiceUrl, final long durationSec,
	final TextView btnPlay, final SeekBar seekBar, final TextView txtDuration) {

		boolean isThisOneActive  = postId.equals(playingVoicePostId) && voicePlayer != null;
		boolean isThisOnePlaying = isThisOneActive && voicePlayer.isPlaying();

		if (btnPlay != null) btnPlay.setText(isThisOnePlaying ? "pause" : "play_arrow");

		if (isThisOneActive) {
			if (seekBar != null) {
				int total = voicePlayer.getDuration();
				seekBar.setMax(total > 0 ? total : 1);
				seekBar.setProgress(voicePlayer.getCurrentPosition());
			}
			if (txtDuration != null) txtDuration.setText(_formatDuration(voicePlayer.getCurrentPosition() / 1000));
			if (isThisOnePlaying) _attachVoiceProgressUpdater(seekBar, txtDuration);
		} else {
			if (seekBar != null) { seekBar.setMax(100); seekBar.setProgress(0); }
			if (txtDuration != null) txtDuration.setText(_formatDuration(durationSec));
		}

		if (btnPlay != null) {
			btnPlay.setOnClickListener(v -> {
				if (voiceUrl == null || voiceUrl.isEmpty()) return;
				if (postId.equals(playingVoicePostId) && voicePlayer != null) {
					// Same post already loaded — just toggle pause/resume.
					if (voicePlayer.isPlaying()) {
						voicePlayer.pause();
						voiceProgressHandler.removeCallbacksAndMessages(null);
						btnPlay.setText("play_arrow");
					} else {
						voicePlayer.start();
						btnPlay.setText("pause");
						_attachVoiceProgressUpdater(seekBar, txtDuration);
					}
				} else {
					// Different post (or nothing) was active — switch to this one.
					_startVoicePlayback(postId, voiceUrl, durationSec, btnPlay, seekBar, txtDuration);
				}
			});
		}

		if (seekBar != null) {
			seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
					if (fromUser && postId.equals(playingVoicePostId) && voicePlayer != null) {
						voicePlayer.seekTo(progress);
						if (txtDuration != null) txtDuration.setText(_formatDuration(progress / 1000));
					}
				}
				@Override public void onStartTrackingTouch(SeekBar sb) {
					voiceProgressHandler.removeCallbacksAndMessages(null);
				}
				@Override public void onStopTrackingTouch(SeekBar sb) {
					if (postId.equals(playingVoicePostId) && voicePlayer != null && voicePlayer.isPlaying()) {
						_attachVoiceProgressUpdater(seekBar, txtDuration);
					}
				}
			});
		}
	}

	/** Stops whatever voice note was playing (if any) and starts this one, streaming from voiceUrl. */
	private void _startVoicePlayback(final String postId, final String voiceUrl, final long durationSec,
	final TextView btnPlay, final SeekBar seekBar, final TextView txtDuration) {

		if (voicePlayer != null) {
			try { voicePlayer.stop(); } catch (Exception ignored) {}
			voicePlayer.release();
			voicePlayer = null;
		}
		voiceProgressHandler.removeCallbacksAndMessages(null);
		playingVoicePostId = postId;
		if (btnPlay != null) btnPlay.setText("hourglass_empty"); // brief loading state while buffering

		voicePlayer = new MediaPlayer();
		try {
			voicePlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			voicePlayer.setDataSource(voiceUrl);
			voicePlayer.setOnPreparedListener(mp -> {
				// User may have tapped a different post while this was buffering.
				if (!postId.equals(playingVoicePostId)) return;
				mp.start();
				if (btnPlay != null) btnPlay.setText("pause");
				if (seekBar != null) {
					int total = mp.getDuration();
					seekBar.setMax(total > 0 ? total : 1);
				}
				_attachVoiceProgressUpdater(seekBar, txtDuration);
			});
			voicePlayer.setOnCompletionListener(mp -> {
				voiceProgressHandler.removeCallbacksAndMessages(null);
				if (btnPlay != null) btnPlay.setText("play_arrow");
				if (seekBar != null) seekBar.setProgress(0);
				if (txtDuration != null) txtDuration.setText(_formatDuration(durationSec));
				playingVoicePostId = null;
			});
			voicePlayer.setOnErrorListener((mp, what, extra) -> {
				voiceProgressHandler.removeCallbacksAndMessages(null);
				if (btnPlay != null) btnPlay.setText("play_arrow");
				playingVoicePostId = null;
				Toast.makeText(context, "Voice message play nahi ho payi", Toast.LENGTH_SHORT).show();
				return true;
			});
			voicePlayer.prepareAsync();
		} catch (Exception e) {
			if (btnPlay != null) btnPlay.setText("play_arrow");
			playingVoicePostId = null;
			Toast.makeText(context, "Voice message load nahi ho payi", Toast.LENGTH_SHORT).show();
		}
	}

	/** Polls MediaPlayer every 250ms while playing to drive the seekbar + duration text. */
	private void _attachVoiceProgressUpdater(final SeekBar seekBar, final TextView txtDuration) {
		voiceProgressHandler.removeCallbacksAndMessages(null);
		voiceProgressRunnable = new Runnable() {
			@Override public void run() {
				if (voicePlayer == null) return;
				try {
					if (voicePlayer.isPlaying()) {
						int cur = voicePlayer.getCurrentPosition();
						if (seekBar != null) seekBar.setProgress(cur);
						if (txtDuration != null) txtDuration.setText(_formatDuration(cur / 1000));
						voiceProgressHandler.postDelayed(this, 250);
					}
				} catch (Exception ignored) {}
			}
		};
		voiceProgressHandler.post(voiceProgressRunnable);
	}

	private int dp(int v) {
		// ✅ FIX: Math.round() use karo — Android ka XML dp→px conversion bhi
		// Math.round() use karta hai. (int) truncate karta hai jisse 1-6px ka
		// overflow hota hai certain densities pe (e.g. 2.5x, 2.75x).
		return Math.round(v * context.getResources().getDisplayMetrics().density);
	}
}