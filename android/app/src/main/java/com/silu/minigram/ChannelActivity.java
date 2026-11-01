package com.silu.minigram;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.io.*;
import java.text.*;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
* ChannelActivity — Telegram-style broadcast channel.
*
* FIXES APPLIED:
*  Issue 1  — Forward now includes both DM chats AND owned/subscribed channels
*  Issue 4  — Post view count tracked per user, written to Firebase once per session
*  Issue 5  — Real-time online subscriber presence (channel_presence node)
*  Issue 6  — Glide.preload() before temp removal for instant swap
*  Issue 7  — Real-time subscriber count listener; auto-heal on open
*  Issue 8  — Last-read timestamp saved; first load scrolls to first unread post
*/
public class ChannelActivity extends AppCompatActivity {
	
	public static String currentChannelId = null;
	
	// ─── State ───────────────────────────────────────────────────────────────
	private String  channelId, channelHandle, channelName;
	private String  channelBio, channelPhoto;
	private String  myUid;
	private boolean isOwner      = false;
	// Requirement: "if the channel owner has disabled forwarding, subscribers
	// must not be able to forward posts". No such setting existed anywhere in
	// the codebase before this — added here + in ChannelSettingsActivity
	// (new toggle) + channels/{id}/restrict_forwarding in the DB.
	private boolean restrictForwarding = false;
	// Requirement 2 — owner control over whether subscribers can reply to
	// posts. Default true (open) so existing channels aren't silently
	// locked down by a field that didn't exist in their data before.
	private boolean allowReplies = true;
	// Requirement 2 — full admin system. Owner can grant specific
	// subscribers posting rights via channel_admins/{channelId}/{uid}.
	// Admins can post; everything else (settings, pin, delete-moderation,
	// forwarding-restriction, reply-permission) stays owner-only — this
	// request was specifically about who can POST, not a full moderation
	// permission matrix.
	private boolean isAdmin = false;
	private final InterstitialAdManager exitInterstitialAd =
		InterstitialAdManager.forChannelExit();
	private boolean isMuted      = false;
	private boolean isSubscribed = false;
	private boolean isFirstLoad  = true;
	// Requirement 3 (Phase 3) — MyProfileActivity's Media/Links/Voice "View"
	// action lands here via the scroll_to_post_id extra. Same limitation as
	// ChatActivity's equivalent: only found if within the initial page.
	private String pendingScrollPostId = null;
	// Requirement 4 redesign — ChannelProfileActivity's Search quick-action.
	private boolean pendingOpenSearch = false;
	// Separate from isFirstLoad on purpose: isFirstLoad only flips once the
	// FIREBASE listener responds, but posts now appear instantly from the
	// local cache well before that. Gating pagination on isFirstLoad meant
	// scrolling up right after a cached open silently did nothing until the
	// network caught up — this flag flips as soon as posts are actually on
	// screen, from cache or network, whichever happens first.
	private boolean canPaginate  = false;
	private boolean isAtBottom   = true;
	/**
	 * Set while a jump-to-a-specific-post is in flight (from the Channel
	 * Profile's Media/Links/Voice tabs). posts is a LIVE listener, so it
	 * re-fires on any change — and its wasAtBottom branch would slam the
	 * list back to the newest post a second or two after we'd scrolled to
	 * the target. That race is exactly why the target appeared to open at a
	 * "random position" and then snap to the last item. While this is
	 * non-null, every OTHER auto-scroll stands down.
	 */
	private String activeScrollTargetId = null;
	/**
	 * The scroll lock above only lasts as long as the highlight (2.5s). But
	 * a jump-to-post can trigger pagination, and a live-listener fire that
	 * lands AFTER the lock releases would take the wasAtBottom branch and
	 * yank the list back to the newest post — which is exactly the "scrolls
	 * to the right post, then a moment later jumps back to the end" report.
	 * This holds auto-scroll off for a longer window, and is released early
	 * the moment the user scrolls themselves (see the touch-scroll handler).
	 */
	private long suppressAutoScrollUntilMs = 0L;
	// ── Pagination ────────────────────────────────────────────────────────
	private static final int POSTS_PAGE_SIZE = 10;
	private int postsPageSize = POSTS_PAGE_SIZE;
	private boolean isLoadingMorePosts = false;
	private boolean hasMorePosts = true; // becomes false once scroll-up returns fewer than requested (= reached the beginning)
	private TextView newPostBubble;
	private int newPostCount = 0;
	
	// Reply-mode state
	private String replyToId   = null;
	private String replyPreview= null;
	
	// ── Issue 5: Online presence ──────────────────────────────────────────────
	private long              subscriberCount = 0;
	private long              onlineCount     = 0;
	private DatabaseReference presenceRef;
	private ValueEventListener presenceListener;
	
	// ── Issue 4: View tracking ───────────────────────────────────────────────
	private final Set<String> viewedThisSession = new HashSet<>();
	private final Set<String> migratedViewPosts = new HashSet<>();
	
	// ── Issue 8: Last-read tracking ──────────────────────────────────────────
	private long lastReadTimestamp = 0;
	
	private FirebaseAuth      auth;
	private FirebaseDatabase  database;
	private DatabaseReference postsRef, channelRef;
	private ValueEventListener postsListener;
	
	// accessToken / projectId fields removed — no longer needed client-side.
	
	// Instant-upload retry map
	private final HashMap<String, Uri> pendingRetryUris = new HashMap<>();
	private final HashMap<String, File> pendingRetryVoiceFiles = new HashMap<>();
	private final UploadRetryQueue retryQueue = new UploadRetryQueue();
	private ConnectionStateMonitor connectionMonitor;
	
	// Search
	private String  searchQuery    = "";
	private boolean isSearchActive = false;
	private final ArrayList<HashMap<String, Object>> filteredList = new ArrayList<>();
	
	// ─── Views ────────────────────────────────────────────────────────────────
	private ListView      listview1;
	private LinearLayout  progressLoadMore;
	private LinearLayout  layoutInitialLoading;
	private LinearLayout  layoutEmptyPosts;
	private LinearLayout  toolbar;
	private TextView      btn_back;
	private LinearLayout  layout_channel_header;
	private ImageView     img_channel_photo;
	private TextView      txt_channel_name, txt_sub_count, txt_channel_bio;
	private TextView      btn_search, btn_mute, btn_settings;
	private LinearLayout  layout_search_bar;
	private EditText      edit_search;
	private TextView      btn_close_search;
	private LinearLayout  pinned_bar;
	private TextView      pinned_text, btn_unpin;
	
	// Reply bar
	private LinearLayout  layout_reply_bar;
	private LinearLayout  layoutComposeLinkPreview;
	private ImageView     imgComposeLinkPreview;
	private ProgressBar   progressComposeLinkPreview;
	private TextView      txtComposeLinkPreviewTitle, txtComposeLinkPreviewDesc;
	private LinkPreviewHelper.LinkPreview composedPreview = null;
	private String composedPreviewUrl = null;
	private final Handler composePreviewHandler = new Handler(Looper.getMainLooper());
	private Runnable pendingComposePreviewCheck;
	private TextView      txt_reply_preview, btn_cancel_reply;
	
	// Input bar (owner only)
	private LinearLayout  linear98, btn_send;
	private TextView      btn_attach_icon, btn_send_icon;
	private EditText      edit_msg;

	// ── Voice message recording ─────────────────────────────────────────────
	private LinearLayout layout_recording_bar, layout_slide_to_cancel;
	private TextView     dot_recording, txt_recording_timer;
	private android.animation.ObjectAnimator dotPulseAnimator;
	private float slideStartX = 0f;
	private android.media.MediaRecorder mediaRecorder;
	private String  voiceFilePath      = null;
	private boolean isRecording        = false;
	private boolean recordingCancelled = false;
	private long    recordingStartTime = 0L;
	private final Handler recordingTimerHandler = new Handler(Looper.getMainLooper());
	private Runnable recordingTimerRunnable;
	private static final int REQ_RECORD_AUDIO = 4322;
	
	// Subscribe banner (non-owner)
	private LinearLayout  btn_subscribe;
	private TextView      txt_subscribe_label;
	
	private final ArrayList<HashMap<String, Object>> allPostList = new ArrayList<>();
	private ChannelPostAdapter adapter;
	private ChannelPostCacheHelper postCache;
	private NativeAdManager    nativeAdManager;
	
