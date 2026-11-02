package com.silu.minigram;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import android.util.Log;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.text.SimpleDateFormat;
import java.util.*;

/**
* ChatlistActivity
*
* FIXES vs previous session:
*  - Popup menu fully restored:
*      My Profile · Privacy & Security · Search People · About · Sign Out
*  - Channel sub-tab pill UI:
*      Active  = blue filled pill  + white bold text
*      Inactive= grey filled pill  + dark grey text
*  - Sub-tab bar animates in/out when switching between tabs
*  - Issue 7: real-time subscriber count via channel_subscribers listener
*  - Issue 9: My Channels / Subscribed filter with clean pill tabs
*/
public class ChatlistActivity extends AppCompatActivity {
	
	// ── State ─────────────────────────────────────────────────────────────────
	private int    currentTab    = 0;
	private int    channelSubTab = 1;      // 0=My Channels, 1=Subscribed  (default = Subscribed)
	private String myUid;
	
	private FirebaseAuth     auth;
	private FirebaseDatabase database;
	private com.google.android.gms.ads.AdView bannerAd;
	// Banner should only ever be visible when BOTH are true: the ad actually
	// loaded, and the user isn't Pro. Tracked separately so the AdListener
	// and the realtime is_pro listener don't fight over visibility.
	private boolean adLoadedOk  = false;
	private boolean userIsPro   = false;
	private DatabaseReference  bannerProRef;
	private ValueEventListener bannerProListener;
	// Track persistent listeners so onDestroy() can detach them (prevents
	// leaks + duplicate callbacks when the activity is recreated).
	private DatabaseReference chatsRef, channelsRef, updateRef;
	private ValueEventListener chatsListener, channelsListener, updateListener;
	
	private long    backPressedTime        = 0L;
	private boolean officialChannelChecked = false;
	
	// ── Views ─────────────────────────────────────────────────────────────────
	private LinearLayout       linear80, linear82, btn_new_chat, linear83, linear84, layout_channel_subtabs, linear85, linearDiscover;
		private TextView iconDiscover, textviewDiscover;
	/** Chat-list filter: false = All, true = Unread only. */
	private boolean unreadOnly = false;
	private FrameLayout        frame_layout81;
	private TextView           textview72, icon_search, icon_menu, icon_fab,
	icon_chats,  textview73,
	icon_channels, textview74,
	icon_profile,  textview75;
	private ListView           listview_chats;
	private FrameLayout        frame_updates_container;
	private boolean            updatesFragmentLoaded = false;
	
	// Sub-tabs (Issue 9)
	private TextView             tab_my_channels, tab_subscribed;
	
	// ── Adapters & data ───────────────────────────────────────────────────────
	private ArrayList<HashMap<String, Object>> chatList            = new ArrayList<>();
	private ArrayList<HashMap<String, Object>> channelList         = new ArrayList<>();
	// Fix: subscriber-count listeners were re-attached on every refire of the
	// outer user_channels listener without ever detaching the old one —
	// a real, compounding listener leak. Track them so we can clean up.
	private final HashMap<String, ValueEventListener> subCountListeners = new HashMap<>();
	private final HashMap<String, DatabaseReference>  subCountRefs      = new HashMap<>();
	// Avoid re-fetching peer/channel profile info (name/photo/is_pro) on
	// every single refire — names/photos rarely change, so cache them and
	// only hit the network for UIDs/channels we haven't resolved yet.
	private final HashMap<String, HashMap<String, Object>> peerInfoCache    = new HashMap<>();
	/**
	 * peerInfoCache used to live ONLY in memory, so every app launch started
	 * empty and re-fetched every chat partner's users/{uid} node over the
	 * network — for people you'd already chatted with many times. That's the
	 * "profiles download again every time you open the app" problem.
	 *
	 * Now it's mirrored to SharedPreferences, so names/photos are on screen
	 * instantly from disk on launch. A background refresh still runs (see
	 * _refreshPeerInfo) so anyone who changed their name or photo still
	 * updates — it just isn't a blocking, every-launch download anymore.
	 */
	private static final String PEER_CACHE_PREFS = "minigram_peer_cache";
	private final HashMap<String, HashMap<String, Object>> channelInfoCache = new HashMap<>();
	private ArrayList<HashMap<String, Object>> filteredChannelList = new ArrayList<>();
	
	private ChatAdapter    chatAdapter;
	private ChannelAdapter channelAdapter;
	private SkeletonAdapter skeletonAdapter;
	
	private boolean chatsLoaded    = false;
	private boolean channelsLoaded = false;
	private ValueAnimator shimmerAnimator;
	
