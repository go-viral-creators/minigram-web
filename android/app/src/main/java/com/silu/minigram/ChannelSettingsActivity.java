package com.silu.minigram;

import java.util.List;
import java.util.ArrayList;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Locale;

public class ChannelSettingsActivity extends AppCompatActivity {
	
	private static final String WEB_BASE = "https://minigram.fireclashpro.com";
	
	private String channelId;
	private String myUid;
	private String currentPhotoUrl = "";
	private String currentHandle   = "";
	private String currentPrivacy  = "public";
	private Uri    newPhotoUri;
	
	private FirebaseAuth      auth;
	private FirebaseDatabase  database;
	private DatabaseReference channelRef;
	private DatabaseReference handlesRef;
	
	// ── Views ──────────────────────────────────────────────────────────────────
	private LinearLayout linear146;
	private ScrollView   vscroll10;
	private TextView     btn_back;
	private TextView     textview139;
	private LinearLayout linear147, linear148, linear150;
	private FrameLayout  frame_layout149;
	private TextView     txt_channel_name;
	private TextView     txt_channel_bio;     // NEW: bio display in header
	private ImageView    img_channel_dp;
	private TextView     textview140;
	private LinearLayout item_edit_info, item_subscribers, item_privacy, item_share_link, item_verify_channel;
	// Requirement 5 — Restrict Forwarding toggle. No such setting existed
	// anywhere in the codebase before; channels/{id}/restrict_forwarding is a
	// new field, enforced in ChannelActivity (hides Forward for non-owners)
	// and re-checked fresh from the DB in ForwardChatListActivity right
	// before any write.
	private LinearLayout item_restrict_forward;
	private Switch       sw_restrict_forward;
	private TextView     txt_restrict_forward_sub;
	private boolean      currentRestrictForwarding = false;
	// Phase 2, Requirement 2 — owner control over whether subscribers can
	// reply to posts. Default true (open) — see ChannelActivity's
	// _checkReplyPermission()/_openReplies() and ChannelReplyActivity's
	// _sendReply() for where this is actually enforced.
	private LinearLayout item_allow_replies;
	private Switch       sw_allow_replies;
	private TextView     txt_allow_replies_sub;
	private boolean      currentAllowReplies = true;
	// Requirement 2 — "who can post". Opens ChannelAdminsActivity where the
	// owner grants/revokes posting rights per-subscriber.
	private LinearLayout item_channel_admins;
	private TextView     txt_verify_label;
	private View         view152;
	private LinearLayout btn_delete_channel;
	private TextView     textview141, textview142, textview143;
	private TextView     textview144, textview145;
	private TextView     txt_sub_count;
	private TextView     textview146, textview147, textview148, textview149;
	private TextView     textview150, textview151;
	private LinearLayout linear151;
	private TextView     textview_share_icon, textview_share_label;
	private LinearLayout loadingOverlay;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
        EdgeToEdgeHelper.setup(this);
		setContentView(R.layout.channel_settings);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));
		initialize(_savedInstanceState);
		FirebaseApp.initializeApp(this);
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
		linear146         = findViewById(R.id.linear146);
		vscroll10         = findViewById(R.id.vscroll10);
		btn_back          = findViewById(R.id.btn_back);
		textview139       = findViewById(R.id.textview139);
		linear147         = findViewById(R.id.linear147);
		linear148         = findViewById(R.id.linear148);
		linear150         = findViewById(R.id.linear150);
		frame_layout149   = findViewById(R.id.frame_layout149);
		txt_channel_name  = findViewById(R.id.txt_channel_name);
		txt_channel_bio   = findViewById(R.id.txt_channel_bio);   // NEW
		img_channel_dp    = findViewById(R.id.img_channel_dp);
		textview140       = findViewById(R.id.textview140);
		item_edit_info    = findViewById(R.id.item_edit_info);
		item_subscribers  = findViewById(R.id.item_subscribers);
		item_privacy      = findViewById(R.id.item_privacy);
		item_restrict_forward    = findViewById(R.id.item_restrict_forward);
		sw_restrict_forward      = findViewById(R.id.sw_restrict_forward);
		txt_restrict_forward_sub = findViewById(R.id.txt_restrict_forward_sub);
		item_allow_replies    = findViewById(R.id.item_allow_replies);
		sw_allow_replies      = findViewById(R.id.sw_allow_replies);
		txt_allow_replies_sub = findViewById(R.id.txt_allow_replies_sub);
		item_channel_admins   = findViewById(R.id.item_channel_admins);
		view152           = findViewById(R.id.view152);
		btn_delete_channel= findViewById(R.id.btn_delete_channel);
		textview141       = findViewById(R.id.textview141);
		textview142       = findViewById(R.id.textview142);
		textview143       = findViewById(R.id.textview143);
		textview144       = findViewById(R.id.textview144);
		textview145       = findViewById(R.id.textview145);
		txt_sub_count     = findViewById(R.id.txt_sub_count);
		textview146       = findViewById(R.id.textview146);
		linear151         = findViewById(R.id.linear151);
		textview149       = findViewById(R.id.textview149);
		textview147       = findViewById(R.id.textview147);
		textview148       = findViewById(R.id.textview148);
		textview150       = findViewById(R.id.textview150);
		textview151       = findViewById(R.id.textview151);
		item_share_link   = findViewById(R.id.item_share_link);
		textview_share_icon  = findViewById(R.id.textview_share_icon);
		textview_share_label = findViewById(R.id.textview_share_label);
		item_verify_channel  = findViewById(R.id.item_verify_channel);
		txt_verify_label     = findViewById(R.id.txt_verify_label);
		
		// Loading overlay
		loadingOverlay = new LinearLayout(this);
		loadingOverlay.setBackgroundColor(0xAA000000);
		loadingOverlay.setGravity(android.view.Gravity.CENTER);
		loadingOverlay.setVisibility(View.GONE);
		android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
		pb.setIndeterminate(true);
		pb.getIndeterminateDrawable().setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
		loadingOverlay.addView(pb);
		android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
		ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
		((ViewGroup) getWindow().getDecorView()).addView(loadingOverlay, flp);
		
		// ── Click listeners ───────────────────────────────────────────────────
		btn_back.setOnClickListener(v -> finish());
		frame_layout149.setOnClickListener(v -> _pickPhoto());
		textview140.setOnClickListener(v -> _pickPhoto());
		item_edit_info.setOnClickListener(v -> _showEditDialog());
		// Removed from Channel Info: Subscribers, Channel Admins, and
		// Share Channel Link — all three now live on the Channel Profile
		// screen instead. Rows hidden rather than deleted from the layout
		// so nothing else that references these ids can break.
		if (item_subscribers != null) item_subscribers.setVisibility(View.GONE);
		if (item_channel_admins != null) item_channel_admins.setVisibility(View.GONE);
		if (item_share_link != null) item_share_link.setVisibility(View.GONE);
		btn_delete_channel.setOnClickListener(v -> _confirmDelete());
		item_privacy.setOnClickListener(v -> _showChannelTypeDialog());
		if (item_restrict_forward != null)
		item_restrict_forward.setOnClickListener(v -> _toggleRestrictForwarding());
		if (item_allow_replies != null)
		item_allow_replies.setOnClickListener(v -> _toggleAllowReplies());
		if (item_verify_channel != null)
		item_verify_channel.setOnClickListener(v ->
			ChannelVerifySheet.newInstance(channelId, txt_channel_name.getText().toString())
				.show(getSupportFragmentManager(), "channel_verify"));
	}
	
	private void initializeLogic() {
		auth       = FirebaseAuth.getInstance();
		database   = FirebaseDatabase.getInstance();
		myUid      = auth.getCurrentUser().getUid();
		channelId  = getIntent().getStringExtra("channel_id");
		channelRef = database.getReference("channels").child(channelId);
		handlesRef = database.getReference("handles");
		_loadChannelData();
	}
	
	// ─── Load channel data ────────────────────────────────────────────────────
	
	private void _loadChannelData() {
		channelRef.addValueEventListener(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				if (!snap.exists()) return;
				
				String name     = snap.child("name").getValue(String.class);
				String bio      = snap.child("description").getValue(String.class);
				String photo    = snap.child("photo_url").getValue(String.class);
				String privacy  = snap.child("privacy").getValue(String.class);
				String handle   = snap.child("handle").getValue(String.class);
				boolean isVerified = Boolean.TRUE.equals(snap.child("is_verified").getValue(Boolean.class));
				currentRestrictForwarding = Boolean.TRUE.equals(
					snap.child("restrict_forwarding").getValue(Boolean.class));
				currentAllowReplies = !Boolean.FALSE.equals(
					snap.child("allow_replies").getValue(Boolean.class));
				
				if (name != null) txt_channel_name.setText(name);
				if (txt_verify_label != null) {
					txt_verify_label.setText(isVerified ? "Channel Verified ✓" : "Verify Channel (Blue Tick)");
				}
				
				// ✅ FIX: subscriber_count stored field was corrupted by old buggy code.
				// Always derive real count from actual channel_subscribers children,
				// then auto-heal the stored field so it stays in sync.
				database.getReference("channel_subscribers").child(channelId)
				.addListenerForSingleValueEvent(new ValueEventListener() {
					@Override public void onDataChange(@NonNull DataSnapshot subSnap) {
						long realCount = subSnap.getChildrenCount();
						txt_sub_count.setText(realCount + " subscribers");
						// Auto-heal stored field if it differs
						Long stored = snap.child("subscriber_count").getValue(Long.class);
						if (stored == null || stored != realCount) {
							channelRef.child("subscriber_count").setValue(realCount);
						}
					}
					@Override public void onCancelled(@NonNull DatabaseError e) {}
				});
				if (handle != null && !handle.isEmpty()) currentHandle = handle;
				if (privacy != null)  currentPrivacy = privacy;
				
				// ── Bio display in header ──
				if (txt_channel_bio != null) {
					if (bio != null && !bio.isEmpty()) {
						txt_channel_bio.setText(bio);
						txt_channel_bio.setVisibility(View.VISIBLE);
					} else {
						txt_channel_bio.setVisibility(View.GONE);
					}
				}
				
				// Privacy subtitle
				textview148.setText("private".equals(currentPrivacy)
				? "Private · Invite only"
				: "Public · Anyone can find");

				if (sw_restrict_forward != null) sw_restrict_forward.setChecked(currentRestrictForwarding);
				if (txt_restrict_forward_sub != null) {
					txt_restrict_forward_sub.setText(currentRestrictForwarding
						? "Subscribers can't forward posts"
						: "Subscribers can forward posts");
				}
				if (sw_allow_replies != null) sw_allow_replies.setChecked(currentAllowReplies);
				if (txt_allow_replies_sub != null) {
					txt_allow_replies_sub.setText(currentAllowReplies
						? "Subscribers can reply to posts"
						: "Subscribers can't reply to posts");
				}
				
				if (photo != null && !photo.isEmpty()) {
					currentPhotoUrl = photo;
					try {
						Glide.with(getApplicationContext()).load(photo).circleCrop().into(img_channel_dp);
					} catch (Exception e) { e.printStackTrace(); }
				}
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	// ─── Channel Type dialog ─────────────────────────────────────────────────
	
	private void _showChannelTypeDialog() {
		View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_channel_type, null);

		RadioButton radioPublic  = dialogView.findViewById(R.id.radio_public);
		RadioButton radioPrivate = dialogView.findViewById(R.id.radio_private);
		View rowPublic  = dialogView.findViewById(R.id.row_public);
		View rowPrivate = dialogView.findViewById(R.id.row_private);

		boolean isPrivate = "private".equals(currentPrivacy);
		radioPublic.setChecked(!isPrivate);
		radioPrivate.setChecked(isPrivate);

		android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
			.setView(dialogView)
			.create();
		if (dialog.getWindow() != null) {
			android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
			bg.setColor(Color.parseColor("#FFFFFF"));
			bg.setCornerRadius(dp(16));
			dialog.getWindow().setBackgroundDrawable(bg);
		}

		// Tapping anywhere on a row selects it — not just the small radio
		// circle, matching Telegram/WhatsApp's tap-target size.
		View.OnClickListener selectPublic  = v -> { radioPublic.setChecked(true);  radioPrivate.setChecked(false); };
		View.OnClickListener selectPrivate = v -> { radioPrivate.setChecked(true); radioPublic.setChecked(false); };
		rowPublic.setOnClickListener(selectPublic);
		rowPrivate.setOnClickListener(selectPrivate);
		radioPublic.setOnClickListener(selectPublic);
		radioPrivate.setOnClickListener(selectPrivate);

		dialogView.findViewById(R.id.btn_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
		dialogView.findViewById(R.id.btn_dialog_save).setOnClickListener(v -> {
			String newPrivacy = radioPrivate.isChecked() ? "private" : "public";
			if (!newPrivacy.equals(currentPrivacy)) _savePrivacy(newPrivacy);
			dialog.dismiss();
		});

		dialog.show();
	}
	
	private void _savePrivacy(String newPrivacy) {
		_loading(true);
		channelRef.child("privacy").setValue(newPrivacy).addOnCompleteListener(task -> {
			_loading(false);
			if (task.isSuccessful()) {
				currentPrivacy = newPrivacy;
				textview148.setText("private".equals(newPrivacy)
				? "Private · Invite only"
				: "Public · Anyone can find");
				Toast.makeText(this,
				"private".equals(newPrivacy) ? "Channel set to Private" : "Channel set to Public",
				Toast.LENGTH_SHORT).show();
			} else {
				Toast.makeText(this, "Failed to update type", Toast.LENGTH_SHORT).show();
			}
		});
	}
	
	private void _toggleRestrictForwarding() {
		final boolean newValue = !currentRestrictForwarding;
		// Reflect immediately for a responsive feel; _loadChannelData's
		// always-on listener will confirm (or correct) this from the DB
		// once the write lands.
		if (sw_restrict_forward != null) sw_restrict_forward.setChecked(newValue);
		_loading(true);
		channelRef.child("restrict_forwarding").setValue(newValue).addOnCompleteListener(task -> {
			_loading(false);
			if (task.isSuccessful()) {
				currentRestrictForwarding = newValue;
				if (txt_restrict_forward_sub != null) {
					txt_restrict_forward_sub.setText(newValue
						? "Subscribers can't forward posts"
						: "Subscribers can forward posts");
				}
				Toast.makeText(this,
					newValue ? "Forwarding restricted" : "Forwarding allowed",
					Toast.LENGTH_SHORT).show();
			} else {
				if (sw_restrict_forward != null) sw_restrict_forward.setChecked(currentRestrictForwarding);
				Toast.makeText(this, "Failed to update setting", Toast.LENGTH_SHORT).show();
			}
		});
	}
	
	private void _toggleAllowReplies() {
		final boolean newValue = !currentAllowReplies;
		if (sw_allow_replies != null) sw_allow_replies.setChecked(newValue);
		_loading(true);
		channelRef.child("allow_replies").setValue(newValue).addOnCompleteListener(task -> {
			_loading(false);
			if (task.isSuccessful()) {
				currentAllowReplies = newValue;
				if (txt_allow_replies_sub != null) {
					txt_allow_replies_sub.setText(newValue
						? "Subscribers can reply to posts"
						: "Subscribers can't reply to posts");
				}
				Toast.makeText(this,
					newValue ? "Replies allowed" : "Replies turned off",
					Toast.LENGTH_SHORT).show();
			} else {
				if (sw_allow_replies != null) sw_allow_replies.setChecked(currentAllowReplies);
				Toast.makeText(this, "Failed to update setting", Toast.LENGTH_SHORT).show();
			}
		});
	}
	
	// ─── Share options ────────────────────────────────────────────────────────
	
	private void _showShareOptions() {
		String webLink = _buildChannelWebLink();
		new android.app.AlertDialog.Builder(this)
		.setTitle("Share Channel")
		.setItems(new String[]{"Copy Link", "Share via..."}, (d, which) -> {
			if (which == 0) {
				_copyToClipboard("Channel Link", webLink);
				Toast.makeText(this, "Link copied!", Toast.LENGTH_SHORT).show();
			} else {
				_shareViaIntent(txt_channel_name.getText().toString(), webLink);
			}
		})
		.setNegativeButton("Cancel", null).show();
	}
	
	private String _buildChannelWebLink() {
		// FIX: "/admin/c/" hai, sirf "/c/" nahi — share-link backend ab
		// poora /admin/ folder ke andar consolidated hai. Manifest ka
		// pathPrefix bhi isi ke saath match karta hai.
		if (!currentHandle.isEmpty()) return WEB_BASE + "/admin/c/" + currentHandle;
		return "minigram://channel/" + channelId;
	}
	
	private void _copyToClipboard(String label, String text) {
		ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		cm.setPrimaryClip(ClipData.newPlainText(label, text));
	}
	
	private void _shareViaIntent(String channelName, String link) {
		Intent share = new Intent(Intent.ACTION_SEND);
		share.setType("text/plain");
		share.putExtra(Intent.EXTRA_SUBJECT, "Join " + channelName + " on Minigram");
		share.putExtra(Intent.EXTRA_TEXT, "Join \"" + channelName + "\" on Minigram:\n" + link);
		startActivity(Intent.createChooser(share, "Share channel via"));
	}
	
	// ─── Photo ────────────────────────────────────────────────────────────────
	
	private void _pickPhoto() {
		Intent intent = new Intent();
		intent.setType("image/*");
		intent.setAction(Intent.ACTION_GET_CONTENT);
		startActivityForResult(Intent.createChooser(intent, "Select Channel Photo"), 5001);
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == 5001 && resultCode == RESULT_OK && data != null) {
			newPhotoUri = data.getData();
			if (newPhotoUri == null) return;
			Glide.with(getApplicationContext()).load(newPhotoUri).circleCrop().into(img_channel_dp);
			_uploadAndSavePhoto();
		}
	}
	
	private void _uploadAndSavePhoto() {
		_loading(true);
		UploadHelper.uploadImage(this, newPhotoUri, new UploadHelper.UploadCallback() {
			@Override public void onSuccess(String url) {
				runOnUiThread(() -> {
					currentPhotoUrl = url;
					channelRef.child("photo_url").setValue(url);
					_loading(false);
					Toast.makeText(ChannelSettingsActivity.this, "Photo updated!", Toast.LENGTH_SHORT).show();
				});
			}
			@Override public void onError(String message) {
				runOnUiThread(() -> {
					_loading(false);
					Toast.makeText(ChannelSettingsActivity.this, message, Toast.LENGTH_LONG).show();
				});
			}
		});
	}
	
	// ─── Edit Info dialog (name + description + handle) ──────────────────────
	
	private void _showEditDialog() {
		channelRef.addListenerForSingleValueEvent(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				String curName   = snap.child("name").getValue(String.class);
				String curDesc   = snap.child("description").getValue(String.class);
				String curHandle = snap.child("handle").getValue(String.class);
				if (curHandle == null) curHandle = "";
				
				android.app.AlertDialog.Builder builder =
				new android.app.AlertDialog.Builder(ChannelSettingsActivity.this);
				builder.setTitle("Edit Channel Info");
				
				LinearLayout layout = new LinearLayout(ChannelSettingsActivity.this);
				layout.setOrientation(LinearLayout.VERTICAL);
				layout.setPadding(48, 16, 48, 16);
				
				// Name
				TextView labelName = new TextView(ChannelSettingsActivity.this);
				labelName.setText("Channel Name");
				labelName.setTextSize(12f);
				labelName.setTextColor(Color.parseColor("#757575"));
				layout.addView(labelName);
				EditText etName = new EditText(ChannelSettingsActivity.this);
				etName.setText(curName);
				etName.setTextSize(16f);
				etName.setSingleLine(true);
				layout.addView(etName);
				
				// Bio / Description
				TextView labelDesc = new TextView(ChannelSettingsActivity.this);
				labelDesc.setText("Bio / Description");
				labelDesc.setTextSize(12f);
				labelDesc.setTextColor(Color.parseColor("#757575"));
				labelDesc.setPadding(0, 24, 0, 0);
				layout.addView(labelDesc);
				EditText etDesc = new EditText(ChannelSettingsActivity.this);
				etDesc.setText(curDesc);
				etDesc.setTextSize(16f);
				etDesc.setMaxLines(4);
				etDesc.setHint("Short description shown in channel header...");
				layout.addView(etDesc);
				
				// Handle
				TextView labelHandle = new TextView(ChannelSettingsActivity.this);
				labelHandle.setText("Handle  (@handle · affects share link)");
				labelHandle.setTextSize(12f);
				labelHandle.setTextColor(Color.parseColor("#757575"));
				labelHandle.setPadding(0, 24, 0, 0);
				layout.addView(labelHandle);
				TextView hintHandle = new TextView(ChannelSettingsActivity.this);
				hintHandle.setText("4–20 chars: a-z, 0-9, _");
				hintHandle.setTextSize(11f);
				hintHandle.setTextColor(Color.parseColor("#9E9E9E"));
				layout.addView(hintHandle);
				final String oldHandle = curHandle;
				EditText etHandle = new EditText(ChannelSettingsActivity.this);
				etHandle.setText(oldHandle);
				etHandle.setTextSize(16f);
				etHandle.setSingleLine(true);
				layout.addView(etHandle);
				
				builder.setView(layout);
				builder.setPositiveButton("Save", (d, w) -> {
					String newName   = etName.getText().toString().trim();
					String newDesc   = etDesc.getText().toString().trim();
					String newHandle = etHandle.getText().toString().trim()
					.toLowerCase(Locale.ENGLISH).replaceAll("\\s+", "");
					if (newName.isEmpty()) {
						Toast.makeText(ChannelSettingsActivity.this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
						return;
					}
					if (!newHandle.isEmpty() && !_isValidHandle(newHandle)) {
						Toast.makeText(ChannelSettingsActivity.this,
						"Invalid handle: 4–20 chars, a-z 0-9 _ only", Toast.LENGTH_SHORT).show();
						return;
					}
					_saveChannelInfo(newName, newDesc, newHandle, oldHandle);
				});
				builder.setNegativeButton("Cancel", null);
				builder.show();
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	private void _saveChannelInfo(String newName, String newDesc, String newHandle, String oldHandle) {
		_loading(true);
		if (newHandle.isEmpty() || newHandle.equals(oldHandle)) {
			HashMap<String, Object> updates = new HashMap<>();
			updates.put("name",        newName);
			updates.put("name_lower",  newName.toLowerCase(java.util.Locale.ENGLISH));
			updates.put("description", newDesc);
			channelRef.updateChildren(updates).addOnCompleteListener(task -> {
				_loading(false);
				if (task.isSuccessful())
				Toast.makeText(this, "Updated!", Toast.LENGTH_SHORT).show();
			});
			return;
		}
		handlesRef.child(newHandle).addListenerForSingleValueEvent(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				if (snap.exists()) {
					_loading(false);
					Toast.makeText(ChannelSettingsActivity.this,
					"@" + newHandle + " is already taken", Toast.LENGTH_SHORT).show();
					return;
				}
				HashMap<String, Object> handleData = new HashMap<>();
				handleData.put("type", "channel");
				handleData.put("id",   channelId);
				HashMap<String, Object> multiWrite = new HashMap<>();
				if (!oldHandle.isEmpty()) multiWrite.put("handles/" + oldHandle, null);
				multiWrite.put("handles/"   + newHandle,                   handleData);
				multiWrite.put("channels/"  + channelId + "/handle",       newHandle);
				multiWrite.put("channels/"  + channelId + "/name",         newName);
				multiWrite.put("channels/"  + channelId + "/name_lower",   newName.toLowerCase(java.util.Locale.ENGLISH));
				multiWrite.put("channels/"  + channelId + "/description",  newDesc);
				database.getReference().updateChildren(multiWrite).addOnCompleteListener(task -> {
					_loading(false);
					if (task.isSuccessful()) {
						currentHandle = newHandle;
						Toast.makeText(ChannelSettingsActivity.this,
						"Updated! New link:\n" + WEB_BASE + "/admin/c/" + newHandle,
						Toast.LENGTH_LONG).show();
					} else {
						Toast.makeText(ChannelSettingsActivity.this,
						"Update failed: " + task.getException().getMessage(),
						Toast.LENGTH_LONG).show();
					}
				});
			}
			@Override public void onCancelled(@NonNull DatabaseError error) {
				_loading(false);
				Toast.makeText(ChannelSettingsActivity.this, "Network error. Try again.", Toast.LENGTH_SHORT).show();
			}
		});
	}
	
	// ─── Subscribers ──────────────────────────────────────────────────────────
	
	private void _showSubscribersDialog() {
		_loading(true);
		database.getReference("channel_subscribers").child(channelId)
		.addListenerForSingleValueEvent(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				_loading(false);
				long realCount = snap.getChildrenCount();
				
				// ✅ FIX: Sync stored subscriber_count with real count every time dialog opens
				channelRef.child("subscriber_count").setValue(realCount);
				// Also update UI immediately
				txt_sub_count.setText(realCount + " subscribers");
				
				// Build subscriber list for dialog
				if (realCount == 0) {
					new android.app.AlertDialog.Builder(ChannelSettingsActivity.this)
					.setTitle("Subscribers")
					.setMessage("No subscribers yet.")
					.setPositiveButton("OK", null)
					.show();
					return;
				}
				
				// Fetch each subscriber's name from users node
				List<String> uidList = new ArrayList<>();
				for (DataSnapshot child : snap.getChildren()) uidList.add(child.getKey());
				
				final String[] names = new String[(int) realCount];
				final int[] loaded = {0};
				for (int i = 0; i < uidList.size(); i++) {
					final int idx = i;
					database.getReference("users").child(uidList.get(idx)).child("fullname")
					.addListenerForSingleValueEvent(new ValueEventListener() {
						@Override public void onDataChange(@NonNull DataSnapshot u) {
							names[idx] = u.getValue(String.class) != null
							? u.getValue(String.class) : "Unknown";
							loaded[0]++;
							if (loaded[0] == (int) realCount) {
								// All names fetched — show dialog
								new android.app.AlertDialog.Builder(ChannelSettingsActivity.this)
								.setTitle(realCount + " Subscriber(s)")
								.setItems(names, null)
								.setPositiveButton("Close", null)
								.show();
							}
						}
						@Override public void onCancelled(@NonNull DatabaseError e) {
							loaded[0]++;
							names[idx] = "Unknown";
						}
					});
				}
			}
			@Override public void onCancelled(@NonNull DatabaseError e) { _loading(false); }
		});
	}
	
	// ─── Delete channel ───────────────────────────────────────────────────────
	
	private void _confirmDelete() {
		new android.app.AlertDialog.Builder(this)
		.setTitle("Delete Channel")
		.setMessage("Are you sure? This will permanently delete the channel and all posts.")
		.setPositiveButton("Delete", (d, w) -> _deleteChannel())
		.setNegativeButton("Cancel", null).show();
	}
	
	private void _deleteChannel() {
		_loading(true);
		database.getReference("channel_posts").child(channelId).removeValue();
		ChannelPostCacheHelper.get(this).clearChannel(channelId);
		database.getReference("channel_subscribers").child(channelId)
		.addListenerForSingleValueEvent(new ValueEventListener() {
			@Override public void onDataChange(@NonNull DataSnapshot snap) {
				for (DataSnapshot sub : snap.getChildren())
				database.getReference("user_channels").child(sub.getKey()).child(channelId).removeValue();
				database.getReference("channel_subscribers").child(channelId).removeValue();
				if (!currentHandle.isEmpty()) handlesRef.child(currentHandle).removeValue();
				channelRef.removeValue().addOnCompleteListener(task -> {
					_loading(false);
					Toast.makeText(ChannelSettingsActivity.this, "Channel deleted", Toast.LENGTH_SHORT).show();
					Intent i = new Intent(ChannelSettingsActivity.this, ChatlistActivity.class);
					i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
					startActivity(i);
					finish();
				});
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {
				_loading(false);
				Toast.makeText(ChannelSettingsActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
			}
		});
	}
	
	// ─── Helpers ─────────────────────────────────────────────────────────────
	
	private boolean _isValidHandle(String handle) {
		return handle != null && handle.matches("[a-z0-9_]{4,20}");
	}
	
	private void _loading(boolean show) {
		loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
	}
	
	private int dp(int v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}
}