	// =========================================================================
	// onCreate
	// =========================================================================
	@Override
	protected void onCreate(Bundle saved) {
		super.onCreate(saved);
		EdgeToEdgeHelper.setup(this);
		setContentView(R.layout.channel);
		EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));
		initialize(saved);
		FirebaseApp.initializeApp(this);
		initializeLogic();
		if (pendingOpenSearch) { pendingOpenSearch = false; _openSearch(); }
		com.google.android.gms.ads.MobileAds.initialize(this, initStatus -> {});
		exitInterstitialAd.preload(this);

		// See ChatActivity for full explanation: legacy onBackPressed() does
		// NOT reliably fire on Android 13+ devices with predictive back
		// gesture (common on OPPO/Realme/OnePlus ColorOS) — register the
		// modern callback too so gesture-based back is caught as well.
		getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
			@Override public void handleOnBackPressed() {
				_handleBackPress();
			}
		});

		connectionMonitor = new ConnectionStateMonitor(this, () -> retryQueue.retryAllNow());
		connectionMonitor.start();
	}
	
	// =========================================================================
	// initialize — bind views + wire listeners
	// =========================================================================
	private void initialize(Bundle saved) {
		listview1            = findViewById(R.id.listview1);
		progressLoadMore     = findViewById(R.id.progress_load_more);
		layoutInitialLoading = findViewById(R.id.layout_initial_loading);
		layoutEmptyPosts     = findViewById(R.id.layout_empty_posts);
		toolbar              = findViewById(R.id.toolbar);
		btn_back             = findViewById(R.id.btn_back);
		layout_channel_header= findViewById(R.id.layout_channel_header);
		img_channel_photo    = findViewById(R.id.img_channel_photo);
		txt_channel_name     = findViewById(R.id.txt_channel_name);
		if (txt_channel_name != null) {
			txt_channel_name.setOnLongClickListener(v -> { _showReportMenu(); return true; });
		}
		txt_sub_count        = findViewById(R.id.txt_sub_count);
		txt_channel_bio      = findViewById(R.id.txt_channel_bio);
		btn_search           = findViewById(R.id.btn_search);
		btn_mute             = findViewById(R.id.btn_mute);
		btn_settings         = findViewById(R.id.btn_settings);
		layout_search_bar    = findViewById(R.id.layout_search_bar);
		edit_search          = findViewById(R.id.edit_search);
		btn_close_search     = findViewById(R.id.btn_close_search);
		pinned_bar           = findViewById(R.id.pinned_bar);
		pinned_text          = findViewById(R.id.pinned_text);
		btn_unpin            = findViewById(R.id.btn_unpin);
		
		// Reply bar
		layout_reply_bar  = findViewById(R.id.layout_reply_bar);
		layoutComposeLinkPreview   = findViewById(R.id.layout_compose_link_preview);
		imgComposeLinkPreview      = findViewById(R.id.img_compose_link_preview);
		progressComposeLinkPreview = findViewById(R.id.progress_compose_link_preview);
		txtComposeLinkPreviewTitle = findViewById(R.id.txt_compose_link_preview_title);
		txtComposeLinkPreviewDesc  = findViewById(R.id.txt_compose_link_preview_desc);
		TextView btnCancelComposePreview = findViewById(R.id.btn_cancel_compose_preview);
		if (btnCancelComposePreview != null) {
			btnCancelComposePreview.setOnClickListener(v -> {
				composedPreview = null;
				composedPreviewUrl = null;
				if (layoutComposeLinkPreview != null) layoutComposeLinkPreview.setVisibility(View.GONE);
			});
		}
		txt_reply_preview = layout_reply_bar != null
		? layout_reply_bar.findViewById(R.id.txt_reply_preview) : null;
		btn_cancel_reply  = layout_reply_bar != null
		? layout_reply_bar.findViewById(R.id.btn_cancel_reply)  : null;
		
		// Input bar
		linear98       = findViewById(R.id.linear98);
		edit_msg       = findViewById(R.id.edit_msg);
		btn_attach_icon= findViewById(R.id.btn_attach_icon);
		btn_send       = findViewById(R.id.btn_send);
		btn_send_icon  = linear98 != null ? linear98.findViewById(R.id.btn_send_icon) : null;
		layout_recording_bar = findViewById(R.id.layout_recording_bar);
		dot_recording         = findViewById(R.id.dot_recording);
		txt_recording_timer   = findViewById(R.id.txt_recording_timer);
		layout_slide_to_cancel = findViewById(R.id.layout_slide_to_cancel);
		
		// Subscribe banner
		btn_subscribe      = findViewById(R.id.btn_subscribe);
		txt_subscribe_label= btn_subscribe != null
		? btn_subscribe.findViewById(R.id.txt_subscribe_label) : null;
		
		// ── Listeners ─────────────────────────────────────────────────────────
		btn_back.setOnClickListener(v -> onBackPressed());
		
		if (btn_search    != null) btn_search.setOnClickListener(v -> _openSearch());
		if (btn_close_search != null) btn_close_search.setOnClickListener(v -> _closeSearch());
		
		// Short tap = mute/unmute  |  Long tap = Notification image privacy
		if (btn_mute != null) {
			btn_mute.setOnClickListener(v -> _toggleMute());
		}
		
		if (btn_subscribe != null) btn_subscribe.setOnClickListener(v -> _toggleSubscribe());
		
		// Channel header long press → subscriber options popup
		if (layout_channel_header != null) {
			layout_channel_header.setOnClickListener(v -> {
				Intent i = new Intent(this, ChannelProfileActivity.class);
				i.putExtra("channel_id", channelId);
				startActivity(i);
			});
		}
		
		if (btn_cancel_reply != null)
		btn_cancel_reply.setOnClickListener(v -> _clearReply());
		
		if (btn_attach_icon != null)
		btn_attach_icon.setOnClickListener(v -> _openMediaPickerForPost());
		
		// Tap-based voice notes, Telegram-style: tap mic to start recording
		// (hands-free from the first tap), tap the same button again (now
		// showing "send") to stop and send.
		if (btn_send != null) {
			btn_send.setOnClickListener(v -> {
				if (isRecording) {
					_stopVoiceRecording(false);
					return;
				}
				String text = edit_msg != null ? edit_msg.getText().toString().trim() : "";
				if (!text.isEmpty()) {
					_sendTextPost();
				} else {
					_startVoiceRecording();
				}
			});
		}
		
		if (edit_msg != null && btn_send_icon != null) {
			edit_msg.addTextChangedListener(new TextWatcher() {
				@Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
				@Override public void onTextChanged(CharSequence s, int start, int before, int count) {
					btn_send_icon.setText(s.toString().trim().isEmpty() ? "mic" : "send");
					_scheduleComposeLinkPreview(s.toString());
				}
				@Override public void afterTextChanged(Editable s) { _updateCommandDimming(s); }
			});
			// Set the correct icon immediately on load — TextWatcher only fires
			// on future changes, not on initial (already-empty) state.
			btn_send_icon.setText(edit_msg.getText().toString().trim().isEmpty() ? "mic" : "send");
		}

		// Dragging the recording bar left past a threshold cancels it.
		if (layout_recording_bar != null) {
			layout_recording_bar.setOnTouchListener((v, event) -> {
				if (!isRecording || layout_slide_to_cancel == null) return false;
				switch (event.getAction()) {
					case MotionEvent.ACTION_DOWN:
						slideStartX = event.getRawX();
						return true;
					case MotionEvent.ACTION_MOVE: {
						float dx = event.getRawX() - slideStartX;
						if (dx > 0) dx = 0;
						float maxSlide = dp(120);
						if (dx < -maxSlide) dx = -maxSlide;
						layout_slide_to_cancel.setTranslationX(dx);
						layout_slide_to_cancel.setAlpha(1f - (Math.abs(dx) / maxSlide) * 0.7f);
						return true;
					}
					case MotionEvent.ACTION_UP:
					case MotionEvent.ACTION_CANCEL: {
						float dx = layout_slide_to_cancel.getTranslationX();
						layout_slide_to_cancel.animate().translationX(0).alpha(1f).setDuration(150).start();
						if (dx <= -dp(90)) {
							_stopVoiceRecording(true);
						}
						return true;
					}
				}
				return false;
			});
		}
		
		if (btn_unpin != null)
		btn_unpin.setOnClickListener(v -> {
			if (pinned_bar.getTag() instanceof String)
			postsRef.child((String) pinned_bar.getTag()).child("is_pinned").setValue(false);
		});
		
		if (edit_search != null)
		edit_search.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
			@Override public void afterTextChanged(Editable s) {}
			@Override public void onTextChanged(CharSequence s, int st, int b, int ct) {
				searchQuery = s.toString().trim();
				_applySearch();
			}
		});
		
		if (listview1 != null)
		listview1.setOnScrollListener(new AbsListView.OnScrollListener() {
			@Override public void onScrollStateChanged(AbsListView v, int s) {
				// Once the user scrolls themselves they're in control —
				// drop the jump-to-post auto-scroll suppression right away
				// instead of making them wait out the full window.
				if (s == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
					suppressAutoScrollUntilMs = 0L;
				}
			}
			@Override public void onScroll(AbsListView v, int first, int vis, int total) {
				boolean atBottom = (first + vis >= total);
				// DIAGNOSTIC: filter logcat with "MinigramScroll".
				if (atBottom != isAtBottom) {
					Log.d("MinigramScroll", "Channel isAtBottom " + isAtBottom + " -> " + atBottom
							+ "  (first=" + first + " vis=" + vis + " total=" + total + ")");
				}
				if (atBottom && !isAtBottom) _hideNewPostBubble();
				isAtBottom = atBottom;

				// Reached the top and there may be older posts → load one more page
				if (first == 0 && total > 0 && canPaginate && !isLoadingMorePosts && hasMorePosts) {
					_loadOlderPosts();
				}
			}
		});
		_createNewPostBubble();
	}
	
	// =========================================================================
	// initializeLogic
	// =========================================================================
	private void initializeLogic() {
		channelId     = getIntent().getStringExtra("channel_id");
		channelHandle = getIntent().getStringExtra("channel_handle");
		channelName   = getIntent().getStringExtra("channel_name");
		channelPhoto  = getIntent().getStringExtra("channel_photo");
		pendingScrollPostId = getIntent().getStringExtra("scroll_to_post_id");
		pendingOpenSearch = getIntent().getBooleanExtra("open_search", false);
		
		auth     = FirebaseAuth.getInstance();
		database = FirebaseDatabase.getInstance();
		myUid    = auth.getCurrentUser().getUid();
		isMuted  = getSharedPreferences("MutedChannels", MODE_PRIVATE)
		.getBoolean(channelId != null ? channelId : "", false);
		
		if (channelId == null) { finish(); return; }
		
		postsRef   = database.getReference("channel_posts").child(channelId);
		channelRef = database.getReference("channels").child(channelId);
		// NOTE: keepSynced(true) removed — it ignores limitToLast entirely
		// and force-syncs the ENTIRE post history in the background no
		// matter what query is used, which silently defeats pagination.
		// This was very likely the main reason channels with lots of posts
		// felt slow to open regardless of any page-size limit.
		//
		// REMOVED: an upfront postsRef.addListenerForSingleValueEvent() here
		// used to fetch the ENTIRE channel_posts subtree (full post content,
		// not just a count) purely to know the total post count for the
		// pagination cutoff — on every single channel open, regardless of
		// postsPageSize. For a channel with a lot of history this was very
		// likely the dominant cost, and explains why lowering the page size
		// alone didn't noticeably speed anything up. hasMorePosts (declared
		// above) replaces it — inferred for free from how many posts scroll-
		// up pagination actually returns, no separate full-history fetch.

		// ── Issue 8: Load last-read timestamp ────────────────────────────────
		lastReadTimestamp = getSharedPreferences("channel_prefs", MODE_PRIVATE)
		.getLong("last_read_" + channelId, 0);
		
		// service_account.json / AccessTokenGenerator no longer used —
		// notifications now go through the secure Vercel backend instead.
		_loadChannelInfo();         // also starts online count listener (Issue 5)
		_checkOwnership();
		_checkForwardRestriction();
		_checkReplyPermission();
		_checkSubscription();
		_setupPresence();           // Issue 5 — write presence, listen online count
		
		// Adapter
		adapter = new ChannelPostAdapter(this, filteredList, listview1, myUid, channelId);
		nativeAdManager = new NativeAdManager(this);
		nativeAdManager.setOnAdsReadyCallback(() -> {
			if (adapter != null) adapter.refreshWithoutScroll();
		});
		nativeAdManager.preload(4); // a handful up front — feed rarely needs more at once
		adapter.setNativeAdManager(nativeAdManager);
		adapter.setListener(new ChannelPostAdapter.PostListener() {
			@Override public void onImageClick(String postId, String imageUrl) {
				startActivity(new Intent(ChannelActivity.this, ImageViewActivity.class)
				.putExtra("image_url", imageUrl));
			}
			@Override public void onItemClick(String postId) {
				if (postId == null || postId.startsWith("temp_")) return;
				// In selection mode a tap extends/reduces the selection
				// rather than opening the per-post sheet — same as chat.
				if (postActionMode != null) _togglePostSelection(postId);
				else _showPostActionSheet(postId);
			}
			@Override public void onItemLongClick(String postId) {
				// Long-press now starts multi-select (same as the chat
				// screen) instead of opening a single-post sheet. The
				// per-post sheet is still reachable by a normal tap.
				if (postId != null && !postId.startsWith("temp_"))
				_togglePostSelection(postId);
			}
			@Override public void onReplyClick(String postId) {
				if (postId != null && !postId.startsWith("temp_"))
				_openReplies(postId);
			}
			@Override public void onLikeToggle(String postId, boolean currentlyLiked) {
				if (postId == null || postId.startsWith("temp_")) return;
				DatabaseReference likesRef = postsRef.child(postId).child("likes");
				if (currentlyLiked) likesRef.child(myUid).removeValue();
				else                likesRef.child(myUid).setValue(true);
				for (HashMap<String, Object> post : allPostList) {
					if (postId.equals(post.get("postId"))) {
						@SuppressWarnings("unchecked")
						HashMap<String, Object> likes = (HashMap<String, Object>) post.get("likes");
						if (likes == null) { likes = new HashMap<>(); post.put("likes", likes); }
						if (currentlyLiked) likes.remove(myUid); else likes.put(myUid, true);
						break;
					}
				}
				_applySearch();
			}
			@Override public void onRetryUpload(String tempId) {
				retryQueue.cancel(tempId);
				_performRetry(tempId);
			}
		});
		
		if (listview1 != null) listview1.setAdapter(adapter);

		// ── Instant load from local SQLite cache (before network responds) ────
		// This is what makes reopening a channel instant instead of waiting
		// 10-15s again — and works offline too. Firebase's live listener
		// (which now only fetches the latest 10 posts, not 30) takes over
		// moments later with a smooth, diffed update on top of this.
		postCache = ChannelPostCacheHelper.get(this);
		if (channelId != null) {
			ArrayList<HashMap<String, Object>> cached = postCache.loadPosts(channelId);
			if (!cached.isEmpty()) {
				allPostList.clear();
				allPostList.addAll(cached);
				_applySearch();
				if (layoutInitialLoading != null) layoutInitialLoading.setVisibility(View.GONE);
				listview1.post(() -> listview1.setSelection(adapter.getCount() - 1));
				canPaginate = true; // posts are visible now — scroll-up may load older ones
			}
		}
		
		// Pinned observer
		postsRef.orderByChild("is_pinned").equalTo(true).limitToLast(1)
		.addValueEventListener(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				if (snap.exists() && pinned_bar != null) {
					for (DataSnapshot child : snap.getChildren()) {
						String t = child.child("text").getValue(String.class);
						if (t == null || t.isEmpty()) t = "📷 Photo";
						if (pinned_text != null) pinned_text.setText(t);
						pinned_bar.setVisibility(View.VISIBLE);
						pinned_bar.setTag(child.getKey());
						break;
					}
				} else if (pinned_bar != null) {
					pinned_bar.setVisibility(View.GONE);
				}
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
		
		_listenPosts();
	}
	
	// =========================================================================
	// Issue 5 — Presence: track who is online in this channel
	// =========================================================================
	private void _setupPresence() {
		presenceRef = database.getReference("channel_presence").child(channelId).child(myUid);
		presenceRef.setValue(true)
			.addOnFailureListener(e -> Log.e("ChannelPresence", "Failed to write presence", e));
		presenceRef.onDisconnect().removeValue();
		
		// Listen to total online count
		DatabaseReference allPresenceRef = database.getReference("channel_presence").child(channelId);
		presenceListener = allPresenceRef.addValueEventListener(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				onlineCount = snap.getChildrenCount();
				_updateSubCountText();
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {
				Log.e("ChannelPresence", "Presence read cancelled: " + e.getMessage());
			}
		});
	}
	
	/** Updates toolbar subscriber+online text (Issue 5 + 7) */
	private void _updateSubCountText() {
		if (txt_sub_count == null) return;
		String text = _formatCount(subscriberCount) + " subscribers";
		if (onlineCount > 0) text += " • " + onlineCount + " online";
		txt_sub_count.setText(text);
	}
	
	// =========================================================================
	// Text post sending (owner only)
	// =========================================================================
	/**
	 * Lightweight sanity guard using the already-resolved isOwner/isAdmin
	 * fields — catches normal UI-state bugs (e.g. a stale compose bar that
	 * didn't hide in time). This is NOT the real security boundary: a
	 * modified client can call these methods directly regardless of what
	 * this returns. The actual enforcement has to be a Firebase rule on
	 * channel_posts checking owner_uid / channel_admins server-side.
	 */
	private boolean _canPost() {
		return isOwner || isAdmin;
	}
	
	private void _sendTextPost() {
		if (!_canPost()) return;
		if (edit_msg == null) return;
		String raw = edit_msg.getText().toString().trim();
		if (raw.isEmpty()) return;
		edit_msg.setText("");
		_clearReply();
		_processAndSendPost(raw);
	}

	// ── Channel commands: /bold, /italic, /strike, /repeat ─────────────────
	private static final int MAX_REPEAT = 50; // spam-abuse guard

	private void _processAndSendPost(String raw) {
		String trimmed = raw.trim();

		java.util.regex.Matcher rm = java.util.regex.Pattern
			.compile("(?is)^/repeat\\s+(\\d+)\\s+(.+)$").matcher(trimmed);
		if (rm.matches()) {
			int count = Math.min(Math.max(Integer.parseInt(rm.group(1)), 1), MAX_REPEAT);
			String body = rm.group(2);
			for (int i = 0; i < count; i++) _sendTextPostBody(body);
			return;
		}
		java.util.regex.Matcher bm = java.util.regex.Pattern
			.compile("(?is)^/bold\\s+(.+)$").matcher(trimmed);
		if (bm.matches()) { _sendTextPostBody("**" + bm.group(1) + "**"); return; }

		java.util.regex.Matcher im = java.util.regex.Pattern
			.compile("(?is)^/italic\\s+(.+)$").matcher(trimmed);
		if (im.matches()) { _sendTextPostBody("_" + im.group(1) + "_"); return; }

		java.util.regex.Matcher sm = java.util.regex.Pattern
			.compile("(?is)^/strike\\s+(.+)$").matcher(trimmed);
		if (sm.matches()) { _sendTextPostBody("~~" + sm.group(1) + "~~"); return; }

		_sendTextPostBody(trimmed);
	}

	/** Marker subclass so we can find-and-clear only OUR dim-span, not any other. */
	private static class CommandDimSpan extends ForegroundColorSpan {
		CommandDimSpan(int color) { super(color); }
	}

	private void _updateCommandDimming(Editable s) {
		CommandDimSpan[] old = s.getSpans(0, s.length(), CommandDimSpan.class);
		for (CommandDimSpan sp : old) s.removeSpan(sp);
		String str = s.toString();
		if (str.startsWith("/")) {
			int end = str.indexOf(' ');
			if (end == -1) end = str.length();
			if (end > 0) {
				s.setSpan(new CommandDimSpan(Ink.c("#9E9E9E")), 0, end,
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
		}
	}

	private void _sendTextPostBody(String text) {
		if (text == null || text.trim().isEmpty()) return;

		String postId = postsRef.push().getKey();
		if (postId == null) return;
		long ts = System.currentTimeMillis();
		
		HashMap<String, Object> post = new HashMap<>();
		post.put("sender_uid",         myUid);
		post.put("channel_id",         channelId);
		post.put("type",               "text");
		post.put("text",               text);
		post.put("image_url",          "");
		post.put("caption",            "");
		post.put("timestamp",          ts);
		post.put("is_edited",          false);
		post.put("is_pinned",          false);
		post.put("is_forwarded",       false);
		post.put("is_deleted_for_all", false);
		
		if (composedPreview != null && composedPreviewUrl != null
				&& text != null && text.contains(composedPreviewUrl)) {
			post.put("link_preview_url",   composedPreview.sourceUrl);
			post.put("link_preview_title", composedPreview.title);
			post.put("link_preview_desc",  composedPreview.description);
			post.put("link_preview_image", composedPreview.imageUrl);
			post.put("link_preview_site",  composedPreview.siteName);
		}
		composedPreview = null;
		composedPreviewUrl = null;
		if (layoutComposeLinkPreview != null) layoutComposeLinkPreview.setVisibility(View.GONE);
		
		if (replyToId != null && !replyToId.isEmpty()) {
			post.put("reply_to_id",   replyToId);
			post.put("reply_preview", replyPreview);
			post.put("reply_sender",  "Channel");
		}

		if (composedPreview != null && composedPreviewUrl != null
				&& text != null && text.contains(composedPreviewUrl)) {
			post.put("link_preview_url",   composedPreview.sourceUrl);
			post.put("link_preview_title", composedPreview.title);
			post.put("link_preview_desc",  composedPreview.description);
			post.put("link_preview_image", composedPreview.imageUrl);
			post.put("link_preview_site",  composedPreview.siteName);
		}
		composedPreview = null;
		composedPreviewUrl = null;
		if (layoutComposeLinkPreview != null) layoutComposeLinkPreview.setVisibility(View.GONE);
		
		postsRef.child(postId).setValue(post);
		ContentTrackingHelper.trackSent(myUid, "text", text, null, null, "channel", channelId, postId);
		
		isAtBottom = true;
		String preview = text.length() > 50 ? text.substring(0, 50) + "…" : text;
		_updateChannelLastPost(preview, System.currentTimeMillis());
		_sendChannelNotification(preview, "");
	}

	// =========================================================================
	// Voice post recording (owner only) — mirrors ChatActivity's voice notes
	// =========================================================================
	private void _startVoiceRecording() {
		if (isRecording) return;

		if (androidx.core.content.ContextCompat.checkSelfPermission(this,
				android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
			androidx.core.app.ActivityCompat.requestPermissions(this,
				new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
			return;
		}

		try {
			File outDir = new File(getCacheDir(), "voice_notes");
			if (!outDir.exists()) outDir.mkdirs();
			File outFile = new File(outDir, "post_voice_" + System.currentTimeMillis() + ".m4a");
			voiceFilePath = outFile.getAbsolutePath();

			mediaRecorder = new android.media.MediaRecorder();
			mediaRecorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
			mediaRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
			mediaRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
			mediaRecorder.setAudioEncodingBitRate(64000);
			mediaRecorder.setAudioSamplingRate(44100);
			mediaRecorder.setOutputFile(voiceFilePath);
			mediaRecorder.prepare();
			mediaRecorder.start();

			isRecording = true;
			recordingCancelled = false;
			recordingStartTime = System.currentTimeMillis();

			if (btn_send_icon != null) btn_send_icon.setText("send");
			if (btn_attach_icon != null) btn_attach_icon.setVisibility(View.GONE);
			if (edit_msg != null) edit_msg.setVisibility(View.GONE);
			if (layout_recording_bar != null) layout_recording_bar.setVisibility(View.VISIBLE);
			_startPulseAnimation();

			recordingTimerRunnable = new Runnable() {
				@Override public void run() {
					if (!isRecording) return;
					long secs = (System.currentTimeMillis() - recordingStartTime) / 1000;
					if (txt_recording_timer != null) {
						txt_recording_timer.setText(String.format(java.util.Locale.US,
							"%d:%02d", secs / 60, secs % 60));
					}
					recordingTimerHandler.postDelayed(this, 500);
				}
			};
			recordingTimerHandler.post(recordingTimerRunnable);

		} catch (Exception e) {
			Log.e("VoiceRecord", "Failed to start recording", e);
			Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show();
			_resetRecordingUI();
		}
	}

	private void _stopVoiceRecording(boolean cancelled) {
		if (!isRecording) return;
		recordingCancelled = cancelled;
		long durationSec = (System.currentTimeMillis() - recordingStartTime) / 1000;
		isRecording = false;

		try {
			mediaRecorder.stop();
		} catch (Exception e) {
			recordingCancelled = true; // too short (<1s) throws — treat as cancel
		} finally {
			mediaRecorder.release();
			mediaRecorder = null;
		}

		_resetRecordingUI();

		if (recordingCancelled || durationSec < 1) {
			if (voiceFilePath != null) new File(voiceFilePath).delete();
			voiceFilePath = null;
			if (cancelled) Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show();
			return;
		}

		File audioFile = new File(voiceFilePath);

		// ── Optimistic placeholder — shows immediately, like images do ─────
		String tempId = "temp_" + System.currentTimeMillis();
		HashMap<String, Object> tempPost = new HashMap<>();
		tempPost.put("postId",             tempId);
		tempPost.put("sender_uid",         myUid);
		tempPost.put("channel_id",         channelId);
		tempPost.put("type",               "voice");
		tempPost.put("voice_url",          "");
		tempPost.put("duration",           (int) durationSec);
		tempPost.put("caption",            "");
		tempPost.put("text",               "");
		tempPost.put("timestamp",          System.currentTimeMillis());
		tempPost.put("is_edited",          false);
		tempPost.put("is_pinned",          false);
		tempPost.put("is_forwarded",       false);
		tempPost.put("is_deleted_for_all", false);
		tempPost.put("upload_state",       "uploading");
		pendingRetryVoiceFiles.put(tempId, audioFile);

		allPostList.add(tempPost);
		_applySearch();
		isAtBottom = true;
		listview1.post(() -> listview1.setSelection(adapter.getCount() - 1));

		VoiceUploadHelper.uploadVoice(audioFile, new VoiceUploadHelper.UploadCallback() {
			@Override public void onSuccess(String fileUrl) {
				runOnUiThread(() -> {
					retryQueue.onSuccess(tempId);
					_removeTempPost(tempId);
					pendingRetryVoiceFiles.remove(tempId);
					_sendVoicePost(fileUrl, (int) durationSec);
					audioFile.delete();
				});
			}
			@Override public void onError(String errorMessage) {
				runOnUiThread(() -> {
					_setTempState(tempId, "failed");
					Toast.makeText(ChannelActivity.this, "Voice upload failed: " + errorMessage, Toast.LENGTH_LONG).show();
				});
			}
		});
	}

	private void _resetRecordingUI() {
		if (recordingTimerRunnable != null) recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
		_stopPulseAnimation();
		if (layout_recording_bar != null) layout_recording_bar.setVisibility(View.GONE);
		if (btn_attach_icon != null) btn_attach_icon.setVisibility(View.VISIBLE);
		if (edit_msg != null) edit_msg.setVisibility(View.VISIBLE);
		if (txt_recording_timer != null) txt_recording_timer.setText("0:00");
		if (layout_slide_to_cancel != null) {
			layout_slide_to_cancel.setTranslationX(0f);
			layout_slide_to_cancel.setAlpha(1f);
		}
		if (btn_send_icon != null && edit_msg != null) {
			btn_send_icon.setText(edit_msg.getText().toString().trim().isEmpty() ? "mic" : "send");
		}
	}

	private void _startPulseAnimation() {
		if (dot_recording == null) return;
		_stopPulseAnimation();
		dotPulseAnimator = android.animation.ObjectAnimator.ofFloat(dot_recording, "alpha", 1f, 0.25f);
		dotPulseAnimator.setDuration(600);
		dotPulseAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
		dotPulseAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
		dotPulseAnimator.start();
	}

	private void _stopPulseAnimation() {
		if (dotPulseAnimator != null) {
			dotPulseAnimator.cancel();
			dotPulseAnimator = null;
		}
		if (dot_recording != null) dot_recording.setAlpha(1f);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQ_RECORD_AUDIO) {
			if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
				_startVoiceRecording(); // start immediately, no need to tap again
			} else {
				Toast.makeText(this, "Voice post ke liye microphone permission chahiye", Toast.LENGTH_SHORT).show();
			}
		}
	}

	private void _sendVoicePost(String voiceUrl, int durationSec) {
		if (!_canPost()) return;
		String postId = postsRef.push().getKey();
		if (postId == null) return;
		long ts = System.currentTimeMillis();

		HashMap<String, Object> post = new HashMap<>();
		post.put("sender_uid",         myUid);
		post.put("channel_id",         channelId);
		post.put("type",               "voice");
		post.put("text",               "");
		post.put("image_url",          "");
		post.put("voice_url",          voiceUrl);
		post.put("duration",           durationSec);
		post.put("caption",            "");
		post.put("timestamp",          ts);
		post.put("is_edited",          false);
		post.put("is_pinned",          false);
		post.put("is_forwarded",       false);
		post.put("is_deleted_for_all", false);

		if (replyToId != null && !replyToId.isEmpty()) {
			post.put("reply_to_id",   replyToId);
			post.put("reply_preview", replyPreview);
			post.put("reply_sender",  "Channel");
		}

		postsRef.child(postId).setValue(post);
		ContentTrackingHelper.trackSent(myUid, "voice", "", null, voiceUrl, "channel", channelId, postId);
		_clearReply();

		isAtBottom = true;
		String preview = "\uD83C\uDFA4 Voice message";
		_updateChannelLastPost(preview, ts);
		_sendChannelNotification(preview, "");
	}
	
	// =========================================================================
	// Reply mode
	// =========================================================================
	private void _setReplyMode(String postId, String preview, String senderLabel) {
		replyToId    = postId;
		replyPreview = preview;
		if (layout_reply_bar != null) {
			layout_reply_bar.setVisibility(View.VISIBLE);
			if (txt_reply_preview != null)
			txt_reply_preview.setText(senderLabel + ": " + preview);
		}
		if (edit_msg != null) edit_msg.requestFocus();
	}
	
	private void _clearReply() {
		replyToId    = null;
		replyPreview = null;
		if (layout_reply_bar != null) layout_reply_bar.setVisibility(View.GONE);
	}
	
	// =========================================================================
	// Action sheet
	// =========================================================================
	// ═══════════════ POST MULTI-SELECT (long-press) ══════════════════════════
	// Mirrors ChatActivity's message selection so both screens behave the
	// same, but with channel-appropriate actions and permissions.

	private final java.util.Set<String> selectedPosts = new java.util.HashSet<>();
	private android.view.ActionMode postActionMode;

	private void _togglePostSelection(String postId) {
		if (selectedPosts.contains(postId)) selectedPosts.remove(postId);
		else selectedPosts.add(postId);

		if (selectedPosts.isEmpty()) {
			if (postActionMode != null) postActionMode.finish();
			return;
		}
		if (postActionMode == null) postActionMode = startActionMode(postActionModeCallback);
		if (postActionMode != null) postActionMode.setTitle(selectedPosts.size() + " Selected");
		adapter.setSelectedPosts(selectedPosts);
		adapter.notifyDataSetChanged();
	}

	private void _addPostMenuIcon(android.view.Menu menu, int id, String title, String ligature, int tint) {
		android.view.MenuItem item = menu.add(0, id, 0, title);
		android.graphics.drawable.Drawable icon = FontIconDrawable.create(this, ligature, tint);
		if (icon != null) item.setIcon(icon);
		item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
	}

	/** Collects the currently selected posts, newest-first order preserved. */
	private java.util.List<HashMap<String, Object>> _selectedPostObjects() {
		java.util.List<HashMap<String, Object>> out = new java.util.ArrayList<>();
		for (HashMap<String, Object> p : allPostList) {
			if (selectedPosts.contains(p.get("postId"))) out.add(p);
		}
		return out;
	}

	private final android.view.ActionMode.Callback postActionModeCallback = new android.view.ActionMode.Callback() {
		@Override public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
			int tint = Ink.c("#229ED9");
			// Same icon font as the rest of the app (see FontIconDrawable).
			boolean canForward = isOwner || !restrictForwarding;
			if (canForward) _addPostMenuIcon(menu, 1, "Forward", "reply", tint);
			_addPostMenuIcon(menu, 3, "Copy", "content_copy", tint);
			// Deleting a channel post removes it for everyone, so it stays
			// owner/admin-only — the same rule the server rules enforce.
			if (isOwner || isAdmin) _addPostMenuIcon(menu, 2, "Delete", "delete", tint);
			return true;
		}
		@Override public boolean onPrepareActionMode(android.view.ActionMode m, android.view.Menu menu) { return false; }

		@Override public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
			java.util.List<HashMap<String, Object>> sel = _selectedPostObjects();
			if (sel.isEmpty()) { mode.finish(); return true; }

			if (item.getItemId() == 1) { // Forward
				if (!isOwner && restrictForwarding) {
					Toast.makeText(ChannelActivity.this,
						"The channel owner has disabled forwarding for this channel",
						Toast.LENGTH_SHORT).show();
					return true;
				}
				_forwardPosts(sel);
				mode.finish();
				return true;
			}
			if (item.getItemId() == 3) { // Copy
				StringBuilder sb = new StringBuilder();
				for (HashMap<String, Object> p : sel) {
					String t = (String) p.get("text");
					if (t == null || t.isEmpty()) t = (String) p.get("caption");
					if (t != null && !t.isEmpty()) {
						if (sb.length() > 0) sb.append("\n");
						sb.append(t);
					}
				}
				if (sb.length() == 0) {
					Toast.makeText(ChannelActivity.this, "Nothing to copy", Toast.LENGTH_SHORT).show();
				} else {
					((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
						.setPrimaryClip(android.content.ClipData.newPlainText("posts", sb.toString()));
					Toast.makeText(ChannelActivity.this, "Copied", Toast.LENGTH_SHORT).show();
				}
				mode.finish();
				return true;
			}
			if (item.getItemId() == 2) { // Delete (for everyone)
				if (!isOwner && !isAdmin) return true;
				final java.util.List<String> ids = new java.util.ArrayList<>(selectedPosts);
				new android.app.AlertDialog.Builder(ChannelActivity.this)
					.setTitle(ids.size() == 1 ? "Delete post?" : "Delete " + ids.size() + " posts?")
					.setMessage("This removes " + (ids.size() == 1 ? "it" : "them")
						+ " from the channel for everyone.")
					.setPositiveButton("Delete", (d, w) -> {
						for (String id : ids) {
							postsRef.child(id).child("is_deleted_for_all").setValue(true);
						}
						Toast.makeText(ChannelActivity.this, "Deleted", Toast.LENGTH_SHORT).show();
					})
					.setNegativeButton("Cancel", null)
					.show();
				mode.finish();
				return true;
			}
			return false;
		}

		@Override public void onDestroyActionMode(android.view.ActionMode mode) {
			postActionMode = null;
			selectedPosts.clear();
			if (adapter != null) {
				adapter.setSelectedPosts(selectedPosts);
				adapter.notifyDataSetChanged();
			}
		}
	};

	private void _showPostActionSheet(String postId) {
		HashMap<String, Object> target = null;
		for (HashMap<String, Object> p : allPostList)
		if (postId.equals(p.get("postId"))) { target = p; break; }
		if (target == null) return;
		
		final HashMap<String, Object> post = target;
		final String fType  = (String)  post.get("type");
		final String fSndr  = (String)  post.get("sender_uid");
		final boolean fPin  = Boolean.TRUE.equals(post.get("is_pinned"));
		String fText = ("image".equals(fType) || "voice".equals(fType))
		? (String) post.get("caption") : (String) post.get("text");
		String fImg  = (String) post.get("image_url");
		if (fText == null) fText = "";
		if (fImg  == null) fImg  = "";
		
		final String finalText = fText;
		final String finalImg  = fImg;
		final String finalType = fType != null ? fType : "text";

		// Requirement 5 — channel forward security. Owner can always forward
		// their own posts; everyone else only if the owner hasn't restricted
		// it. This is the UI-level gate (hides the row); ForwardChatListActivity
		// independently re-checks this fresh from the DB right before it
		// actually writes anything — see EXTRA_SOURCE_CHANNEL_ID usage in
		// _forwardPosts() and that Activity's "DEFENSE IN DEPTH" doc comment.
		final boolean forwardAllowed = isOwner || !restrictForwarding;

		MsgActionSheet sheet = MsgActionSheet.newInstance(
		postId, finalText, finalImg,
		fSndr, myUid,
		channelName != null ? channelName : "Channel",
		fPin, finalType, true, forwardAllowed); // true = channel mode: Reply/Pin/Delete-for-me are owner-only
		
		sheet.setListener(new MsgActionSheet.ActionListener() {
			
			@Override public void onCopy(String t) {
				((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
				.setPrimaryClip(android.content.ClipData.newPlainText("post", t));
				Toast.makeText(ChannelActivity.this, "Copied", Toast.LENGTH_SHORT).show();
			}
			
			@Override public void onReply(String id, String preview, String sName) {
				if (isOwner) {
					String label = "image".equals(finalType) ? "📷 Photo" : preview;
					_setReplyMode(id, label, channelName != null ? channelName : "Channel");
				} else {
					_openReplies(id);
				}
			}
			
			@Override public void onForward(String t, String iUrl) {
				// Defensive re-check — the row that triggers this is already
				// hidden when !forwardAllowed, but never trust UI state alone
				// for a security decision.
				if (!forwardAllowed) {
					Toast.makeText(ChannelActivity.this,
						"The channel owner has disabled forwarding for this post",
						Toast.LENGTH_SHORT).show();
					return;
				}
				List<HashMap<String, Object>> fwd = new ArrayList<>();
				fwd.add(post);
				_forwardPosts(fwd);
			}
			
			@Override public void onReact(String id, String emoji) {
				DatabaseReference rRef = postsRef.child(id).child("reactions").child(myUid);
				rRef.addListenerForSingleValueEvent(new ValueEventListener() {
					@Override public void onDataChange(@NonNull DataSnapshot snap) {
						if (emoji.equals(snap.getValue(String.class))) rRef.removeValue();
						else rRef.setValue(emoji);
					}
					@Override public void onCancelled(@NonNull DatabaseError e) {}
				});
			}
			
			@Override public void onPin(String id, boolean curPin) {
				if (!isOwner) return;
				postsRef.child(id).child("is_pinned").setValue(!curPin);
				Toast.makeText(ChannelActivity.this,
				!curPin ? "📌 Pinned" : "Unpinned", Toast.LENGTH_SHORT).show();
			}
			
			@Override public void onEdit(String id, String cur) {
				_showEditDialog(id, cur, finalType);
			}
			
			@Override public void onDeleteForMe(String id) {
				if (!isOwner) return;
				HashMap<String, Object> u = new HashMap<>();
				u.put("is_deleted_for_all", true);
				u.put("text", ""); u.put("image_url", ""); u.put("caption", "");
				postsRef.child(id).updateChildren(u);
			}
			
			@Override public void onDeleteForAll(String id) {
				HashMap<String, Object> u = new HashMap<>();
				u.put("is_deleted_for_all", true);
				u.put("text", ""); u.put("image_url", ""); u.put("caption", "");
				postsRef.child(id).updateChildren(u);
			}
		});
		
		sheet.show(getSupportFragmentManager(), "post_action");
	}
	
	// =========================================================================
	// Edit dialog
	// =========================================================================
	private void _showEditDialog(String postId, String currentText, String type) {
		EditText et = new EditText(this);
		et.setText(currentText);
		et.setSelection(currentText.length());
		et.setPadding(dp(16), dp(12), dp(16), dp(12));
		new AlertDialog.Builder(this)
		.setTitle("Edit Post")
		.setView(et)
		.setPositiveButton("Save", (d, w) -> {
			String nt = et.getText().toString().trim();
			if (!nt.isEmpty()) {
				HashMap<String, Object> upd = new HashMap<>();
				upd.put("text", nt); upd.put("caption", nt); upd.put("is_edited", true);
				postsRef.child(postId).updateChildren(upd);
			}
		})
		.setNegativeButton("Cancel", null).show();
	}
	
	// =========================================================================
	// Forward — opens ForwardChatListActivity (Telegram-style picker)
	// =========================================================================
	//
	// REPLACES the old in-place AlertDialog (_forwardPosts /
	// _buildAndShowForwardDialog / _showForwardDialog — 3 methods, ~250 lines).
	// That flow's own comment claimed "Forward to DM chats AND owned/subscribed
	// channels" — the "subscribed" part was actually a SECURITY BUG: it built
	// the channel-target list from user_channels, which stores owner and
	// subscriber identically (no role field), and the write path never checked
	// ownership either. Any subscriber of any channel could forward a post INTO
	// that channel as if they owned it. ForwardChatListActivity fixes this by
	// building its channel-target list from channels.orderByChild("owner_uid")
	// instead — only channels you actually own ever appear as targets.
	//
	// It also fixes: no chat-list-preview update / no push notification on
	// forward (recipient had no way to know a message arrived), and
	// voice_url/duration/link_preview_* silently dropped on forward. See
	// ForwardChatListActivity's and ForwardHelper's class docs for details.
	private void _forwardPosts(final List<HashMap<String, Object>> posts) {
		if (posts == null || posts.isEmpty()) return;
		Intent i = new Intent(this, ForwardChatListActivity.class);
		i.putExtra(ForwardChatListActivity.EXTRA_MESSAGES_JSON, new Gson().toJson(posts));
		// Don't offer THIS channel as a forward target for its own post.
		i.putExtra(ForwardChatListActivity.EXTRA_EXCLUDE_CHANNEL_ID, channelId);
		// Enables ForwardChatListActivity's fresh, un-bypassable re-check of this
		// channel's restrict_forwarding flag right before it writes anything —
		// see that class's doc comment ("DEFENSE IN DEPTH"). The UI-level gate
		// (hiding the Forward row below) is the first check; this is the second,
		// independent one.
		i.putExtra(ForwardChatListActivity.EXTRA_SOURCE_CHANNEL_ID, channelId);
		startActivity(i);
	}
	
	// =========================================================================
	// Media picker for image posts
	// =========================================================================
	private void _openMediaPickerForPost() {
		TelegramMediaPicker picker = TelegramMediaPicker.newInstance();
		picker.setListener((uriStrings, caption) -> {
			for (String u : uriStrings) _sendPostInstant(Uri.parse(u), caption);
		});
		picker.show(getSupportFragmentManager(), "channel_media_picker");
	}
	
	// =========================================================================
	// Instant image post (WhatsApp-style)
	// =========================================================================
	private void _sendPostInstant(Uri imageUri, String caption) {
		if (!_canPost()) return;
		if (imageUri == null) return;
		String tempId = "temp_" + System.currentTimeMillis();
		long   ts     = System.currentTimeMillis();
		
		String savedReplyId   = replyToId;
		String savedReplyPrev = replyPreview;
		
		HashMap<String, Object> temp = new HashMap<>();
		temp.put("postId",             tempId);
		temp.put("sender_uid",         myUid);
		temp.put("channel_id",         channelId);
		temp.put("type",               "image");
		temp.put("image_url",          "");
		temp.put("local_uri",          imageUri.toString());
		temp.put("caption",            caption != null ? caption : "");
		temp.put("text",               "");
		temp.put("timestamp",          ts);
		temp.put("is_edited",          false);
		temp.put("is_pinned",          false);
		temp.put("is_forwarded",       false);
		temp.put("is_deleted_for_all", false);
		temp.put("upload_state",       "uploading");
		
		if (savedReplyId != null) {
			temp.put("reply_to_id",   savedReplyId);
			temp.put("reply_preview", savedReplyPrev);
			temp.put("reply_sender",  "Channel");
		}
		
		pendingRetryUris.put(tempId, imageUri);
		allPostList.add(temp);
		_applySearch();
		listview1.post(() -> listview1.setSelection(adapter.getCount() - 1));
		isAtBottom = true;
		_clearReply();
		
		final String finalCaption  = caption != null ? caption : "";
		final String finalReplyId  = savedReplyId;
		final String finalReplyPrev= savedReplyPrev;
		
		ImageEditHelper.compressImage(this, imageUri, new ImageEditHelper.CompressCallback() {
			@Override public void onSuccess(Uri compressed) {
				_doUpload(tempId, compressed, finalCaption, finalReplyId, finalReplyPrev);
			}
			@Override public void onError(String e) {
				_doUpload(tempId, imageUri, finalCaption, finalReplyId, finalReplyPrev);
			}
		});
	}
	
	/** Issue 6 — Glide.preload() before temp removal for seamless swap */
	private void _doUpload(String tempId, Uri uri, String caption,
	String repId, String repPrev) {
		UploadHelper.uploadImage(this, uri, new UploadHelper.UploadCallback() {
			@Override public void onSuccess(String url) {
				runOnUiThread(() -> {
					// Pre-warm Glide disk cache so the real post shows instantly
					Glide.with(getApplicationContext())
					.load(url)
					.diskCacheStrategy(DiskCacheStrategy.ALL)
					.preload();
					
					retryQueue.onSuccess(tempId);
					_removeTempPost(tempId);
					pendingRetryUris.remove(tempId);
					_pushImagePostToFirebase(url, caption, repId, repPrev);
				});
			}
			@Override public void onError(String err) {
				runOnUiThread(() -> _setTempState(tempId, "failed"));
			}
		});
	}
	
	private void _removeTempPost(String tempId) {
		for (int i = 0; i < allPostList.size(); i++) {
			if (tempId.equals(allPostList.get(i).get("postId"))) {
				allPostList.remove(i); _applySearch(); return;
			}
		}
	}
	
	private void _setTempState(String tempId, String state) {
		for (HashMap<String, Object> post : allPostList) {
			if (tempId.equals(post.get("postId"))) {
				post.put("upload_state", state); _applySearch();
				if ("failed".equals(state)) {
					retryQueue.enqueue(tempId, () -> _performRetry(tempId));
				}
				return;
			}
		}
	}

	/** Shared by manual tap-to-retry AND the automatic backoff queue. */
	private void _performRetry(String tempId) {
		if (pendingRetryVoiceFiles.containsKey(tempId)) _retryVoiceUpload(tempId);
		else _retryUpload(tempId);
	}

	private void _scheduleComposeLinkPreview(String text) {
		String url = LinkPreviewHelper.extractFirstUrl(text);

		if (url == null) {
			if (pendingComposePreviewCheck != null) composePreviewHandler.removeCallbacks(pendingComposePreviewCheck);
			composedPreview = null;
			composedPreviewUrl = null;
			if (layoutComposeLinkPreview != null) layoutComposeLinkPreview.setVisibility(View.GONE);
			return;
		}

		if (url.equals(composedPreviewUrl)) return;

		if (pendingComposePreviewCheck != null) composePreviewHandler.removeCallbacks(pendingComposePreviewCheck);
		composedPreview = null;
		composedPreviewUrl = url;

		pendingComposePreviewCheck = () -> {
			if (layoutComposeLinkPreview == null) return;
			layoutComposeLinkPreview.setVisibility(View.VISIBLE);
			if (progressComposeLinkPreview != null) progressComposeLinkPreview.setVisibility(View.VISIBLE);
			if (imgComposeLinkPreview != null) imgComposeLinkPreview.setVisibility(View.GONE);
			if (txtComposeLinkPreviewTitle != null) txtComposeLinkPreviewTitle.setText("Fetching preview…");
			if (txtComposeLinkPreviewDesc != null) txtComposeLinkPreviewDesc.setText("");

			LinkPreviewHelper.fetch(url, preview -> {
				if (!url.equals(composedPreviewUrl)) return;
				if (progressComposeLinkPreview != null) progressComposeLinkPreview.setVisibility(View.GONE);

				if (preview == null) {
					if (layoutComposeLinkPreview != null) layoutComposeLinkPreview.setVisibility(View.GONE);
					composedPreviewUrl = null;
					return;
				}
				composedPreview = preview;
				if (txtComposeLinkPreviewTitle != null)
					txtComposeLinkPreviewTitle.setText(preview.title != null ? preview.title : url);
				if (txtComposeLinkPreviewDesc != null)
					txtComposeLinkPreviewDesc.setText(preview.description != null ? preview.description : "");
				if (preview.imageUrl != null && !preview.imageUrl.isEmpty() && imgComposeLinkPreview != null) {
					imgComposeLinkPreview.setVisibility(View.VISIBLE);
					Glide.with(this).load(preview.imageUrl)
						.diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
						.into(imgComposeLinkPreview);
				}
			});
		};
		composePreviewHandler.postDelayed(pendingComposePreviewCheck, 600);
	}

	
	private void _retryVoiceUpload(String tempId) {
		File audioFile = pendingRetryVoiceFiles.get(tempId);
		if (audioFile == null || !audioFile.exists()) {
			Toast.makeText(this, "Cannot retry — recording no longer available", Toast.LENGTH_SHORT).show();
			return;
		}
		int durationSec = 0;
		for (HashMap<String, Object> post : allPostList) {
			if (tempId.equals(post.get("postId"))) {
				Object d = post.get("duration");
				if (d instanceof Number) durationSec = ((Number) d).intValue();
				post.put("upload_state", "uploading");
				_applySearch();
				break;
			}
		}
		final int fDuration = durationSec;
		VoiceUploadHelper.uploadVoice(audioFile, new VoiceUploadHelper.UploadCallback() {
			@Override public void onSuccess(String fileUrl) {
				runOnUiThread(() -> {
					retryQueue.onSuccess(tempId);
					_removeTempPost(tempId);
					pendingRetryVoiceFiles.remove(tempId);
					_sendVoicePost(fileUrl, fDuration);
					audioFile.delete();
				});
			}
			@Override public void onError(String errorMessage) {
				runOnUiThread(() -> {
					_setTempState(tempId, "failed");
					Toast.makeText(ChannelActivity.this, "Voice upload failed: " + errorMessage, Toast.LENGTH_LONG).show();
				});
			}
		});
	}

	private void _retryUpload(String tempId) {
		Uri uri = pendingRetryUris.get(tempId);
		if (uri == null) {
			Toast.makeText(this, "Cannot retry — source unavailable", Toast.LENGTH_SHORT).show();
			return;
		}
		for (HashMap<String, Object> post : allPostList) {
			if (tempId.equals(post.get("postId"))) {
				post.put("upload_state", "uploading");
				_applySearch();
				String cap = (String) post.get("caption");
				String rId = (String) post.get("reply_to_id");
				String rPv = (String) post.get("reply_preview");
				_doUpload(tempId, uri, cap != null ? cap : "", rId, rPv);
				return;
			}
		}
	}
	
	private void _pushImagePostToFirebase(String imgUrl, String caption,
	String repId, String repPrev) {
		String postId = postsRef.push().getKey();
		if (postId == null) return;
		long ts = System.currentTimeMillis();
		HashMap<String, Object> post = new HashMap<>();
		post.put("sender_uid",         myUid);
		post.put("channel_id",         channelId);
		post.put("type",               "image");
		post.put("image_url",          imgUrl);
		post.put("caption",            caption);
		post.put("text",               "");
		post.put("timestamp",          ts);
		post.put("is_edited",          false);
		post.put("is_pinned",          false);
		post.put("is_forwarded",       false);
		post.put("is_deleted_for_all", false);
		if (repId != null && !repId.isEmpty()) {
			post.put("reply_to_id",   repId);
			post.put("reply_preview", repPrev);
			post.put("reply_sender",  "Channel");
		}
		postsRef.child(postId).setValue(post);
		ContentTrackingHelper.trackSent(myUid, "image", caption, imgUrl, null, "channel", channelId, postId);
		_updateChannelLastPost("📷 Photo", ts);
		_sendChannelNotification("📷 Photo", imgUrl);
	}
	
	// =========================================================================
	// Issue 7 — Channel info with real-time subscriber count fix
	// =========================================================================
	private void _loadChannelInfo() {
		channelRef.addValueEventListener(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				channelName  = snap.child("name").getValue(String.class);
				channelBio   = snap.child("description").getValue(String.class);
				channelPhoto = snap.child("photo_url").getValue(String.class);
				Long subCount= snap.child("subscriber_count").getValue(Long.class);
				
				subscriberCount = subCount != null ? subCount : 0;
				
				if (txt_channel_name != null && channelName != null)
				txt_channel_name.setText(channelName);
				_updateSubCountText(); // Issue 5 + 7
				
				boolean isVerified = Boolean.TRUE.equals(snap.child("is_verified").getValue(Boolean.class));
				ImageView badge = findViewById(R.id.img_verified_badge);
				if (badge != null) badge.setVisibility(isVerified ? View.VISIBLE : View.GONE);
				if (txt_channel_bio != null) {
					// Toolbar no longer shows the channel description —
					// it lives on the Channel Profile screen instead.
					txt_channel_bio.setVisibility(View.GONE);
				}
				if (img_channel_photo != null && channelPhoto != null && !channelPhoto.isEmpty())
				Glide.with(getApplicationContext()).load(channelPhoto)
				.circleCrop().diskCacheStrategy(DiskCacheStrategy.ALL)
				.placeholder(R.drawable.ic_channel).into(img_channel_photo);
				
				// Issue 7: Auto-heal subscriber_count if stale
				_healSubscriberCount();
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	/**
* Issue 7 — Verify stored subscriber_count against actual channel_subscribers count.
* Runs once when channel is opened, fixes any stale value silently.
*/	
	private void _healSubscriberCount() {
		database.getReference("channel_subscribers").child(channelId)
		.addListenerForSingleValueEvent(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				long realCount = snap.getChildrenCount();
				if (realCount != subscriberCount) {
					channelRef.child("subscriber_count").setValue(realCount);
					subscriberCount = realCount;
					_updateSubCountText();
				}
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	private String _formatCount(long n) {
		if (n >= 1_000_000) return String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0);
		if (n >= 1_000)     return String.format(Locale.getDefault(), "%.1fK", n / 1_000.0);
		return String.valueOf(n);
	}
	
	private void _checkOwnership() {
		channelRef.child("owner_uid").addListenerForSingleValueEvent(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				String ownerUid = snap.getValue(String.class);
				isOwner = myUid.equals(ownerUid);
				
				if (btn_subscribe!= null) btn_subscribe.setVisibility(isOwner ? View.GONE : View.VISIBLE);
				// Settings icon removed from the toolbar — the owner reaches
				// Channel Settings via Channel Profile → Edit instead.
				if (btn_settings != null) btn_settings.setVisibility(View.GONE);
				if (btn_mute     != null) btn_mute.setVisibility(isOwner ? View.GONE : View.VISIBLE);
				
				if (isOwner) {
					if (linear98 != null) linear98.setVisibility(View.VISIBLE);
				} else {
					// Non-owner: compose bar only shows if they've been made
					// an admin — separate lookup since owner_uid alone can't
					// answer this.
					database.getReference("channel_admins").child(channelId).child(myUid)
						.addListenerForSingleValueEvent(new ValueEventListener() {
							@Override public void onDataChange(@NonNull DataSnapshot adminSnap) {
								isAdmin = adminSnap.exists() && Boolean.TRUE.equals(adminSnap.getValue(Boolean.class));
								if (linear98 != null) linear98.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
							}
							@Override public void onCancelled(@NonNull DatabaseError e) {
								if (linear98 != null) linear98.setVisibility(View.GONE);
							}
						});
				}
				
				_updateSubscribeBanner();
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	/**
	 * Fetches channels/{channelId}/restrict_forwarding once on channel open.
	 * Live updates aren't needed here — if the owner flips this mid-session,
	 * the NEXT post's action sheet (or a re-open) picks it up; the actual
	 * write is independently re-checked fresh from the DB in
	 * ForwardChatListActivity right before it forwards anything, so a stale
	 * value here can never let a restricted forward through — it can only
	 * ever cause the Forward row to be hidden one post-action-sheet-open late.
	 */
	private void _checkForwardRestriction() {
		channelRef.child("restrict_forwarding").addListenerForSingleValueEvent(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				restrictForwarding = Boolean.TRUE.equals(snap.getValue(Boolean.class));
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	private void _checkReplyPermission() {
		channelRef.child("allow_replies").addListenerForSingleValueEvent(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				// Field absent (older channel, created before this setting
				// existed) must read as allowed, not blocked — hence the
				// explicit "isn't false" check rather than "is true".
				allowReplies = !Boolean.FALSE.equals(snap.getValue(Boolean.class));
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	private void _checkSubscription() {
		database.getReference("channel_subscribers").child(channelId).child(myUid)
		.addValueEventListener(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				isSubscribed = snap.exists();
				// SELF-HEAL: topic subscription device par rehti hai, DB mein nahi.
				// Jinhone topic-code aane se pehle subscribe kiya tha, ya jinhone
				// reinstall / device change kiya hai — unka subscription missing
				// hota hai aur channel post kabhi nahi pahunchti. Screen khulte hi
				// silently repair kar dete hain (subscribeToTopic idempotent hai).
				if (isSubscribed && channelId != null) FcmTokenManager.subscribeChannel(channelId);
				_updateSubscribeBanner();
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	private void _updateSubscribeBanner() {
		if (btn_subscribe == null || isOwner) return;
		if (isSubscribed) {
			btn_subscribe.setVisibility(View.GONE);
		} else {
			btn_subscribe.setVisibility(View.VISIBLE);
			if (txt_subscribe_label != null)
			txt_subscribe_label.setText("SUBSCRIBE to this Channel");
			btn_subscribe.setBackgroundColor(Ink.c("#1A73E8"));
		}
	}
	
	private void _toggleSubscribe() {
		if (isOwner) return;
		DatabaseReference subRef    = database.getReference("channel_subscribers").child(channelId).child(myUid);
		DatabaseReference userChRef = database.getReference("user_channels").child(myUid).child(channelId);
		if (isSubscribed) {
			subRef.removeValue();
			userChRef.removeValue();
			channelRef.child("subscriber_count").setValue(ServerValue.increment(-1));
			// FIX: pehle bina completion listener ke call hota tha — fail ho jaye
			// to kisi ko pata hi nahi chalta tha.
			FcmTokenManager.unsubscribeChannel(channelId);
			Toast.makeText(this, "Unsubscribed", Toast.LENGTH_SHORT).show();
		} else {
			subRef.setValue(true);
			userChRef.setValue(true);
			channelRef.child("subscriber_count").setValue(ServerValue.increment(1));
			FcmTokenManager.subscribeChannel(channelId);
			Toast.makeText(this, "Subscribed ✓", Toast.LENGTH_SHORT).show();
		}
	}
	
	private void _loadOlderPosts() {
		isLoadingMorePosts = true;
		if (progressLoadMore != null) progressLoadMore.setVisibility(View.VISIBLE);

		// Anchor on a real POST id rather than a row index. adapter.getCount()
		// includes interleaved native-ad rows, so a raw index delta drifts the
		// moment loading more posts makes another ad slot eligible — which is
		// exactly what produced the "teleport" jump. Also capture the anchor
		// row's pixel offset so the restore is exact rather than snapping the
		// row to the very top.
		final int anchorRow = listview1.getFirstVisiblePosition();
		final View anchorView = listview1.getChildAt(0);
		final int anchorOffset = (anchorView == null) ? 0 : anchorView.getTop();
		String anchorId = null;
		if (anchorRow >= 0 && anchorRow < adapter.getCount()) {
			Object item = adapter.getItem(anchorRow);
			if (item instanceof HashMap) anchorId = (String) ((HashMap<?, ?>) item).get("postId");
		}
		final String anchorPostId = anchorId;

		if (postsListener != null) {
			postsRef.orderByChild("timestamp").limitToLast(postsPageSize)
				.removeEventListener(postsListener);
		}
		postsPageSize += POSTS_PAGE_SIZE;
		isFirstLoad = false;

		postsListener = postsRef.orderByChild("timestamp").limitToLast(postsPageSize)
			.addValueEventListener(new ValueEventListener() {
				@Override public void onDataChange(@NonNull DataSnapshot snap) {
					// Asked for postsPageSize but got fewer back → that's the
					// channel's entire history, stop trying to load more.
					if (snap.getChildrenCount() < postsPageSize) hasMorePosts = false;

					_rebuildPostList(snap);
					_applySearch();

					// Put the user back exactly where they were, on the same
					// post, at the same pixel offset — UNLESS a jump-to-post
					// is driving this pagination, in which case that owns
					// positioning and restoring the old anchor would fight it.
					if (anchorPostId != null && activeScrollTargetId == null) {
						int newRow = -1;
						for (int i = 0; i < filteredList.size(); i++) {
							if (anchorPostId.equals(filteredList.get(i).get("postId"))) {
								newRow = adapter.displayPositionForDataIndex(i);
								break;
							}
						}
						if (newRow >= 0) {
							final int targetRow = newRow;
							listview1.post(() -> listview1.setSelectionFromTop(targetRow, anchorOffset));
						}
					}
					isLoadingMorePosts = false;
					if (progressLoadMore != null) progressLoadMore.setVisibility(View.GONE);
				}
				@Override public void onCancelled(@NonNull DatabaseError e) {
					isLoadingMorePosts = false;
					if (progressLoadMore != null) progressLoadMore.setVisibility(View.GONE);
				}
			});
	}

	/**
	 * Parses a posts snapshot into allPostList — shared by the live paginated
	 * listener and the scroll-up "load older" path so both stay in sync.
	 */
	private void _rebuildPostList(DataSnapshot snap) {
		boolean snapWasEmpty = !snap.hasChildren();
		// Keep in-flight temp posts
		List<HashMap<String, Object>> temps = new ArrayList<>();
		for (HashMap<String, Object> p : allPostList) {
			String pid = (String) p.get("postId");
			if (pid != null && pid.startsWith("temp_")) temps.add(p);
		}
		allPostList.clear();

		for (DataSnapshot child : snap.getChildren()) {
			HashMap<String, Object> post = new HashMap<>();
			post.put("postId",             child.getKey());
			post.put("sender_uid",         child.child("sender_uid").getValue(String.class));
			post.put("channel_id",         child.child("channel_id").getValue(String.class));
			post.put("type",               child.child("type").getValue(String.class));
			post.put("text",               child.child("text").getValue(String.class));
			post.put("image_url",          child.child("image_url").getValue(String.class));
			post.put("voice_url",          child.child("voice_url").getValue(String.class));
			post.put("duration",           child.child("duration").getValue(Long.class));
			post.put("caption",            child.child("caption").getValue(String.class));
			post.put("timestamp",          child.child("timestamp").getValue(Long.class));
			post.put("is_edited",          child.child("is_edited").getValue(Boolean.class));
			post.put("is_pinned",          child.child("is_pinned").getValue(Boolean.class));
			post.put("is_forwarded",       child.child("is_forwarded").getValue(Boolean.class));
			post.put("is_deleted_for_all", child.child("is_deleted_for_all").getValue(Boolean.class));
			post.put("reply_to_id",        child.child("reply_to_id").getValue(String.class));
			post.put("reply_sender",       child.child("reply_sender").getValue(String.class));
			post.put("link_preview_url",   child.child("link_preview_url").getValue(String.class));
			post.put("link_preview_title", child.child("link_preview_title").getValue(String.class));
			post.put("link_preview_desc",  child.child("link_preview_desc").getValue(String.class));
			post.put("link_preview_image", child.child("link_preview_image").getValue(String.class));
			post.put("link_preview_site",  child.child("link_preview_site").getValue(String.class));
			post.put("reply_preview",      child.child("reply_preview").getValue(String.class));
			post.put("reply_count",        child.child("reply_count").getValue(Long.class));
			post.put("link_preview_url",   child.child("link_preview_url").getValue(String.class));
			post.put("link_preview_title", child.child("link_preview_title").getValue(String.class));
			post.put("link_preview_desc",  child.child("link_preview_desc").getValue(String.class));
			post.put("link_preview_image", child.child("link_preview_image").getValue(String.class));
			post.put("link_preview_site",  child.child("link_preview_site").getValue(String.class));
			HashMap<String, Object> likes = new HashMap<>();
			if (child.child("likes").exists()) {
				for (DataSnapshot ls : child.child("likes").getChildren())
				likes.put(ls.getKey(), true);
			}
			post.put("likes", likes);

			// ── View count ────────────────────────────────────────────────
			// Views used to be a nested {uid: true, uid: true, ...} map
			// stored INSIDE every post, so fetching the post list (even
			// limitToLast(10)) also downloaded that entire map for every
			// post — the real cause of slow channel loads and of the
			// back-gesture stutter (main thread parsing oversized JSON).
			//
			// New posts store a lightweight view_count integer instead, with
			// per-viewer records in a separate channel_post_views node that
			// post loading never touches.
			//
			// BACKWARD COMPATIBILITY (this is what I got wrong before and
			// broke view counts): posts created BEFORE this change have no
			// view_count field at all — only the old views map. So we read
			// view_count when it exists, and otherwise fall back to counting
			// the legacy map, which is already inside this snapshot and
			// therefore costs nothing extra to count here. Each legacy post
			// then gets migrated once, in the background, so it stops being
			// expensive from the next load onward — the channel heals itself
			// as it's used, with no admin migration step needed.
			Long vc = child.child("view_count").getValue(Long.class);
			long viewCount;
			if (vc != null) {
				viewCount = vc;
			} else if (child.child("views").exists()) {
				viewCount = child.child("views").getChildrenCount();
				_migrateLegacyViews(child.getKey(), viewCount);
			} else {
				viewCount = 0L;
			}
			post.put("view_count", viewCount);

			if (!Boolean.TRUE.equals(post.get("is_deleted_for_all"))
					&& child.getKey() != null && !child.getKey().startsWith("temp_")) {
				_maybeRecordView(child.getKey());
			}

			// ── Reactions: { uid: emoji } → count per emoji + my reaction ──
			HashMap<String, Integer> reactionCounts = new HashMap<>();
			String myReaction = null;
			for (DataSnapshot r : child.child("reactions").getChildren()) {
				String emoji = r.getValue(String.class);
				if (emoji == null) continue;
				Integer cur = reactionCounts.get(emoji);
				reactionCounts.put(emoji, cur == null ? 1 : cur + 1);
				if (myUid != null && myUid.equals(r.getKey())) myReaction = emoji;
			}
			post.put("reaction_counts", reactionCounts);
			post.put("my_reaction",     myReaction);

			if (Boolean.TRUE.equals(post.get("is_deleted_for_all"))) continue; // vanish completely, like Telegram
			allPostList.add(post);
		}
		allPostList.addAll(temps);

		if (layoutEmptyPosts != null) {
			layoutEmptyPosts.setVisibility(
				(allPostList.isEmpty() && snapWasEmpty) ? View.VISIBLE : View.GONE);
		}

		// Mirror the confirmed set into the local cache so the next open is
		// instant + offline-capable (same pattern as chat's message cache).
		if (postCache != null && channelId != null) {
			postCache.cachePosts(channelId, allPostList);
		}
	}

	/**
	 * Records a view for this post, WITHOUT ever touching the post's own
	 * data (so fetching posts for display never has to download per-viewer
	 * records — see the big comment in _rebuildPostList for the full story).
	 *
	 * Fully async, runs after the post list has already rendered — never
	 * blocks or delays anything the user sees.
	 *
	 * Cheap single-child existence check first (not a full-map download) to
	 * avoid double-counting the same viewer across app sessions; only on a
	 * genuinely new view does it write the per-viewer record AND atomically
	 * increment the lightweight view_count on the post itself.
	 */
	/**
	 * One-time, self-healing migration for a post still using the old
	 * nested views map. Converts it to the lightweight view_count field and
	 * removes the heavy map, so this post stops bloating every future post
	 * fetch for everyone.
	 *
	 * Runs async, off the render path — the count shown to the user right
	 * now comes from the snapshot we already have, so nothing waits on this.
	 *
	 * Note: the old per-viewer records are dropped rather than copied into
	 * channel_post_views (copying potentially thousands of entries per post
	 * would be far more expensive than the problem it solves). The count
	 * itself is preserved exactly; the only side effect is that someone who
	 * viewed a legacy post before the migration can be counted once more if
	 * they open it again later. For a view counter that's an acceptable
	 * trade — no count is ever lost, it can only drift slightly upward.
	 */
	private void _migrateLegacyViews(String postId, long currentCount) {
		if (postId == null || postId.startsWith("temp_")) return;
		if (!migratedViewPosts.add(postId)) return; // already handled this session

		HashMap<String, Object> updates = new HashMap<>();
		updates.put("channel_posts/" + channelId + "/" + postId + "/view_count", currentCount);
		updates.put("channel_posts/" + channelId + "/" + postId + "/views", null); // drop the heavy map
		database.getReference().updateChildren(updates)
			.addOnFailureListener(e -> Log.e("ChannelViews", "Legacy views migration failed", e));
	}

	private void _maybeRecordView(String postId) {
		if (viewedThisSession.contains(postId)) return;
		viewedThisSession.add(postId);

		DatabaseReference myViewRef = database.getReference("channel_post_views")
			.child(channelId).child(postId).child(myUid);
		myViewRef.addListenerForSingleValueEvent(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				if (snap.exists()) return; // already counted in a previous session
				HashMap<String, Object> updates = new HashMap<>();
				updates.put("channel_post_views/" + channelId + "/" + postId + "/" + myUid, true);
				updates.put("channel_posts/" + channelId + "/" + postId + "/view_count", ServerValue.increment(1));
				database.getReference().updateChildren(updates)
					.addOnFailureListener(e -> Log.e("ChannelViews", "Failed to record view", e));
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}

	// =========================================================================
	// Issue 4 + 8 — Listen posts with view tracking + smart first-scroll
	// =========================================================================
	private void _listenPosts() {
		postsListener = postsRef.orderByChild("timestamp").limitToLast(postsPageSize)
		.addValueEventListener(new ValueEventListener() {
			@Override
			public void onDataChange(@NonNull DataSnapshot snap) {
				if (snap.getChildrenCount() < postsPageSize) hasMorePosts = false;
				int prevCount = allPostList.size();
				_rebuildPostList(snap);

				boolean wasFirst    = isFirstLoad;
				if (isFirstLoad) {
					isFirstLoad = false;
					if (layoutInitialLoading != null) layoutInitialLoading.setVisibility(View.GONE);
				}
				canPaginate = true; // covers the no-cache case (first ever open of this channel)
				boolean wasAtBottom = isAtBottom;
				_applySearch();
				
				// ── Issue 8: Smart first scroll ───────────────────────────
				if (wasFirst) {
					if (pendingScrollPostId != null) {
						final String target = pendingScrollPostId;
						pendingScrollPostId = null;
						listview1.post(() -> scrollToPost(target));
					} else {
					int unreadPos = _findFirstUnreadPosition();
					if (unreadPos > 0) {
						// Scroll to first unread so user sees context
						final int pos = unreadPos;
						listview1.post(() -> {
							listview1.setSelection(pos);
						});
					} else {
						// No unread (or all new) — go to bottom
						listview1.post(() -> listview1.setSelection(adapter.getCount() - 1));
					}
					}
				} else if (wasAtBottom && activeScrollTargetId == null && !isLoadingMorePosts
						&& System.currentTimeMillis() > suppressAutoScrollUntilMs) {
					Log.d("MinigramScroll", "Channel: wasAtBottom, auto-scroll (prev=" + prevCount
							+ " now=" + allPostList.size() + ")");
					listview1.post(() -> listview1.setSelection(adapter.getCount() - 1));
				} else if (!wasFirst && allPostList.size() > prevCount) {
					Log.d("MinigramScroll", "Channel: NOT at bottom, showNewPostBubble() (prev=" + prevCount
							+ " now=" + allPostList.size() + ")");
					newPostCount += (allPostList.size() - prevCount);
					_showNewPostBubble();
				} else {
					Log.d("MinigramScroll", "Channel: no branch taken — wasFirst=" + wasFirst
							+ " wasAtBottom=" + wasAtBottom + " prev=" + prevCount
							+ " now=" + allPostList.size());
				}
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	/**
* Issue 8 — Returns position of the first post with timestamp > lastReadTimestamp.
* Returns -1 if everything is read or list is empty.
*/	
	private int _findFirstUnreadPosition() {
		if (lastReadTimestamp == 0) return -1; // Never opened before — show bottom
		for (int i = 0; i < allPostList.size(); i++) {
			Object tsObj = allPostList.get(i).get("timestamp");
			if (tsObj instanceof Long && (Long) tsObj > lastReadTimestamp) {
				return i;
			}
		}
		return -1; // All read
	}
	
	/** Phase 3 — MyProfileActivity's "View" action. ListView fully rebinds
	 *  visible rows on notifyDataSetChanged() (no DiffUtil involved, unlike
	 *  ChatMessageRecyclerAdapter), so no equivalent bypass is needed here —
	 *  a plain notifyDataSetChanged() after setHighlightedPostId() is enough
	 *  for the tint to actually show. */
	private void scrollToPost(String postId) {
		if (postId == null) return;
		// Claim the lock for the WHOLE operation — including any pagination
		// it has to trigger to reach an older post. Everything else that
		// auto-scrolls checks this and stands down until it's released.
		activeScrollTargetId = postId;
		isAtBottom = false; // we're deliberately leaving the bottom
		// Outlives the highlight so a late listener fire can't drag the list
		// back to the newest post after we've landed on the target.
		suppressAutoScrollUntilMs = System.currentTimeMillis() + 15000;
		_scrollToPost(postId, 0);
	}

	private void _releaseScrollLock(String postId) {
		if (postId.equals(activeScrollTargetId)) activeScrollTargetId = null;
	}

	/**
	 * The target post is very often OLDER than the initially loaded page
	 * (posts load with limitToLast), so it simply isn't in the list yet —
	 * that's why this used to give up silently and leave the list sitting
	 * at the newest post. Pages back through history until the post is
	 * actually loaded, then smooth-scrolls to it.
	 */
	private void _scrollToPost(String postId, int attempt) {
		if (!postId.equals(activeScrollTargetId)) return; // superseded by a newer request
		for (int i = 0; i < filteredList.size(); i++) {
			if (postId.equals(filteredList.get(i).get("postId"))) {
				final int pos = adapter.displayPositionForDataIndex(i);
				adapter.setHighlightedPostId(postId);
				adapter.notifyDataSetChanged();
				listview1.post(() -> {
					// Land the post a little below the top edge so it reads
					// as "here it is in context", not jammed under the
					// toolbar — and ANIMATE there rather than teleporting.
					int offset = Math.max(0, listview1.getHeight() / 4);
					listview1.smoothScrollToPositionFromTop(pos, offset, 450);
				});
				new Handler(Looper.getMainLooper()).postDelayed(() -> {
					adapter.setHighlightedPostId(null);
					adapter.notifyDataSetChanged();
					_releaseScrollLock(postId);
				}, 2500);
				return;
			}
		}

		// Bail out once there's genuinely nothing older left to fetch, or
		// after a sane number of pages, so a deleted/invalid id can never
		// spin forever — and always release the lock on the way out.
		if (!hasMorePosts || attempt >= 10) {
			_releaseScrollLock(postId);
			return;
		}

		if (!isLoadingMorePosts) _loadOlderPosts();
		new Handler(Looper.getMainLooper()).postDelayed(
			() -> _scrollToPost(postId, attempt + 1), 700);
	}
	
	// =========================================================================
	// Search
	// =========================================================================
	private void _applySearch() {
		filteredList.clear();
		if (searchQuery.isEmpty()) {
			filteredList.addAll(allPostList);
		} else {
			String q = searchQuery.toLowerCase(Locale.getDefault());
			for (HashMap<String, Object> post : allPostList) {
				String t = (String) post.get("text");
				String c = (String) post.get("caption");
				if ((t != null && t.toLowerCase(Locale.getDefault()).contains(q))
				|| (c != null && c.toLowerCase(Locale.getDefault()).contains(q)))
				filteredList.add(post);
			}
		}
		if (adapter != null) {
			if (isAtBottom && searchQuery.isEmpty()) adapter.notifyDataSetChanged();
			else                                     adapter.refreshWithoutScroll();
		}
	}
	
	private void _openSearch() {
		isSearchActive = true;
		if (layout_search_bar != null) layout_search_bar.setVisibility(View.VISIBLE);
		if (edit_search       != null) { edit_search.requestFocus(); edit_search.setText(""); }
	}
	
	private void _closeSearch() {
		isSearchActive = false; searchQuery = "";
		if (layout_search_bar != null) layout_search_bar.setVisibility(View.GONE);
		if (edit_search       != null) edit_search.setText("");
		_applySearch();
		android.view.inputmethod.InputMethodManager imm =
		(android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		if (imm != null && edit_search != null)
		imm.hideSoftInputFromWindow(edit_search.getWindowToken(), 0);
	}
	
	// =========================================================================
	// Open comments / reply thread
	// =========================================================================
	private void _openReplies(String postId) {
		if (!isOwner && !allowReplies) {
			Toast.makeText(this, "The channel owner has turned off replies", Toast.LENGTH_SHORT).show();
			return;
		}
		Intent i = new Intent(this, ChannelReplyActivity.class);
		i.putExtra("channel_id", channelId);
		i.putExtra("post_id",    postId);
		i.putExtra("is_owner",   isOwner);
		startActivity(i);
	}
	
	// =========================================================================
	// Settings, Mute
	// =========================================================================
	private void _openSettings() {
		startActivity(new Intent(this, ChannelSettingsActivity.class)
		.putExtra("channel_id",     channelId)
		.putExtra("channel_handle", channelHandle));
	}
	
	/**
* Opens per-channel notification image blur sheet.
* Accessible to ALL users (owners via 3-dot menu, subscribers via long-press mute icon).
*/	
	private void _openNotifImagePrivacy() {
		NotificationImagePrivacySheet
		.forChannel(channelId,
		channelName  != null ? channelName  : "",
		channelPhoto != null ? channelPhoto : "")
		.show(getSupportFragmentManager(), "notif_img_privacy");
	}
	
	private void _showReportMenu() {
		android.widget.PopupMenu popup = new android.widget.PopupMenu(this, txt_channel_name);
		popup.getMenu().add(0, 1, 0, "Report Channel");
		popup.setOnMenuItemClickListener(item -> { _confirmReportChannel(); return true; });
		popup.show();
	}

	private void _confirmReportChannel() {
		String[] reasons = {"Spam", "Inappropriate content", "Harassment", "Impersonation", "Other"};
		new android.app.AlertDialog.Builder(this)
			.setTitle("Report this channel")
			.setItems(reasons, (d, which) -> {
				String reportingUid = FirebaseAuth.getInstance().getUid();
				if (reportingUid == null || channelId == null) return;
				HashMap<String, Object> report = new HashMap<>();
				report.put("channel_id", channelId);
				report.put("reported_by", reportingUid);
				report.put("reason", reasons[which]);
				report.put("timestamp", System.currentTimeMillis());
				database.getReference("channel_reports").push().setValue(report);
				Toast.makeText(this, "Report submitted — thanks for letting us know", Toast.LENGTH_SHORT).show();
			})
			.setNegativeButton("Cancel", null)
			.show();
	}

	private void _toggleMute() {
		isMuted = !isMuted;
		getSharedPreferences("MutedChannels", MODE_PRIVATE).edit()
		.putBoolean(channelId, isMuted).apply();
		if (btn_mute != null)
		btn_mute.setText(isMuted ? "notifications_off" : "notifications");
		Toast.makeText(this, isMuted ? "Channel muted" : "Channel unmuted", Toast.LENGTH_SHORT).show();
	}
	
	private void _showSubscriberMenu() {
		Intent i = new Intent(this, ChannelProfileActivity.class);
		i.putExtra("channel_id", channelId);
		startActivity(i);
	}
	
	// =========================================================================
	// Notifications + last-post metadata
	// =========================================================================
	private void _updateChannelLastPost(String preview, long ts) {
		HashMap<String, Object> upd = new HashMap<>();
		upd.put("last_post_preview", preview);
		upd.put("last_post_ts",      ts);
		channelRef.updateChildren(upd);
	}
	
	/**
	 * Channel post ka push "channel_<id>" topic par bhejta hai.
	 *
	 * FIXES vs purana version:
	 *  • "channel_name" data map mein daala hi nahi jaata tha — receiver ko ""
	 *    milta tha aur setContentTitle("") se BLANK title wali notification banti thi.
	 *  • Topic name ab FcmTokenManager.topicFor() banata hai, jo FCM ke reject
	 *    karne wale characters saaf kar deta hai (invalid topic = poora message drop).
	 *  • Errors ab loudly log hoti hain.
	 */
	private void _sendChannelNotification(String previewText, String imageUrl) {
		if (channelId == null || channelId.isEmpty()) return;

		String safeName = (channelName != null && !channelName.trim().isEmpty())
				? channelName.trim() : "New Post";

		HashMap<String, Object> data = new HashMap<>();
		data.put("title",        safeName);
		data.put("body",         previewText != null ? previewText : "");
		data.put("image",        imageUrl != null ? imageUrl : "");
		data.put("type",         "channel");
		data.put("extra",        channelId);
		data.put("channel_name", safeName);
		data.put("sender_uid",   myUid);

		FCMNotificationSender.sendNotification(data, FcmTokenManager.topicFor(channelId), "",
		new FCMNotificationSender.OnResponse() {
			@Override public void onSuccess(String r) { Log.d("MinigramFCM", "channel push ok"); }
			@Override public void onError(String e)   { Log.e("MinigramFCM", "channel push FAILED: " + e); }
		});
	}

	// _loadFcmAccessToken() removed — notifications now go through the
	// secure Vercel backend (see FCMNotificationSender.sendNotification).
	// service_account.json is no longer bundled in this app at all.
	// =========================================================================
	// onActivityResult — ImageCaptionActivity flow
	// =========================================================================
	@Override
	protected void onActivityResult(int req, int res, Intent data) {
		super.onActivityResult(req, res, data);
		if (req == 2001 && res == RESULT_OK && data != null) {
			ArrayList<Uri> uris = new ArrayList<>();
			if (data.getClipData() != null) {
				android.content.ClipData cd = data.getClipData();
				for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
			} else if (data.getData() != null) {
				uris.add(data.getData());
			}
			if (!uris.isEmpty()) {
				ArrayList<String> strs = new ArrayList<>();
				for (Uri u : uris) strs.add(u.toString());
				Intent intent = new Intent(this, ImageCaptionActivity.class);
				intent.putStringArrayListExtra(ImageCaptionActivity.EXTRA_IMAGE_URIS, strs);
				startActivityForResult(intent, ImageCaptionActivity.REQUEST_CODE);
			}
			return;
		}
		if (req == ImageCaptionActivity.REQUEST_CODE && res == RESULT_OK && data != null) {
			ArrayList<String> uriStrings = data.getStringArrayListExtra(ImageCaptionActivity.EXTRA_IMAGE_URIS);
			String caption = data.getStringExtra(ImageCaptionActivity.EXTRA_CAPTION);
			if (uriStrings != null)
			for (String u : uriStrings)
			_sendPostInstant(Uri.parse(u), caption != null ? caption : "");
		}
	}
	
	// =========================================================================
	// Lifecycle
	// =========================================================================
	@Override
	public void onBackPressed() {
		// Kept for physical-back/older-device compatibility; gesture back on
		// newer devices goes through the OnBackPressedCallback registered in
		// onCreate(), which calls this same shared method.
		_handleBackPress();
	}

	private void _handleBackPress() {
		if (isSearchActive) { _closeSearch(); return; }
		exitInterstitialAd.maybeShow(this, this::finish);
	}
	
	@Override protected void onResume() {
		super.onResume();
		currentChannelId = channelId;
		isFirstLoad = true;
		// Re-register presence
		if (presenceRef != null) {
			presenceRef.setValue(true);
			presenceRef.onDisconnect().removeValue();
		}
		// Screenshot Protection — channel posts are the same kind of content
		// (text/images/voice) as a 1-to-1 chat, but this screen had zero
		// FLAG_SECURE coverage before; ChatActivity/ChatlistActivity already
		// had it. Same pref, same behavior, now applied here too.
		boolean screenshotOff = getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
			.getBoolean(PrivacySecurityActivity.KEY_SCREENSHOT, false);
		if (screenshotOff) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
		else                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
	}
	
	@Override protected void onPause() {
		super.onPause();
		currentChannelId = null;
		if (adapter != null) adapter.stopVoicePlayback();
		if (isRecording) _stopVoiceRecording(true); // discard in-progress recording if user backgrounds app
		
		// Issue 8 — Save last-read timestamp so we know where user left off
		getSharedPreferences("channel_prefs", MODE_PRIVATE).edit()
		.putLong("last_read_" + channelId, System.currentTimeMillis())
		.apply();
		
		// Issue 5 — Remove online presence when leaving
		if (presenceRef != null) presenceRef.removeValue();
	}
	
	@Override protected void onDestroy() {
		super.onDestroy();
		currentChannelId = null;
		if (postsListener != null && postsRef != null)
		postsRef.removeEventListener(postsListener);
		if (presenceRef != null) presenceRef.removeValue();
		if (nativeAdManager != null) nativeAdManager.destroyAll();
		if (connectionMonitor != null) connectionMonitor.stop();
		retryQueue.clear();
	}
	
	private void _createNewPostBubble() {
		newPostBubble = new TextView(this);
		newPostBubble.setVisibility(View.GONE);
		newPostBubble.setTextColor(Color.WHITE);
		newPostBubble.setTextSize(13f);
		newPostBubble.setTypeface(null, Typeface.BOLD);
		newPostBubble.setPadding(dp(18), dp(10), dp(18), dp(10));
		newPostBubble.setGravity(Gravity.CENTER);

		GradientDrawable pill = new GradientDrawable();
		pill.setShape(GradientDrawable.RECTANGLE);
		pill.setCornerRadius(dp(30));
		pill.setColor(Ink.c("#075E54"));
		newPostBubble.setBackground(pill);
		newPostBubble.setElevation(dp(8));

		newPostBubble.setOnClickListener(v -> {
			_hideNewPostBubble();
			// FIX: setSelection() instant-jump karta tha. AbsListView ka apna
			// smoothScrollToPosition() use karo — ListView ke liye ye
			// RecyclerView ke LinearSmoothScroller jaisa hi kaam karta hai,
			// koi extra dependency nahi chahiye.
			listview1.post(() -> listview1.smoothScrollToPosition(adapter.getCount() - 1));
		});

		final ViewGroup root = (ViewGroup) getWindow().getDecorView();
		root.post(() -> {
			FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
			lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
			int bottomMargin = dp(88);
			if (linear98 != null && linear98.getHeight() > 0)
				bottomMargin = linear98.getHeight() + dp(14);
			lp.bottomMargin = bottomMargin;
			root.addView(newPostBubble, lp);
		});
	}

	private void _showNewPostBubble() {
		// DIAGNOSTIC: agar ye null hua, matlab _createNewPostBubble() abhi
		// tak view attach nahi kar paya.
		Log.d("MinigramScroll", "_showNewPostBubble() called, newPostBubble null? " + (newPostBubble == null));
		if (newPostBubble == null) return;
		runOnUiThread(() -> {
			Log.d("MinigramScroll", "_showNewPostBubble() setting VISIBLE, count=" + newPostCount);
			String label = newPostCount == 1 ? "\u2193   1 New Post" : "\u2193   " + newPostCount + " New Posts";
			newPostBubble.setText(label);
			if (newPostBubble.getVisibility() != View.VISIBLE) {
				newPostBubble.setVisibility(View.VISIBLE);
				newPostBubble.setAlpha(0f);
				newPostBubble.setScaleX(0.5f);
				newPostBubble.setScaleY(0.5f);
				newPostBubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
					.setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
			}
		});
	}

	private void _hideNewPostBubble() {
		if (newPostBubble == null || newPostBubble.getVisibility() != View.VISIBLE) return;
		newPostBubble.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(180)
			.withEndAction(() -> {
				newPostBubble.setVisibility(View.GONE);
				newPostBubble.setAlpha(1f);
				newPostCount = 0;
			}).start();
	}

	private int dp(int v) {
		return (int)(v * getResources().getDisplayMetrics().density);
	}
}