	// ═════════════════════════════════════════════════════════════════════════
	//  Lifecycle
	// ═════════════════════════════════════════════════════════════════════════
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeHelper.setup(this);
		setContentView(R.layout.chatlist);
		EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));
		bindViews();
		initFirebase();
		wireListeners();
		initLogic();
		
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
			!= PackageManager.PERMISSION_GRANTED) {
				ActivityCompat.requestPermissions(this,
				new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
			}
		}
		
		handleDeepLink();

		// Explicit SDK init — without this, ad requests can silently fail on
		// some devices/OEMs before the SDK auto-initializes on its own.
		ConsentHelper.gatherThenInitAds(this);

		// Bottom banner ad — declared directly in chatlist.xml (above the nav
		// bar), starts GONE, only shown once it actually loads AND the user
		// isn't Pro. See setupBannerAd().
		setupBannerAd();
	}

	/**
	 * Loads the banner ad declared in chatlist.xml (id: banner_ad, default
	 * GONE) and wires its visibility to two things:
	 *   1) the ad actually finishing loading (AdListener)
	 *   2) the user's realtime is_pro flag (Pro users never see ads — the
	 *      instant Pro is granted/removed from the admin panel or a
	 *      purchase completes, the banner disappears/reappears live)
	 * Only VISIBLE when both are satisfied — see _updateBannerVisibility().
	 */
	private void setupBannerAd() {
		if (bannerAd == null) return;

		bannerAd.setAdListener(new com.google.android.gms.ads.AdListener() {
			@Override public void onAdLoaded() {
				adLoadedOk = true;
				_updateBannerVisibility();
			}
			@Override public void onAdFailedToLoad(@NonNull com.google.android.gms.ads.LoadAdError error) {
				// Common causes: no fill (test/low traffic), invalid ad unit ID,
				// AdMob App ID missing from AndroidManifest, no network.
				android.util.Log.e("BannerAd", "load FAILED — code=" + error.getCode()
					+ " domain=" + error.getDomain() + " message=" + error.getMessage());
				adLoadedOk = false;
				_updateBannerVisibility();
			}
		});
		bannerAd.loadAd(new com.google.android.gms.ads.AdRequest.Builder().build());

		// Realtime Pro gating.
		if (myUid != null) {
			bannerProRef = database.getReference("users").child(myUid).child("is_pro");
			bannerProListener = new ValueEventListener() {
				@Override public void onDataChange(@NonNull DataSnapshot snap) {
					userIsPro = Boolean.TRUE.equals(snap.getValue(Boolean.class));
					_updateBannerVisibility();
				}
				@Override public void onCancelled(@NonNull DatabaseError e) {}
			};
			bannerProRef.addValueEventListener(bannerProListener);
		}
	}

	/** Banner shows only when it has genuinely loaded AND the user isn't Pro — otherwise GONE (zero space). */
	private void _updateBannerVisibility() {
		if (bannerAd == null) return;
		bannerAd.setVisibility((adLoadedOk && !userIsPro) ? View.VISIBLE : View.GONE);
	}
	
	@Override protected void onResume() {
		super.onResume();
		if (bannerAd != null) bannerAd.resume();
		if (myUid != null) {
			boolean onlineVisible = getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
				.getBoolean(PrivacySecurityActivity.KEY_ONLINE_VIS, true);
			// BUG FIXED: this used to write "online" unconditionally on every
			// resume, ignoring the Online Status privacy toggle entirely — so
			// turning it off only had an effect for the single moment the
			// switch was flipped, then the very next screen visit silently
			// wrote "online" straight back. Gating the write itself (rather
			// than gating what peers are shown) means no online signal ever
			// leaves the device while this is off, not just a hidden one.
			if (onlineVisible)
				database.getReference("users").child(myUid).child("status").setValue("online");
		}
		
		boolean screenshotOff = getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
		.getBoolean(PrivacySecurityActivity.KEY_SCREENSHOT, false);
		if (screenshotOff) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
		else               getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
	}
	
	@Override protected void onPause() {
		super.onPause();
		if (bannerAd != null) bannerAd.pause();
		if (myUid != null) {
			database.getReference("users").child(myUid).child("status").setValue("offline");
			boolean lastSeenVisible = getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
				.getBoolean(PrivacySecurityActivity.KEY_LAST_SEEN, true);
			// Requirement: "Last Seen" toggle previously had nothing real to
			// control — no last-active timestamp existed anywhere in the
			// project. This is that field, written only when visible (same
			// source-gating approach as Online Status above) so ChatActivity
			// has real data to show as "last seen X ago" for peers who allow
			// it, and nothing at all for peers who don't.
			if (lastSeenVisible)
				database.getReference("users").child(myUid).child("last_active_ts")
					.setValue(com.google.firebase.database.ServerValue.TIMESTAMP);
		}
	}
	
	@Override protected void onDestroy() {
		super.onDestroy();
		if (bannerAd != null) bannerAd.destroy();
		if (bannerProRef != null && bannerProListener != null) bannerProRef.removeEventListener(bannerProListener);
		if (chatsRef != null && chatsListener != null) chatsRef.removeEventListener(chatsListener);
		if (channelsRef != null && channelsListener != null) channelsRef.removeEventListener(channelsListener);
		if (updateRef != null && updateListener != null) updateRef.removeEventListener(updateListener);
		for (String cid : subCountListeners.keySet()) {
			DatabaseReference ref = subCountRefs.get(cid);
			ValueEventListener l  = subCountListeners.get(cid);
			if (ref != null && l != null) ref.removeEventListener(l);
		}
		subCountListeners.clear();
		subCountRefs.clear();
	}
	
	@Override public void onBackPressed() {
		if (currentTab != InkNav.CHATS) { switchTab(InkNav.CHATS); return; }
		long now = System.currentTimeMillis();
		if (now - backPressedTime < 2000) super.onBackPressed();
		else { backPressedTime = now; Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show(); }
	}
	
	// ═════════════════════════════════════════════════════════════════════════
	//  Init
	// ═════════════════════════════════════════════════════════════════════════
	
	private void bindViews() {
		linear80               = findViewById(R.id.linear80);
		frame_layout81         = findViewById(R.id.frame_layout81);
		linear82               = findViewById(R.id.linear82);
		textview72             = findViewById(R.id.textview72);
		icon_search            = findViewById(R.id.icon_search);
		icon_menu              = findViewById(R.id.icon_menu);
		listview_chats         = findViewById(R.id.listview_chats);
		frame_updates_container= findViewById(R.id.frame_updates_container);
		btn_new_chat           = findViewById(R.id.btn_new_chat);
		icon_fab               = findViewById(R.id.icon_fab);
		linear83               = findViewById(R.id.linear83);
		linearDiscover         = findViewById(R.id.linear_discover);
		iconDiscover           = findViewById(R.id.icon_discover);
		textviewDiscover       = findViewById(R.id.textview_discover);
		linear84               = findViewById(R.id.linear84);
		linear85               = findViewById(R.id.linear85);
		icon_chats             = findViewById(R.id.icon_chats);
		textview73             = findViewById(R.id.textview73);
		icon_channels          = findViewById(R.id.icon_channels);
		textview74             = findViewById(R.id.textview74);
		icon_profile           = findViewById(R.id.icon_profile);
		textview75             = findViewById(R.id.textview75);
		layout_channel_subtabs = findViewById(R.id.layout_channel_subtabs);
		tab_my_channels        = findViewById(R.id.tab_my_channels);
		tab_subscribed         = findViewById(R.id.tab_subscribed);
		bannerAd                = findViewById(R.id.banner_ad);
	}
	
	private void initFirebase() {
		// FIX: pehle FirebaseApp.initializeApp() yahan kabhi call hi nahi hota
		// tha - sirf FirebaseInitProvider (ab manifest se hataya gaya) ke
		// bharose chalta tha. Agar ChatlistActivity kisi process mein pehli
		// Activity ho (deep link / restored task), to FirebaseAuth.getInstance()
		// "Default FirebaseApp is not initialized" phenk sakta tha.
		try {
			FirebaseApp.initializeApp(this);
		} catch (Throwable t) {
			Log.e("MinigramFCM", "ChatlistActivity: FirebaseApp init failed: " + t);
		}

		if (!SketchApplication.isFirebaseReady()) {
			android.widget.Toast.makeText(this,
				"Startup error - Firebase failed to initialize. Check logcat (tag: MinigramFCM).",
				android.widget.Toast.LENGTH_LONG).show();
			finish();
			return;
		}

		auth     = FirebaseAuth.getInstance();
		database = FirebaseDatabase.getInstance();
		myUid    = auth.getCurrentUser().getUid();
		database.getReference("user_chats")   .child(myUid).keepSynced(true);
		database.getReference("user_channels").child(myUid).keepSynced(true);
		if (getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
				.getBoolean(PrivacySecurityActivity.KEY_ONLINE_VIS, true))
			database.getReference("users").child(myUid).child("status").setValue("online");

		// FIX: token registration ab launcher se hoti hai, sirf ChatActivity se nahi.
		// Pehle syncToken() sirf ChatActivity.onCreate() mein tha, isliye jo user
		// kabhi 1-to-1 chat nahi kholta (sirf channels padhta hai) uska
		// users/{uid}/fcm_token DB mein aata hi nahi tha -> koi bhi uske paas
		// push nahi bhej paata. Ye call idempotent hai aur andar se rate-limited
		// hai, isliye har launch par safe hai. Ye topic self-heal bhi trigger
		// karta hai (topic subscriptions device par rehti hain, DB mein nahi).
		FcmTokenManager.syncToken(this);

		_watchBanStatus();
	}

	/**
	 * Live ban enforcement — the moment the admin panel sets
	 * users/{uid}/is_banned = true, this fires and clears the entire task
	 * into the Banned screen. Works mid-session too, not just on next open.
	 *
	 * NOTE: we deliberately do NOT sign out — BannedActivity keeps a live
	 * listener on this same flag so an admin UNBAN also takes effect
	 * realtime (user walks right back in). While banned, actual writes are
	 * blocked server-side by the Firebase rules, so the live session can't
	 * be abused.
	 */
	private void _watchBanStatus() {
		final DatabaseReference banRef =
			database.getReference("users").child(myUid).child("is_banned");
		banRef.addValueEventListener(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				if (Boolean.TRUE.equals(snap.getValue(Boolean.class))) {
					banRef.removeEventListener(this);
					Intent i = new Intent(ChatlistActivity.this, BannedActivity.class);
					i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
					startActivity(i);
					finish();
				}
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	private void handleDeepLink() {
		Intent intent = getIntent();
		if (intent == null || intent.getData() == null) return;
		if (!"app".equals(intent.getData().getScheme())) return;
		android.net.Uri data = intent.getData();
		String host = data.getHost();
		String id   = data.getLastPathSegment();
		if ("user".equals(host) && id != null) {
			UserProfileSheet.show(id, "").show(getSupportFragmentManager(), "peer_profile");
		} else if ("channel".equals(host) && id != null) {
			joinChannel(id);
			startActivity(new Intent(this, ChannelActivity.class)
			.putExtra("channel_id", id).putExtra("channel_name", "Loading..."));
		}
	}
	
	private void wireListeners() {
		linear83.setOnClickListener(v -> switchTab(0));
		linearDiscover.setOnClickListener(v -> switchTab(InkNav.DISCOVER));
		linear84.setOnClickListener(v -> switchTab(1));
		linear85.setOnClickListener(v -> switchTab(InkNav.SETTINGS));
		_bindFilterChips();
		
		icon_search.setOnClickListener(v -> openSearchForCurrentTab());
		textview72 .setOnClickListener(v -> openSearchForCurrentTab());
		
		// ── POPUP MENU — fully restored ────────────────────────────────────
		icon_menu.setOnClickListener(v -> showPopupMenu());
		
		btn_new_chat.setOnClickListener(v -> {
			if (currentTab == 0)
			startActivity(new Intent(this, NewMessageActivity.class));
			else
			startActivity(new Intent(this, CreateChannelActivity.class));
		});
		
		listview_chats.setOnItemClickListener((parent, view, pos, id) -> {
			if (currentTab == 0) {
				if (pos < 0 || pos >= shownChats().size()) return;
				HashMap<String, Object> chat = shownChats().get(pos);
				String chatId = (String) chat.get("chatId");
				cancelNotification(chatId);
				startActivity(new Intent(this, ChatActivity.class)
				.putExtra("chat_id",    chatId)
				.putExtra("peer_uid",   (String) chat.get("peer_uid"))
				.putExtra("peer_name",  (String) chat.get("peer_name"))
				.putExtra("peer_photo", (String) chat.get("peer_photo")));
			} else if (currentTab == 1) {
				if (pos < 0 || pos >= filteredChannelList.size()) return;
				HashMap<String, Object> ch = filteredChannelList.get(pos);
				String channelId = (String) ch.get("channelId");
				cancelNotification(channelId);
				startActivity(new Intent(this, ChannelActivity.class)
				.putExtra("channel_id",    channelId)
				.putExtra("channel_name",  (String) ch.get("name"))
				.putExtra("channel_photo", (String) ch.get("photo_url"))
				.putExtra("owner_uid",     (String) ch.get("owner_uid")));
			}
		});
		
		listview_chats.setOnItemLongClickListener((parent, view, pos, id) -> {
			if (currentTab == 0 && pos >= 0 && pos < shownChats().size()) {
				HashMap<String, Object> chat = shownChats().get(pos);
				showChatOptions((String) chat.get("chatId"), (String) chat.get("peer_uid"));
				return true;
			} else if (currentTab == 1 && pos >= 0 && pos < filteredChannelList.size()) {
				HashMap<String, Object> ch = filteredChannelList.get(pos);
				showChannelOptions((String) ch.get("channelId"), (String) ch.get("owner_uid"));
				return true;
			}
			return false;
		});
		
		// Sub-tab pill clicks
		if (tab_my_channels != null) tab_my_channels.setOnClickListener(v -> switchChannelSubTab(0));
		if (tab_subscribed  != null) tab_subscribed .setOnClickListener(v -> switchChannelSubTab(1));
	}
	
	private void initLogic() {
		chatAdapter    = new ChatAdapter();
		channelAdapter = new ChannelAdapter();
		skeletonAdapter= new SkeletonAdapter();
		
		listview_chats.setAdapter(skeletonAdapter);
		startShimmer();
		
		switchTab(getIntent().getIntExtra(InkNav.EXTRA_TAB, 0));
		// Populate from disk BEFORE the first load so cached peers render
		// immediately rather than waiting on a network round trip.
		_loadPeerInfoCache();
		loadChats();
		loadChannels();
		_repairOwnedChannelMemberships();
		checkForUpdates();
	}
	
	// ═════════════════════════════════════════════════════════════════════════
	//  Popup menu — FULLY RESTORED
	// ═════════════════════════════════════════════════════════════════════════
	
	private void showPopupMenu() {
		PopupMenu popup = new PopupMenu(this, icon_menu);
		popup.getMenu().add(0, 1, 0, "New chat");
		popup.getMenu().add(0, 2, 1, "New channel");
		popup.getMenu().add(0, 3, 2, "Mark all as read");
		popup.getMenu().add(0, 4, 3, "Invite friends");
		popup.setOnMenuItemClickListener(item -> {
			switch (item.getItemId()) {
				case 1: startActivity(new Intent(this, NewMessageActivity.class)); break;
				case 2: startActivity(new Intent(this, CreateChannelActivity.class)); break;
				case 3: markAllRead(); break;
				case 4:
					startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
						.putExtra(Intent.EXTRA_TEXT, "Join me on Minigram \u2014 private chats and channels. https://play.google.com/store/apps/details?id=com.silu.minigram"), "Invite friends"));
					break;
			}
			return true;
		});
		popup.show();
	}

	private void markAllRead() {
		int n = 0;
		for (HashMap<String, Object> c : chatList) {
			Object u = c.get("unread");
			if (u instanceof Long && (Long) u > 0 && c.get("chatId") != null) {
				database.getReference("user_chats").child(myUid).child((String) c.get("chatId")).child("unread").setValue(0);
				n++;
			}
		}
		Toast.makeText(this, n == 0 ? "Nothing unread" : "Marked " + n + " chat" + (n == 1 ? "" : "s") + " as read", Toast.LENGTH_SHORT).show();
	}
	
	// ═════════════════════════════════════════════════════════════════════════
	//  Long-press option sheets
	// ═════════════════════════════════════════════════════════════════════════
	
	private void showChatOptions(String chatId, String peerUid) {
		final boolean pinned = isPinned(chatId);
		final android.content.SharedPreferences muted = getSharedPreferences("MutedChats", MODE_PRIVATE);
		final boolean isMuted = muted.getBoolean(chatId, false);
		String[] options = {pinned ? "Unpin" : "Pin to top", "Mark as read", isMuted ? "Unmute" : "Mute", "Block user", "Delete chat"};
		new android.app.AlertDialog.Builder(this)
		.setItems(options, (dialog, which) -> {
			if (which == 0) {
				getSharedPreferences("PinnedChats", MODE_PRIVATE).edit().putBoolean(chatId, !pinned).apply();
				updateChatTabView();
			} else if (which == 1) {
				database.getReference("user_chats").child(myUid).child(chatId).child("unread").setValue(0);
			} else if (which == 2) {
				muted.edit().putBoolean(chatId, !isMuted).apply();
				Toast.makeText(this, isMuted ? "Chat unmuted" : "Chat muted \uD83D\uDD15", Toast.LENGTH_SHORT).show();
			} else if (which == 3) {
				new android.app.AlertDialog.Builder(this).setTitle("Block this user?")
				.setMessage("They will no longer be able to message you.")
				.setPositiveButton("Block", (d2, w2) -> {
					database.getReference("blocked_users").child(myUid).child(peerUid).setValue(true);
					Toast.makeText(this, "User blocked", Toast.LENGTH_SHORT).show();
				}).setNegativeButton("Cancel", null).show();
			} else {
				new android.app.AlertDialog.Builder(this).setTitle("Delete this chat?")
				.setMessage("Messages are removed from this device and your chat list.")
				.setPositiveButton("Delete", (d2, w2) -> {
					database.getReference("user_chats").child(myUid).child(chatId).removeValue();
					MessageCacheHelper.get(this).clearChat(chatId);
				}).setNegativeButton("Cancel", null).show();
			}
		}).show();
	}
	
	private void showChannelOptions(String channelId, String ownerUid) {
		boolean isOwner = myUid != null && myUid.equals(ownerUid);
		if (isOwner) {
			// BUG FIXED: this used to show "Leave Channel" unconditionally,
			// even for a channel you own — leaving your own channel this
			// way doesn't make sense (you're still owner_uid on the
			// channels/ node itself, this only touched your own membership
			// records) and could leave things in a confusing half state.
			// Owner gets a shortcut into management instead.
			String[] ownerOptions = {"Channel Settings", "Mute"};
			new android.app.AlertDialog.Builder(this)
			.setItems(ownerOptions, (dialog, which) -> {
				if (which == 0) {
					Intent i = new Intent(this, ChannelSettingsActivity.class);
					i.putExtra("channel_id", channelId);
					startActivity(i);
				} else {
					getSharedPreferences("MutedChannels", MODE_PRIVATE)
					.edit().putBoolean(channelId, true).apply();
					Toast.makeText(this, "Channel Muted 🔕", Toast.LENGTH_SHORT).show();
				}
			}).show();
			return;
		}
		String[] options = {"Leave Channel", "Mute"};
		new android.app.AlertDialog.Builder(this)
		.setItems(options, (dialog, which) -> {
			if (which == 0) {
				// user_channels se remove
				database.getReference("user_channels")
				.child(myUid).child(channelId).removeValue();
				// channel_subscribers se bhi properly remove (yahi miss tha)
				database.getReference("channel_subscribers")
				.child(channelId).child(myUid).removeValue();
				// subscriber count decrement
				database.getReference("channels")
				.child(channelId).child("subscriber_count")
				.setValue(ServerValue.increment(-1));
				// FCM topic unsubscribe.
				// FIX: pehle yahan raw "channel_" + channelId banaya jaata tha jabki
				// sender (ChannelActivity) FcmTokenManager.topicFor() use karta hai.
				// Dono kabhi bhi divergent ho sakte the. Aur FirebaseMessaging
				// .getInstance() ka direct call NullPointerException phenk sakta hai
				// ("Firebase Messaging component is not present") jo poori
				// ChatlistActivity gira deta tha. FcmTokenManager dono handle karta hai.
				FcmTokenManager.unsubscribeChannel(channelId);
				Toast.makeText(this, "Channel छोड़ दिया", Toast.LENGTH_SHORT).show();
			} else if (which == 1) {
				getSharedPreferences("channel_prefs", MODE_PRIVATE)
				.edit().putBoolean("muted_" + channelId, true).apply();
				Toast.makeText(this, "Channel Muted 🔕", Toast.LENGTH_SHORT).show();
			}
		}).show();
	}
	
	// ═════════════════════════════════════════════════════════════════════════
	//  Tab switching
	// ═════════════════════════════════════════════════════════════════════════
	
	/** Highlights one of the 4 bottom-nav items: 0 Chats, 1 Discover, 2 Channels, 3 Settings. */
	private void _applyNav(int sel) {
		View[] item = {linear83, linearDiscover, linear84, linear85};
		TextView[] ic = {icon_chats, iconDiscover, icon_channels, icon_profile};
		TextView[] tx = {textview73, textviewDiscover, textview74, textview75};
		String[] on = {"chat_bubble", "explore", "campaign", "settings"};
		String[] off = {"chat_bubble_outline", "explore", "campaign", "settings"};
		int cOn = Ink.res(R.color.ink_accent), cOff = Ink.res(R.color.ink_text_2);
		for (int i = 0; i < 4; i++) {
			boolean s = i == sel;
			item[i].setSelected(s);
			ic[i].setText(s ? on[i] : off[i]);
			ic[i].setTextColor(s ? cOn : cOff);
			tx[i].setTextColor(s ? cOn : cOff);
			tx[i].setTypeface(null, s ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
		}
	}

	/** Public wrapper so hosted fragments can jump to another tab. */
	void openTab(int tab) { switchTab(tab); }

	/** Keeps a scrolling view's bottom padding equal to the floating nav (+ banner) height, so content scrolls under it. */
	static void bindOverlayPadding(android.app.Activity a, View target, int extraDp) {
		View o = a.findViewById(R.id.overlay_stack);
		if (o == null || target == null) return;
		final int extra = (int) (extraDp * a.getResources().getDisplayMetrics().density);
		final Runnable apply = () -> target.setPadding(target.getPaddingLeft(), target.getPaddingTop(),
			target.getPaddingRight(), o.getHeight() + extra);
		apply.run();
		final View.OnLayoutChangeListener l = (v, a1, b1, c1, d1, e1, f1, g1, h1) -> apply.run();
		o.addOnLayoutChangeListener(l);
		target.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View v) {}
			@Override public void onViewDetachedFromWindow(View v) { o.removeOnLayoutChangeListener(l); }
		});
	}

	private void _bindFilterChips() {
		com.google.android.material.chip.ChipGroup g = findViewById(R.id.chip_group_filter);
		if (g == null) return;
		g.setOnCheckedStateChangeListener((group, ids) -> {
			if (ids.isEmpty()) return;
			int id = ids.get(0);
			if (id == R.id.chip_channels) {
				switchTab(1);
				group.check(R.id.chip_all);
				return;
			}
			unreadOnly = (id == R.id.chip_unread);
			if (currentTab == 0) updateChatTabView();
		});
	}

	/** The rows currently shown on the Chats tab (All, or only chats with unread messages). */
	private ArrayList<HashMap<String, Object>> shownChats() {
		ArrayList<HashMap<String, Object>> out = new ArrayList<>();
		ArrayList<HashMap<String, Object>> rest = new ArrayList<>();
		for (HashMap<String, Object> c : chatList) {
			Object u = c.get("unread");
			if (unreadOnly && !(u instanceof Long && (Long) u > 0)) continue;
			if (isPinned((String) c.get("chatId"))) out.add(c); else rest.add(c);
		}
		out.addAll(rest);   // pinned first, order within each group unchanged
		return out;
	}

	private boolean isPinned(String chatId) {
		return chatId != null && getSharedPreferences("PinnedChats", MODE_PRIVATE).getBoolean(chatId, false);
	}

	@Override protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		switchTab(intent.getIntExtra(InkNav.EXTRA_TAB, 0));
	}

	private void switchTab(int tab) {
		currentTab = tab;
		_applyNav(tab == InkNav.CHATS ? 0 : tab == InkNav.DISCOVER ? 1 : tab == InkNav.SETTINGS ? 3 : 2);
		boolean hosted = tab == InkNav.DISCOVER || tab == InkNav.SETTINGS;
		View header = findViewById(R.id.linear80);
		if (header != null) header.setVisibility(hosted ? View.GONE : View.VISIBLE);
		setHosted(R.id.frame_discover_container, tab == InkNav.DISCOVER);
		setHosted(R.id.frame_settings_container, tab == InkNav.SETTINGS);
		if (tab == InkNav.DISCOVER && getSupportFragmentManager().findFragmentByTag("tab_discover") == null)
			getSupportFragmentManager().beginTransaction().replace(R.id.frame_discover_container, new DiscoverFragment(), "tab_discover").commitAllowingStateLoss();
		if (tab == InkNav.SETTINGS && getSupportFragmentManager().findFragmentByTag("tab_settings") == null)
			getSupportFragmentManager().beginTransaction().replace(R.id.frame_settings_container, new SettingsFragment(), "tab_settings").commitAllowingStateLoss();

		if (hosted) {
			hideSubtabs();
			_setFiltersVisible(false);
			listview_chats.setVisibility(View.GONE);
			btn_new_chat.setVisibility(View.GONE);
			if (frame_updates_container != null) frame_updates_container.setVisibility(View.GONE);
		} else if (tab == InkNav.CHATS) {
			hideSubtabs();
			_showMainListContent();
			_setFiltersVisible(true);
			listview_chats.setPadding(0, dp(8), 0, listview_chats.getPaddingBottom());
			listview_chats.setClipToPadding(false);
			updateChatTabView();
			btn_new_chat.setVisibility(View.VISIBLE);
			icon_fab.setText("edit");
		} else if (tab == InkNav.CHANNELS) {
			_showMainListContent();
			_setFiltersVisible(false);
			showSubtabs();
			// paddingTop = 52dp (sub-tab height) + 8dp gap
			listview_chats.setPadding(0, dp(60), 0, listview_chats.getPaddingBottom());
			listview_chats.setClipToPadding(false);
			applySubTabPillStyle();
			updateChannelTabView();
			btn_new_chat.setVisibility(View.VISIBLE);
			icon_fab.setText("add");
		} else {
			// Updates — real Fragment, loaded once and then just shown/hidden
			hideSubtabs();
			_setFiltersVisible(false);
			listview_chats.setVisibility(View.GONE);
			btn_new_chat.setVisibility(View.GONE);
			if (frame_updates_container != null) {
				frame_updates_container.setVisibility(View.VISIBLE);
				if (!updatesFragmentLoaded) {
					getSupportFragmentManager().beginTransaction()
						.replace(R.id.frame_updates_container, new UpdatesFragment())
						.commit();
					updatesFragmentLoaded = true;
				}
			}
		}
		_applyOverlayInsets();
	}

	private void setHosted(int id, boolean on) {
		View v = findViewById(id);
		if (v != null) v.setVisibility(on ? View.VISIBLE : View.GONE);
	}

	private boolean overlayBound;
	/** List, Updates container and FAB all clear the floating nav (and banner) by its live height. */
	private void _applyOverlayInsets() {
		if (overlayBound) return;
		overlayBound = true;
		final View o = findViewById(R.id.overlay_stack);
		if (o == null) return;
		final Runnable apply = () -> {
			int h = o.getHeight();
			listview_chats.setPadding(listview_chats.getPaddingLeft(), listview_chats.getPaddingTop(), 0, h + dp(8));
			if (frame_updates_container != null) frame_updates_container.setPadding(0, 0, 0, h);
			android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) btn_new_chat.getLayoutParams();
			lp.bottomMargin = h + dp(16);
			btn_new_chat.setLayoutParams(lp);
		};
		o.addOnLayoutChangeListener((v, a, b, c, d, e, f, g, hh) -> apply.run());
		o.post(apply);
	}

	private void _setFiltersVisible(boolean v) {
		View f = findViewById(R.id.layout_chat_filters);
		if (f != null) f.setVisibility(v ? View.VISIBLE : View.GONE);
		TextView t = findViewById(R.id.textview72);
		if (t != null) t.setText(v ? "Chats" : "Channels");
	}

	/** Chats/Channels both live in listview_chats — Updates hides it. */
	private void _showMainListContent() {
		listview_chats.setVisibility(View.VISIBLE);
		if (frame_updates_container != null) frame_updates_container.setVisibility(View.GONE);
	}
	
	private void showSubtabs() {
		if (layout_channel_subtabs == null) return;
		layout_channel_subtabs.setVisibility(View.VISIBLE);
		layout_channel_subtabs.animate().alpha(1f).setDuration(180).start();
	}
	
	private void hideSubtabs() {
		if (layout_channel_subtabs == null) return;
		layout_channel_subtabs.setVisibility(View.GONE);
	}
	
	// ── Channel sub-tab (pill style) ─────────────────────────────────────────
	
	private void switchChannelSubTab(int sub) {
		channelSubTab = sub;
		applySubTabPillStyle();
		updateChannelTabView();
	}
	
	/**
* Sets active pill = blue filled + white bold text
*        inactive  = light grey filled + dark grey text
* No underline, no border — clean pill chip look.
*/	
	private void applySubTabPillStyle() {
		if (tab_my_channels == null || tab_subscribed == null) return;
		
		if (channelSubTab == 0) {
			// My Channels active
			tab_my_channels.setBackgroundResource(R.drawable.tab_pill_active_bg);
			tab_my_channels.setTextColor(Color.WHITE);
			tab_my_channels.setTypeface(null, android.graphics.Typeface.BOLD);
			tab_subscribed .setBackgroundResource(R.drawable.tab_pill_inactive_bg);
			tab_subscribed .setTextColor(Ink.c("#5A5A5A"));
			tab_subscribed .setTypeface(null, android.graphics.Typeface.NORMAL);
		} else {
			// Subscribed active
			tab_subscribed .setBackgroundResource(R.drawable.tab_pill_active_bg);
			tab_subscribed .setTextColor(Color.WHITE);
			tab_subscribed .setTypeface(null, android.graphics.Typeface.BOLD);
			tab_my_channels.setBackgroundResource(R.drawable.tab_pill_inactive_bg);
			tab_my_channels.setTextColor(Ink.c("#5A5A5A"));
			tab_my_channels.setTypeface(null, android.graphics.Typeface.NORMAL);
		}
	}
	
	private ArrayList<HashMap<String, Object>> getFilteredChannelList() {
		ArrayList<HashMap<String, Object>> result = new ArrayList<>();
		for (HashMap<String, Object> ch : channelList) {
			String ownerUid = (String) ch.get("owner_uid");
			if (channelSubTab == 0) { if (myUid.equals(ownerUid))  result.add(ch); }
			else                    { if (!myUid.equals(ownerUid)) result.add(ch); }
		}
		return result;
	}
	
	// ═════════════════════════════════════════════════════════════════════════
	//  Tab content renderers
	// ═════════════════════════════════════════════════════════════════════════
	
	private void updateChatTabView() {
		stopShimmer();
		if (!chatsLoaded) { listview_chats.setAdapter(skeletonAdapter); startShimmer(); return; }
		if (chatList.isEmpty()) {
			listview_chats.setAdapter(new EmptyStateAdapter(
			"No chats yet",
			"Search for a user to start chatting",
			v -> startActivity(new Intent(this, UserSearchActivity.class)
			.putExtra(UserSearchActivity.EXTRA_MODE, UserSearchActivity.MODE_USER))));
		} else {
			listview_chats.setAdapter(chatAdapter);
			chatAdapter.notifyDataSetChanged();
		}
	}
	
	private void updateChannelTabView() {
		stopShimmer();
		filteredChannelList.clear();
		filteredChannelList.addAll(getFilteredChannelList());
		
		if (!channelsLoaded) { listview_chats.setAdapter(skeletonAdapter); startShimmer(); return; }
		
		if (filteredChannelList.isEmpty()) {
			String title, sub;
			View.OnClickListener action;
			if (channelSubTab == 0) {
				title  = "No channels created yet";
				sub    = "Tap + to create your first channel";
				action = v -> startActivity(new Intent(this, CreateChannelActivity.class));
			} else {
				title  = "No subscribed channels";
				sub    = "Search and subscribe to a channel";
				action = v -> startActivity(new Intent(this, UserSearchActivity.class)
				.putExtra(UserSearchActivity.EXTRA_MODE, UserSearchActivity.MODE_CHANNEL));
			}
			listview_chats.setAdapter(new EmptyStateAdapter(title, sub, action));
		} else {
			listview_chats.setAdapter(channelAdapter);
			channelAdapter.notifyDataSetChanged();
		}
	}
	
	// ═════════════════════════════════════════════════════════════════════════
	//  Shimmer
	// ═════════════════════════════════════════════════════════════════════════
	
	private void startShimmer() {
		if (shimmerAnimator != null && shimmerAnimator.isRunning()) return;
		shimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
		shimmerAnimator.setDuration(1200);
		shimmerAnimator.setRepeatMode(ValueAnimator.RESTART);
		shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
		shimmerAnimator.setInterpolator(new LinearInterpolator());
		shimmerAnimator.addUpdateListener(a -> {
			if (skeletonAdapter != null) skeletonAdapter.setShimmerPhase((float) a.getAnimatedValue());
		});
		shimmerAnimator.start();
	}
	
	private void stopShimmer() {
		if (shimmerAnimator != null) { shimmerAnimator.cancel(); shimmerAnimator = null; }
		if (skeletonAdapter != null) skeletonAdapter.setShimmerPhase(1f);
	}
	
	// ═════════════════════════════════════════════════════════════════════════
	//  Firebase loaders
	// ═════════════════════════════════════════════════════════════════════════
	
	private void loadChats() {
		chatsRef = database.getReference("user_chats").child(myUid);
		chatsListener = chatsRef
		.orderByChild("last_time")
		.addValueEventListener(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				chatList.clear();
				for (DataSnapshot child : snap.getChildren()) {
					String peerUid = child.child("peer_uid").getValue(String.class);
					// Ghost entry skip + Firebase se clean
					if (peerUid == null || peerUid.trim().isEmpty()) {
						database.getReference("user_chats")
						.child(myUid).child(child.getKey()).removeValue();
						continue;
					}
					HashMap<String, Object> chat = new HashMap<>();
					chat.put("chatId",     child.getKey());
					chat.put("peer_uid",   peerUid);
					chat.put("last_msg",   child.child("last_msg") .getValue(String.class));
					chat.put("last_time",  child.child("last_time").getValue(Long.class));
					chat.put("unread",     child.child("unread")   .getValue(Long.class));

					// Already resolved this peer before (in this session)? Use it
					// immediately — no blank flash, and no repeat network fetch.
					HashMap<String, Object> cached = peerInfoCache.get(peerUid);
					if (cached != null) {
						chat.put("peer_name",   cached.get("peer_name"));
						chat.put("peer_photo",  cached.get("peer_photo"));
						chat.put("peer_is_pro", cached.get("peer_is_pro"));
					} else {
						chat.put("peer_name",  "");
						chat.put("peer_photo", "");
						chat.put("peer_is_pro", false);
					}
					chatList.add(0, chat);
				}
				// Resolve peer names/photos — only for peers not already cached.
				for (HashMap<String, Object> chat : chatList) {
					String pUid = (String) chat.get("peer_uid");
					if (pUid == null) continue;
					// Cached peers already rendered above from disk — they're
					// refreshed quietly here rather than skipped, so a changed
					// name/photo still lands without the user waiting on a
					// download for people already on screen.
					database.getReference("users").child(pUid)
					.addListenerForSingleValueEvent(new ValueEventListener() {
						@Override public void onDataChange(@NonNull DataSnapshot us) {
							if (!us.exists()) return;
							HashMap<String, Object> info = new HashMap<>();
							info.put("peer_name",   us.child("fullname") .getValue(String.class));
							info.put("peer_photo",  us.child("photo_url").getValue(String.class));
							info.put("peer_is_pro", Boolean.TRUE.equals(us.child("is_pro").getValue(Boolean.class)));
							peerInfoCache.put(pUid, info);
							_savePeerInfo(pUid, info);
							chat.put("peer_name",   info.get("peer_name"));
							chat.put("peer_photo",  info.get("peer_photo"));
							chat.put("peer_is_pro", info.get("peer_is_pro"));
							if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
						}
						@Override public void onCancelled(@NonNull DatabaseError e) {}
					});
				}
				chatsLoaded = true;
				if (currentTab == 0) updateChatTabView();
				if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	/**
* Issue 7: loadChannels uses real-time listener on channel_subscribers
* so subscriber count is always live, not stale.
*/	
	/**
	 * "My Channels" reads from user_channels/{myUid} (see loadChannels()
	 * below) — NOT from channels/{id}/owner_uid. Before the ownership-check
	 * fix to this screen's long-press menu, tapping "Leave Channel" on a
	 * channel you actually OWNED removed exactly that user_channels entry —
	 * the channel itself, and your ownership record on it, were never
	 * touched. So the channel silently vanished from your own list even
	 * though it's fully intact and you're still its owner.
	 *
	 * This runs once per app open (cheap — one indexed query, and a no-op
	 * write for anyone with nothing to repair) and restores the missing
	 * entry for any channel where owner_uid really is you. Acts as a
	 * permanent safety net against that old bug's lingering effect, and
	 * against any other future path that could cause the same gap — this
	 * invariant (owner always has their own channel in their own list)
	 * should just always hold.
	 */
	private void _repairOwnedChannelMemberships() {
		if (myUid == null) return;
		database.getReference("channels").orderByChild("owner_uid").equalTo(myUid)
			.addListenerForSingleValueEvent(new ValueEventListener() {
				@Override public void onDataChange(@NonNull DataSnapshot ownedSnap) {
					for (DataSnapshot ch : ownedSnap.getChildren()) {
						String channelId = ch.getKey();
						if (channelId == null) continue;
						database.getReference("user_channels").child(myUid).child(channelId)
							.addListenerForSingleValueEvent(new ValueEventListener() {
								@Override public void onDataChange(@NonNull DataSnapshot membershipSnap) {
									if (!membershipSnap.exists()) {
										database.getReference("user_channels").child(myUid)
											.child(channelId).setValue(true);
									}
								}
								@Override public void onCancelled(@NonNull DatabaseError e) {}
							});
					}
				}
				@Override public void onCancelled(@NonNull DatabaseError e) {}
			});
	}

	/** Writes one peer's cached name/photo to disk. Stored as three plain
	 *  keys rather than JSON to avoid pulling in a parser for three fields. */
	private void _savePeerInfo(String uid, HashMap<String, Object> info) {
		if (uid == null) return;
		android.content.SharedPreferences.Editor e =
			getSharedPreferences(PEER_CACHE_PREFS, MODE_PRIVATE).edit();
		Object nm = info.get("peer_name");
		Object ph = info.get("peer_photo");
		e.putString(uid + "_name",  nm != null ? String.valueOf(nm) : "");
		e.putString(uid + "_photo", ph != null ? String.valueOf(ph) : "");
		e.putBoolean(uid + "_pro",  Boolean.TRUE.equals(info.get("peer_is_pro")));
		e.apply();
	}

	/** Loads whatever was cached on a previous run into peerInfoCache, so the
	 *  first frame after launch already has names and photos instead of blank
	 *  rows waiting on the network. */
	private void _loadPeerInfoCache() {
		android.content.SharedPreferences sp = getSharedPreferences(PEER_CACHE_PREFS, MODE_PRIVATE);
		for (String key : sp.getAll().keySet()) {
			if (!key.endsWith("_name")) continue;
			String uid = key.substring(0, key.length() - "_name".length());
			if (peerInfoCache.containsKey(uid)) continue;
			HashMap<String, Object> info = new HashMap<>();
			info.put("peer_name",   sp.getString(uid + "_name", ""));
			info.put("peer_photo",  sp.getString(uid + "_photo", ""));
			info.put("peer_is_pro", sp.getBoolean(uid + "_pro", false));
			peerInfoCache.put(uid, info);
		}
	}

	private void loadChannels() {
		channelsRef = database.getReference("user_channels").child(myUid);
		channelsListener = channelsRef
		.addValueEventListener(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				channelList.clear();
				final int[] total    = {0};
				final int[] resolved = {0};
				for (DataSnapshot child : snap.getChildren()) total[0]++;
				
				if (total[0] == 0) {
					channelsLoaded = true;
					if (!officialChannelChecked) { officialChannelChecked = true; autoSubscribeOfficialChannel(); }
					if (currentTab == 1) updateChannelTabView();
					return;
				}
				
				for (DataSnapshot child : snap.getChildren()) {
					String channelId = child.getKey();
					if (channelId == null) { checkAllResolved(total, resolved); continue; }

					database.getReference("channels").child(channelId)
					.addListenerForSingleValueEvent(new ValueEventListener() {
						@Override public void onDataChange(@NonNull DataSnapshot cs) {
							if (!cs.exists()) { checkAllResolved(total, resolved); return; }
							
							HashMap<String, Object> ch = new HashMap<>();
							ch.put("channelId", channelId);
							ch.put("name",      cs.child("name")     .getValue(String.class));
							ch.put("photo_url", cs.child("photo_url").getValue(String.class));
							ch.put("owner_uid", cs.child("owner_uid").getValue(String.class));
							ch.put("description", cs.child("description").getValue(String.class));
							ch.put("is_verified", Boolean.TRUE.equals(cs.child("is_verified").getValue(Boolean.class)));
							ch.put("subscriber_count", cs.child("subscriber_count").getValue(Long.class));
							channelInfoCache.put(channelId, ch);
							channelList.add(ch);

							// Real-time sub count — detach any PREVIOUS listener for this
							// channel first (this outer block re-runs on every refire of
							// the user_channels listener; without this, a new live
							// listener stacked up on top of the old one every single
							// time — a compounding leak that gets the app slower the
							// longer a session runs).
							ValueEventListener oldListener = subCountListeners.get(channelId);
							DatabaseReference   oldRef      = subCountRefs.get(channelId);
							if (oldListener != null && oldRef != null) {
								oldRef.removeEventListener(oldListener);
							}

							DatabaseReference subRef = database.getReference("channel_subscribers").child(channelId);
							ValueEventListener subListener = new ValueEventListener() {
								@Override public void onDataChange(@NonNull DataSnapshot subSnap) {
									long real = subSnap.getChildrenCount();
									ch.put("subscriber_count", real);
									Long stored = cs.child("subscriber_count").getValue(Long.class);
									if (stored == null || stored != real)
									database.getReference("channels").child(channelId)
									.child("subscriber_count").setValue(real);
									if (channelAdapter != null) channelAdapter.notifyDataSetChanged();
									if (currentTab == 1) updateChannelTabView();
								}
								@Override public void onCancelled(@NonNull DatabaseError e) {}
							};
							subRef.addValueEventListener(subListener);
							subCountListeners.put(channelId, subListener);
							subCountRefs.put(channelId, subRef);

							checkAllResolved(total, resolved);
						}
						@Override public void onCancelled(@NonNull DatabaseError e) { checkAllResolved(total, resolved); }
					});
				}
				
				if (!officialChannelChecked) { officialChannelChecked = true; autoSubscribeOfficialChannel(); }
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	private void checkAllResolved(int[] total, int[] resolved) {
		resolved[0]++;
		if (resolved[0] >= total[0]) {
			channelsLoaded = true;
			if (currentTab == 1) updateChannelTabView();
			if (channelAdapter != null) channelAdapter.notifyDataSetChanged();
		}
	}
	
	private void autoSubscribeOfficialChannel() {
		database.getReference("users").orderByChild("handle").equalTo("minigram_official")
		.addListenerForSingleValueEvent(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				if (!snap.exists()) return;
				String officialUid = null;
				for (DataSnapshot c : snap.getChildren()) { officialUid = c.getKey(); break; }
				if (officialUid == null) return;
				final String fUid = officialUid;
				database.getReference("channels").orderByChild("owner_uid").equalTo(fUid)
				.addListenerForSingleValueEvent(new ValueEventListener() {
					@Override public void onDataChange(@NonNull DataSnapshot snap2) {
						if (!snap2.exists()) return;
						for (DataSnapshot chSnap : snap2.getChildren()) {
							final String cId = chSnap.getKey();
							if (cId == null) continue;
							database.getReference("user_channels").child(myUid).child(cId)
							.addListenerForSingleValueEvent(new ValueEventListener() {
								@Override public void onDataChange(@NonNull DataSnapshot s) { if (!s.exists()) joinChannel(cId); }
								@Override public void onCancelled(@NonNull DatabaseError e) {}
							});
							break;
						}
					}
					@Override public void onCancelled(@NonNull DatabaseError e) {}
				});
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	private void joinChannel(String channelId) {
		database.getReference("channel_subscribers").child(channelId).child(myUid).setValue(true);
		database.getReference("user_channels").child(myUid).child(channelId).setValue(true);
		database.getReference("channels").child(channelId).child("subscriber_count")
		.setValue(ServerValue.increment(1));
		// FIX: same topic-builder as the sender + crash-safe. Dekho _leaveChannel note.
		FcmTokenManager.subscribeChannel(channelId);
	}
	
	private void openSearchForCurrentTab() {
		startActivity(new Intent(this, UserSearchActivity.class)
		.putExtra(UserSearchActivity.EXTRA_MODE,
		currentTab == 1 ? UserSearchActivity.MODE_CHANNEL : UserSearchActivity.MODE_USER));
	}
	
	private void cancelNotification(String id) {
		if (id == null) return;
		NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		if (nm != null) nm.cancel(id.hashCode());
	}
	
	private void checkForUpdates() {
		updateRef = database.getReference("app_update");
		updateRef.keepSynced(true);
		updateListener = updateRef.addValueEventListener(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				if (!snap.exists()) return;
				int latestVersion = 0;
				try { latestVersion = Integer.parseInt(String.valueOf(snap.child("version_code").getValue())); }
				catch (Exception ignored) {}
				int currentVersion = 1;
				try { currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; }
				catch (Exception ignored) {}
				
				if (latestVersion > currentVersion) {
					String  title    = snap.child("title")       .getValue(String.class);
					String  whatsNew = snap.child("whats_new")   .getValue(String.class);
					String  url      = snap.child("url")         .getValue(String.class);
					Boolean force    = snap.child("force_update").getValue(Boolean.class);
					if (title    == null || title.isEmpty())    title    = "New Update Available!";
					if (whatsNew == null || whatsNew.isEmpty()) whatsNew = "A new version is available.";
					if (url      == null || url.isEmpty())      url      = "https://minigram-messaging.vercel.app";
					final String ft = title, fw = whatsNew, fu = url;
					final boolean ff = force != null && force;
					runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) showUpdateDialog(ft, fw, fu, ff); });
				}
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	private void showUpdateDialog(String title, String message, String url, boolean forceUpdate) {
		try {
			android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
			b.setTitle(title).setMessage(message).setCancelable(!forceUpdate);
			b.setPositiveButton("Update Now", (d, w) -> {
				try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
				catch (Exception e) { Toast.makeText(this, "Invalid link", Toast.LENGTH_SHORT).show(); }
				if (forceUpdate) finish();
			});
			if (!forceUpdate) b.setNegativeButton("Later", null);
			b.show();
		} catch (Exception e) { e.printStackTrace(); }
	}
	
	// ═════════════════════════════════════════════════════════════════════════
	//  Inner Adapters
	// ═════════════════════════════════════════════════════════════════════════
	
	private class SkeletonAdapter extends BaseAdapter {
		private final int COUNT = 6;
		private float shimmerPhase = 0f;
		void setShimmerPhase(float p) { this.shimmerPhase = p; notifyDataSetChanged(); }
		@Override public int    getCount()        { return COUNT; }
		@Override public Object getItem(int p)    { return null; }
		@Override public long   getItemId(int p)  { return p; }
		@Override public View getView(int pos, View cv, ViewGroup parent) {
			int layout = (currentTab == 0) ? R.layout.item_chat_skeleton : R.layout.item_channel_skeleton;
			if (cv == null || cv.getTag() == null || (int) cv.getTag() != layout) {
				cv = LayoutInflater.from(ChatlistActivity.this).inflate(layout, parent, false);
				cv.setTag(layout);
			}
			float offset = (pos % 3) * 0.33f;
			float alpha  = (float)(0.4 + 0.6 * Math.abs(Math.sin((shimmerPhase + offset) * Math.PI)));
			int   color  = Color.argb((int)(alpha * 255), 224, 224, 224);
			View a = cv.findViewById(R.id.skeleton_avatar),
			l1= cv.findViewById(R.id.skeleton_line1),
			l2= cv.findViewById(R.id.skeleton_line2),
			l3= cv.findViewById(R.id.skeleton_line3);
			if (a != null)  a.setBackgroundColor(color);
			if (l1 != null) l1.setBackgroundColor(color);
			if (l2 != null) l2.setBackgroundColor(color);
			if (l3 != null) l3.setBackgroundColor(color);
			return cv;
		}
	}
	
	private class EmptyStateAdapter extends BaseAdapter {
		final String title, sub;
		final View.OnClickListener action;
		EmptyStateAdapter(String t, String s, View.OnClickListener a) { title = t; sub = s; action = a; }
		@Override public int    getCount()        { return 1; }
		@Override public Object getItem(int p)    { return null; }
		@Override public long   getItemId(int p)  { return 0; }
		@Override public View getView(int p, View cv, ViewGroup parent) {
			if (cv == null) cv = LayoutInflater.from(ChatlistActivity.this)
			.inflate(R.layout.item_empty_state, parent, false);
			((TextView) cv.findViewById(R.id.txt_empty_title)) .setText(title);
			((TextView) cv.findViewById(R.id.txt_empty_sub))   .setText(sub);
			((TextView) cv.findViewById(R.id.txt_empty_action)).setOnClickListener(action);
			return cv;
		}
	}
	
	private class ChatAdapter extends BaseAdapter {
		@Override public int    getCount()        { return shownChats().size(); }
		@Override public Object getItem(int p)    { return shownChats().get(p); }
		@Override public long   getItemId(int p)  { return p; }
		@Override public View getView(int pos, View cv, ViewGroup parent) {
			if (cv == null) cv = LayoutInflater.from(ChatlistActivity.this)
			.inflate(R.layout.item_chat, parent, false);
			HashMap<String, Object> chat = shownChats().get(pos);
			String name    = (String) chat.get("peer_name");
			String photo   = (String) chat.get("peer_photo");
			String lastMsg = (String) chat.get("last_msg");
			Long   time    = (Long)   chat.get("last_time");
			Long   unread  = (Long)   chat.get("unread");
			
			((TextView) cv.findViewById(R.id.txt_name))
			.setText(name != null && !name.isEmpty() ? name : "User");
			String cid = (String) chat.get("chatId");
			boolean enc = false;
			if (cid != null && myUid != null) {
				com.silu.minigram.e2ee.E2ee e2 = com.silu.minigram.e2ee.E2ee.get(getApplicationContext(), myUid);
				enc = e2.isEncrypted(cid);
				String local = e2.localPreview(cid);
				if (local != null && com.silu.minigram.e2ee.E2ee.GENERIC_PREVIEW.equals(lastMsg)) lastMsg = local;
			}
			View pin = cv.findViewById(R.id.txt_pin);
			if (pin != null) pin.setVisibility(isPinned(cid) ? View.VISIBLE : View.GONE);
			View lock = cv.findViewById(R.id.txt_lock);
			if (lock != null) lock.setVisibility(enc ? View.VISIBLE : View.GONE);
			((TextView) cv.findViewById(R.id.txt_last_msg))
			.setText(lastMsg != null ? lastMsg : "");
			ImageView imgBadge = cv.findViewById(R.id.img_verified_badge);
			if (imgBadge != null) {
				boolean peerIsPro = Boolean.TRUE.equals(chat.get("peer_is_pro"));
				imgBadge.setVisibility(peerIsPro ? View.VISIBLE : View.GONE);
			}
			TextView txtTime = cv.findViewById(R.id.txt_time);
			txtTime.setText((time != null && time > 0)
			? new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(time)) : "");
			TextView txtUnread = cv.findViewById(R.id.txt_unread);
			if (unread != null && unread > 0) {
				txtUnread.setVisibility(View.VISIBLE);
				txtUnread.setText(unread > 99 ? "99+" : String.valueOf(unread));
			} else {
				txtUnread.setVisibility(View.GONE);
			}
			ImageView imgAv = cv.findViewById(R.id.img_avatar);
			AvatarHelper.load(getApplicationContext(), imgAv, photo, name);
			return cv;
		}
	}
	
	/** Issue 9 — uses filteredChannelList, shows owner badge on owned channels */
	private class ChannelAdapter extends BaseAdapter {
		@Override public int    getCount()        { return filteredChannelList.size(); }
		@Override public Object getItem(int p)    { return filteredChannelList.get(p); }
		@Override public long   getItemId(int p)  { return p; }
		@Override public View getView(int pos, View cv, ViewGroup parent) {
			if (cv == null) cv = LayoutInflater.from(ChatlistActivity.this)
			.inflate(R.layout.item_channel, parent, false);
			HashMap<String, Object> ch = filteredChannelList.get(pos);
			String name     = (String) ch.get("name");
			String photo    = (String) ch.get("photo_url");
			Long   subCount = (Long)   ch.get("subscriber_count");
			String ownerUid = (String) ch.get("owner_uid");
			
			((TextView) cv.findViewById(R.id.txt_channel_name)).setText(name != null ? name : "Channel");
			((TextView) cv.findViewById(R.id.txt_subscribers))
			.setText(com.silu.minigram.discover.Recommender.formatCount(subCount != null ? subCount : 0) + " subscribers");
			TextView descTv = cv.findViewById(R.id.txt_channel_desc);
			String desc = (String) ch.get("description");
			if (descTv != null) {
				descTv.setText(desc == null ? "" : desc.trim());
				descTv.setVisibility(desc == null || desc.trim().isEmpty() ? View.GONE : View.VISIBLE);
			}
			TextView badge = cv.findViewById(R.id.txt_owner_badge);
			badge.setVisibility(myUid != null && myUid.equals(ownerUid) ? View.VISIBLE : View.GONE);
			ImageView imgVerifiedBadge = cv.findViewById(R.id.img_verified_badge);
			if (imgVerifiedBadge != null) {
				boolean isVerified = Boolean.TRUE.equals(ch.get("is_verified"));
				imgVerifiedBadge.setVisibility(isVerified ? View.VISIBLE : View.GONE);
			}
			
			ImageView imgCh = cv.findViewById(R.id.img_channel);
			if (photo != null && !photo.isEmpty())
			Glide.with(getApplicationContext()).load(photo).circleCrop()
			.diskCacheStrategy(DiskCacheStrategy.ALL)
			.placeholder(R.drawable.ic_channel).error(R.drawable.ic_channel).into(imgCh);
			else imgCh.setImageResource(R.drawable.ic_channel);
			return cv;
		}
	}
	
	private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
}
