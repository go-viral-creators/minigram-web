package com.silu.minigram;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;
import java.util.*;

public class ChannelReplyActivity extends AppCompatActivity {
	
	// ─── Extras (received from ChannelActivity) ───────────────────────────────
	private String  channelId;
	private String  postId;
	private boolean isOwner;
	
	// ─── User ────────────────────────────────────────────────────────────────
	private String myUid   = "";
	private String myName  = "User";
	private String myPhoto = "";        // ← ADDED: current user's profile photo URL
	
	// ─── Views ───────────────────────────────────────────────────────────────
	private TextView btnBack;
	private TextView txtTitle;
	private TextView txtEmpty;
	private ListView listview1;
	private EditText editReply;
	private TextView btnSend;
	
	// ─── Firebase ────────────────────────────────────────────────────────────
	private DatabaseReference  repliesRef;
	private ValueEventListener repliesListener;
	
	// ─── Data ────────────────────────────────────────────────────────────────
	private final ArrayList<HashMap<String, Object>> replyList = new ArrayList<>();
	private ChannelReplyAdapter adapter;
	
	// =========================================================================
	// onCreate
	// =========================================================================
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeHelper.setup(this);
		setContentView(R.layout.channel_reply);
		EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));
		
		// ── Read intent extras ────────────────────────────────────────────────
		channelId = getIntent().getStringExtra("channel_id");
		postId    = getIntent().getStringExtra("post_id");
		isOwner   = getIntent().getBooleanExtra("is_owner", false);
		
		if (channelId == null || postId == null) { finish(); return; }
		
		// ── Current user ──────────────────────────────────────────────────────
		FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
		if (user != null) myUid = user.getUid();
		
		_bindViews();
		_loadMyProfile();
		
		// ── Firebase refs ─────────────────────────────────────────────────────
		repliesRef = FirebaseDatabase.getInstance()
		.getReference("channel_replies").child(channelId).child(postId);
		
		// ── Adapter ───────────────────────────────────────────────────────────
		adapter = new ChannelReplyAdapter(
		this, replyList, myUid, isOwner,
		replyId -> _confirmDelete(replyId)   // delete callback
		);
		listview1.setAdapter(adapter);
		
		_listenReplies();
	}
	
	// =========================================================================
	// Bind views & listeners
	// =========================================================================
	private void _bindViews() {
		btnBack   = findViewById(R.id.btn_back);
		txtTitle  = findViewById(R.id.txt_title);
		txtEmpty  = findViewById(R.id.txt_empty);
		listview1 = findViewById(R.id.listview1);
		editReply = findViewById(R.id.edit_reply);
		btnSend   = findViewById(R.id.btn_send);
		
		btnBack.setOnClickListener(v -> onBackPressed());
		btnSend.setOnClickListener(v -> _sendReply());
	}
	
	// =========================================================================
	// Load current user's display name AND photo from RTDB
	// =========================================================================
	private void _loadMyProfile() {
		if (myUid.isEmpty()) return;
		FirebaseDatabase.getInstance().getReference("users").child(myUid)
		.addListenerForSingleValueEvent(new ValueEventListener() {
			@Override
			public void onDataChange(@NonNull DataSnapshot snap) {
				// ── Name ─────────────────────────────────────────────────
				String n = snap.child("name").getValue(String.class);
				if (n == null || n.isEmpty())
				n = snap.child("username").getValue(String.class);
				if (n == null || n.isEmpty())
				n = snap.child("fullname").getValue(String.class);
				if (n != null && !n.isEmpty()) myName = n;
				
				// ── Photo ─────────────────────────────────────────────── ← ADDED
				String p = snap.child("photo_url").getValue(String.class);
				if (p != null && !p.isEmpty()) myPhoto = p;
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	// =========================================================================
	// Send reply
	// =========================================================================
	private void _sendReply() {
		String text = editReply.getText().toString().trim();
		if (TextUtils.isEmpty(text)) { editReply.setError("Write something"); return; }
		if (myUid.isEmpty()) { Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show(); return; }
		
		if (isOwner) { _writeReply(text); return; }
		
		// Fresh read, not the value this screen opened with — owner could
		// have turned replies off in ChannelSettingsActivity while this
		// screen was already sitting open.
		FirebaseDatabase.getInstance().getReference("channels").child(channelId)
			.child("allow_replies").addListenerForSingleValueEvent(new ValueEventListener() {
				@Override public void onDataChange(@NonNull DataSnapshot snap) {
					boolean allowed = !Boolean.FALSE.equals(snap.getValue(Boolean.class));
					if (allowed) _writeReply(text);
					else Toast.makeText(ChannelReplyActivity.this,
						"The channel owner has turned off replies", Toast.LENGTH_SHORT).show();
				}
				@Override public void onCancelled(@NonNull DatabaseError e) {
					// Can't confirm the current setting — fail closed rather
					// than let a reply through on a permissions check we
					// couldn't actually complete.
					Toast.makeText(ChannelReplyActivity.this,
						"Couldn't send — check your connection and try again", Toast.LENGTH_SHORT).show();
				}
			});
	}
	
	private void _writeReply(String text) {
		String replyId = repliesRef.push().getKey();
		if (replyId == null) return;
		
		HashMap<String, Object> reply = new HashMap<>();
		reply.put("sender_uid",   myUid);
		reply.put("sender_name",  myName);
		reply.put("sender_photo", myPhoto);     // ← ADDED: store photo URL with reply
		reply.put("text",         text);
		reply.put("timestamp",    System.currentTimeMillis());
		reply.put("is_deleted",   false);
		
		repliesRef.child(replyId).setValue(reply);
		
		// Increment reply_count on the parent post
		FirebaseDatabase.getInstance()
		.getReference("channel_posts").child(channelId).child(postId)
		.child("reply_count")
		.setValue(ServerValue.increment(1));
		
		editReply.setText("");
		_hideKeyboard();
	}
	
	// =========================================================================
	// Delete reply (with confirmation)
	// =========================================================================
	private void _confirmDelete(String replyId) {
		new AlertDialog.Builder(this)
		.setTitle("Delete Reply")
		.setMessage("Remove this reply for everyone?")
		.setPositiveButton("Delete", (d, w) -> {
			// Soft-delete: mark as deleted so listener still fires
			repliesRef.child(replyId).child("is_deleted").setValue(true);
			FirebaseDatabase.getInstance()
			.getReference("channel_posts").child(channelId).child(postId)
			.child("reply_count")
			.setValue(ServerValue.increment(-1));
		})
		.setNegativeButton("Cancel", null)
		.show();
	}
	
	// =========================================================================
	// Listen replies — real-time, ordered by timestamp
	// =========================================================================
	private void _listenReplies() {
		repliesListener = repliesRef.orderByChild("timestamp")
		.addValueEventListener(new ValueEventListener() {
			@Override
			public void onDataChange(@NonNull DataSnapshot snap) {
				replyList.clear();
				for (DataSnapshot child : snap.getChildren()) {
					
					// ── Pehle check karo ──────────────────────────────────
					boolean deleted = Boolean.TRUE.equals(
					child.child("is_deleted").getValue(Boolean.class));
					if (deleted) continue;
					// ──────────────────────────────────────────────────────
					
					HashMap<String, Object> reply = new HashMap<>();
					reply.put("replyId",      child.getKey());
					reply.put("sender_uid",   child.child("sender_uid").getValue(String.class));
					reply.put("sender_name",  child.child("sender_name").getValue(String.class));
					reply.put("sender_photo", child.child("sender_photo").getValue(String.class));
					reply.put("text",         child.child("text").getValue(String.class));
					reply.put("timestamp",    child.child("timestamp").getValue(Long.class));
					replyList.add(reply);
				}
				
				adapter.notifyDataSetChanged();
				
				// Toggle empty-state vs list
				boolean hasReplies = !replyList.isEmpty();
				if (listview1 != null) listview1.setVisibility(hasReplies ? View.VISIBLE : View.GONE);
				if (txtEmpty  != null) txtEmpty.setVisibility(hasReplies ? View.GONE   : View.VISIBLE);
				
				if (!replyList.isEmpty())
				listview1.post(() -> listview1.setSelection(replyList.size() - 1));
			}
			@Override public void onCancelled(@NonNull DatabaseError e) {}
		});
	}
	
	// =========================================================================
	// Lifecycle
	// =========================================================================
	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (repliesListener != null && repliesRef != null)
		repliesRef.removeEventListener(repliesListener);
	}
	
	@Override
	public void onBackPressed() { finish(); }
	
	// =========================================================================
	// Utility
	// =========================================================================
	private void _hideKeyboard() {
		InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		if (imm != null && editReply != null)
		imm.hideSoftInputFromWindow(editReply.getWindowToken(), 0);
	}
}
