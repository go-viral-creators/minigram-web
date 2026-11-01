package com.silu.minigram;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.util.*;
import android.view.*;
import android.view.View;
import android.view.View.*;
import android.view.animation.*;
import android.webkit.*;
import android.widget.*;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import android.view.inputmethod.InputMethodManager;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.HashMap;
import java.util.regex.*;
import org.json.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

public class EditProfileActivity extends AppCompatActivity {

	private Uri imageUri;
	private String uploadedPhotoUrl = "";
	private String myUid;
	private boolean isUploading = false;

	// ── Cached old handle to detect changes ──────────────────────────
	private String oldHandle = "";
	// Requirement 5 — cached so we can tell if the number actually changed,
	// same idea as oldHandle above.
	private String oldPhoneCc = "";
	private String oldPhoneNumber = "";

	private LinearLayout linear180;
	private ScrollView vscroll14;
	private LinearLayout loadingOverlay;
	private TextView btn_back;
	private TextView textview174;
	private TextView btn_save;
	private LinearLayout linear181;
	private android.widget.RelativeLayout relativelayout11;
	private LinearLayout linear182;
	private View view183;
	private LinearLayout linear184;
	private View view185;
	private LinearLayout linear186;
	private TextView textview179;
	private ImageView img_profile;
	private LinearLayout btn_change_photo;
	private TextView textview175;
	private TextView textview176;
	private EditText et_fullname;
	private TextView txt_fullname_error, txt_username_error;
	private TextView textview177;
	private EditText et_username;   // used as handle field
	private TextView textview178;
	private EditText et_bio;
	private EditText et_country_code, et_phone;
	private TextView txt_phone_error;
	private LinearLayout loadingBarCard;
	private ProgressBar progressbar2;
	private TextView textview2;

	private FirebaseAuth auth;
	private DatabaseReference usersRef;
	private DatabaseReference handlesRef;

	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
        EdgeToEdgeHelper.setup(this);
		setContentView(R.layout.edit_profile);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));
		initialize(_savedInstanceState);
		FirebaseApp.initializeApp(this);
		initializeLogic();
	}

	private void initialize(Bundle _savedInstanceState) {
		linear180        = findViewById(R.id.linear180);
		vscroll14        = findViewById(R.id.vscroll14);
		loadingOverlay   = findViewById(R.id.loadingOverlay);
		btn_back         = findViewById(R.id.btn_back);
		textview174      = findViewById(R.id.textview174);
		btn_save         = findViewById(R.id.btn_save);
		linear181        = findViewById(R.id.linear181);
		relativelayout11 = findViewById(R.id.relativelayout11);
		linear182        = findViewById(R.id.linear182);
		view183          = findViewById(R.id.view183);
		linear184        = findViewById(R.id.linear184);
		view185          = findViewById(R.id.view185);
		linear186        = findViewById(R.id.linear186);
		textview179      = findViewById(R.id.textview179);
		img_profile      = findViewById(R.id.img_profile);
		btn_change_photo = findViewById(R.id.btn_change_photo);
		textview175      = findViewById(R.id.textview175);
		textview176      = findViewById(R.id.textview176);
		et_fullname      = findViewById(R.id.et_fullname);
		txt_fullname_error = findViewById(R.id.txt_fullname_error);
		txt_username_error = findViewById(R.id.txt_username_error);
		textview177      = findViewById(R.id.textview177);
		et_username      = findViewById(R.id.et_username);
		textview178      = findViewById(R.id.textview178);
		et_bio           = findViewById(R.id.et_bio);
		et_country_code  = findViewById(R.id.et_country_code);
		et_phone         = findViewById(R.id.et_phone);
		txt_phone_error  = findViewById(R.id.txt_phone_error);
		loadingBarCard   = findViewById(R.id.loadingBarCard);
		progressbar2     = findViewById(R.id.progressbar2);
		textview2        = findViewById(R.id.textview2);

		btn_back.setOnClickListener(v -> finish());

		btn_change_photo.setOnClickListener(v -> {
			_hideKeyboard();
			pickImage.launch("image/*");
		});

		btn_save.setOnClickListener(v -> {
			textview2.setText("Saving...");
			_hideKeyboard();
			_saveProfile();
		});
	}

	private void initializeLogic() {
		auth = FirebaseAuth.getInstance();
		if (auth.getCurrentUser() == null) {
			finish();
			return;
		}
		myUid      = auth.getCurrentUser().getUid();
		usersRef   = FirebaseDatabase.getInstance().getReference("users");
		handlesRef = FirebaseDatabase.getInstance().getReference("handles");
		_loadProfile();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		isUploading = false;
	}

	// ─── Load profile ─────────────────────────────────────────────────────────

	private void _loadProfile() {
		textview2.setText("Loading...");
		_loadingBar(true);
		usersRef.child(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
			@Override
			public void onDataChange(@NonNull DataSnapshot snapshot) {
				if (isFinishing() || isDestroyed()) return;
				_loadingBar(false);
				if (snapshot.exists()) {
					String fullname  = snapshot.child("fullname").getValue(String.class);
					// Read handle — fall back to username for older accounts
					String handle    = snapshot.child("handle").getValue(String.class);
					if (handle == null || handle.isEmpty())
						handle = snapshot.child("username").getValue(String.class);
					String bio       = snapshot.child("bio").getValue(String.class);
					String photoUrl  = snapshot.child("photo_url").getValue(String.class);

					if (fullname != null) et_fullname.setText(fullname);
					if (handle  != null) {
						et_username.setText(handle);
						oldHandle = handle;   // cache for change detection
					}
					if (bio != null) et_bio.setText(bio);
					PrivateProfile.readOwn(myUid, (e164, phoneCc, phoneNumber) -> {
					if (phoneCc != null && !phoneCc.isEmpty()) {
						et_country_code.setText(phoneCc);
						oldPhoneCc = phoneCc;
					}
					if (phoneNumber != null && !phoneNumber.isEmpty()) {
						et_phone.setText(phoneNumber);
						oldPhoneNumber = phoneNumber;
					}
					if (photoUrl != null && !photoUrl.isEmpty()) {
						uploadedPhotoUrl = photoUrl;
						Glide.with(img_profile)
							.load(photoUrl)
							.circleCrop()
							.placeholder(R.drawable.default_profile)
							.error(R.drawable.default_profile)
							.into(img_profile);
					}
					});
				}
			}

			@Override
			public void onCancelled(@NonNull DatabaseError error) {
				_loadingBar(false);
				if (isFinishing() || isDestroyed()) return;
				Toast.makeText(EditProfileActivity.this, "Failed to load profile", Toast.LENGTH_SHORT).show();
			}
		});
	}

	// ─── Save profile (handle-aware) ──────────────────────────────────────────

	// Reliable inline field errors — Android's TextView.setError() has a
	// known bug on several devices/OEMs where the default error icon's
	// bounds are mismeasured, causing the EditText to blow up to a huge
	// height. A plain error TextView below the field avoids that entirely.
	private void _showFieldError(TextView errorView, String message) {
		if (errorView == null) return;
		errorView.setText(message);
		errorView.setVisibility(View.VISIBLE);
	}

	private void _hideFieldError(TextView errorView) {
		if (errorView == null) return;
		errorView.setVisibility(View.GONE);
	}

	private void _saveProfile() {
		if (isUploading) {
			Toast.makeText(this, "Please wait for upload to finish", Toast.LENGTH_SHORT).show();
			return;
		}

		String fullname  = et_fullname.getText().toString().trim();
		String newHandle = et_username.getText().toString().trim()
				.toLowerCase(Locale.ENGLISH).replaceAll("\\s+", "");
		String bio       = et_bio.getText().toString().trim();
		String newCc     = et_country_code.getText().toString().trim();
		String newNumber = et_phone.getText().toString().trim();
		String e164      = _toE164(newCc, newNumber);

		// Clear any previous errors before re-validating
		_hideFieldError(txt_fullname_error);
		_hideFieldError(txt_username_error);
		_hideFieldError(txt_phone_error);

		// ── Basic validation ──────────────────────────────────────────
		if (fullname.isEmpty()) {
			_showFieldError(txt_fullname_error, "Name is required");
			et_fullname.requestFocus();
			return;
		}
		if (!_isValidHandle(newHandle)) {
			_showFieldError(txt_username_error, "Handle must be 4–20 chars: letters, digits, underscores only");
			et_username.requestFocus();
			return;
		}
		if (!_isValidE164(e164)) {
			_showFieldError(txt_phone_error, "Enter a valid phone number with country code");
			et_phone.requestFocus();
			return;
		}

		_loadingBar(true);

		boolean handleChanged = !newHandle.equals(oldHandle);
		boolean phoneChanged  = !newCc.equals(oldPhoneCc) || !newNumber.equals(oldPhoneNumber);

		if (!handleChanged && !phoneChanged) {
			_writeProfileFields(fullname, newHandle, bio, newCc, newNumber, e164);
			return;
		}

		final int[] pending = {(handleChanged ? 1 : 0) + (phoneChanged ? 1 : 0)};
		final boolean[] handleOk = {!handleChanged};
		final boolean[] phoneOk  = {!phoneChanged};
		final boolean[] handleCheckFailed = {false};
		final boolean[] phoneCheckFailed  = {false};

		Runnable proceed = () -> {
			if (!handleOk[0]) {
				_loadingBar(false);
				_showFieldError(txt_username_error, "@" + newHandle + " is already taken");
				et_username.requestFocus();
				return;
			}
			if (handleCheckFailed[0]) {
				_loadingBar(false);
				_showFieldError(txt_username_error, "Couldn't check that handle — try again");
				return;
			}
			if (!phoneOk[0]) {
				_loadingBar(false);
				_showFieldError(txt_phone_error, "This number is already linked to another account");
				et_phone.requestFocus();
				return;
			}
			if (phoneCheckFailed[0]) {
				_loadingBar(false);
				_showFieldError(txt_phone_error, "Couldn't verify this number — try again");
				return;
			}
			_writeAll(fullname, newHandle, bio, newCc, newNumber, e164, handleChanged, phoneChanged);
		};

		if (handleChanged) {
			handlesRef.child(newHandle).addListenerForSingleValueEvent(new ValueEventListener() {
				@Override public void onDataChange(@NonNull DataSnapshot snap) {
					handleOk[0] = !snap.exists();
					if (--pending[0] == 0) proceed.run();
				}
				@Override public void onCancelled(@NonNull DatabaseError error) {
					// BUG FIXED (same class as CompleteProfileActivity's):
					// a denied/failed read must NOT be reported as "already
					// taken" — that's a different, false claim.
					handleCheckFailed[0] = true;
					if (--pending[0] == 0) proceed.run();
				}
			});
		}
		if (phoneChanged) {
			FirebaseDatabase.getInstance().getReference("phone_index").child(_phoneKey(e164))
				.addListenerForSingleValueEvent(new ValueEventListener() {
					@Override public void onDataChange(@NonNull DataSnapshot snap) {
						String existingOwner = snap.getValue(String.class);
						phoneOk[0] = !snap.exists() || myUid.equals(existingOwner);
						if (--pending[0] == 0) proceed.run();
					}
					@Override public void onCancelled(@NonNull DatabaseError error) {
						phoneCheckFailed[0] = true;
						if (--pending[0] == 0) proceed.run();
					}
				});
		}
		if (pending[0] == 0) proceed.run();
	}

	/** Combines country code + local number into E.164. Same deliberately
	 *  simple approach as CompleteProfileActivity — no phone-number library
	 *  assumed available, just enough to produce a consistent unique key. */
	private String _toE164(String cc, String number) {
		String digits = number.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) return null;
		String code = cc.startsWith("+") ? cc : "+" + cc.replaceAll("[^0-9]", "");
		return code + digits;
	}

	private boolean _isValidE164(String e164) {
		return e164 != null && e164.matches("\\+[1-9]\\d{7,14}");
	}

	private String _phoneKey(String e164) {
		return e164 != null && e164.startsWith("+") ? e164.substring(1) : e164;
	}

	/** One write covering every combination — handle changed, phone
	 *  changed, both, or (via _writeProfileFields below) neither. Replaces
	 *  the old two-method split (_atomicHandleSwap / _writeProfileFields)
	 *  now that there are two independent things that can each change. */
	private void _writeAll(String fullname, String newHandle, String bio,
	                        String newCc, String newNumber, String e164,
	                        boolean handleChanged, boolean phoneChanged) {
		DatabaseReference db = FirebaseDatabase.getInstance().getReference();
		HashMap<String, Object> multiWrite = new HashMap<>();

		multiWrite.put("users/" + myUid + "/fullname",       fullname);
		multiWrite.put("users/" + myUid + "/fullname_lower", fullname.toLowerCase(Locale.ENGLISH));
		multiWrite.put("users/" + myUid + "/bio",             bio);
		multiWrite.put(PrivateProfile.prefix(myUid) + "email",
				auth.getCurrentUser() != null && auth.getCurrentUser().getEmail() != null
						? auth.getCurrentUser().getEmail() : "");
		if (!uploadedPhotoUrl.isEmpty()) {
			multiWrite.put("users/" + myUid + "/photo_url", uploadedPhotoUrl);
		}

		if (handleChanged) {
			HashMap<String, Object> newHandleData = new HashMap<>();
			newHandleData.put("type", "user");
			newHandleData.put("id",   myUid);
			if (!oldHandle.isEmpty()) multiWrite.put("handles/" + oldHandle, null); // release old
			multiWrite.put("handles/" + newHandle,        newHandleData);           // claim new
			multiWrite.put("users/" + myUid + "/handle",  newHandle);
		}
		if (phoneChanged) {
			String oldE164 = _toE164(oldPhoneCc, oldPhoneNumber);
			if (oldE164 != null && _isValidE164(oldE164)) {
				multiWrite.put("phone_index/" + _phoneKey(oldE164), null); // release old
			}
			multiWrite.put("phone_index/" + _phoneKey(e164), myUid);       // claim new
			multiWrite.put(PrivateProfile.prefix(myUid) + "phone",        e164);
			multiWrite.put(PrivateProfile.prefix(myUid) + "phone_cc",     newCc);
			multiWrite.put(PrivateProfile.prefix(myUid) + "phone_number", newNumber);
		}

		db.updateChildren(multiWrite).addOnCompleteListener(task -> {
			if (isFinishing() || isDestroyed()) return;
			_loadingBar(false);
			if (task.isSuccessful()) {
				oldHandle = newHandle;
				oldPhoneCc = newCc;
				oldPhoneNumber = newNumber;
				Toast.makeText(this, "Profile saved!", Toast.LENGTH_SHORT).show();
				finish();
			} else {
				Toast.makeText(this, "Save failed: " + task.getException().getMessage(),
						Toast.LENGTH_LONG).show();
			}
		});
	}

	/** Nothing changed except maybe fullname/bio/photo — no uniqueness
	 *  registries to touch. */
	private void _writeProfileFields(String fullname, String handle, String bio,
	                                  String cc, String number, String e164) {
		HashMap<String, Object> updates = new HashMap<>();
		updates.put("fullname",       fullname);
		updates.put("fullname_lower", fullname.toLowerCase(Locale.ENGLISH));
		updates.put("handle",         handle);
		updates.put("bio",            bio);
		// e-mail is private: stored under user_private/{uid}, not the public users/{uid} node
		PrivateProfile.ref(myUid).child("email").setValue(auth.getCurrentUser() != null
				&& auth.getCurrentUser().getEmail() != null ? auth.getCurrentUser().getEmail() : "");
		if (!uploadedPhotoUrl.isEmpty()) {
			updates.put("photo_url", uploadedPhotoUrl);
		}

		usersRef.child(myUid).updateChildren(updates).addOnCompleteListener(task -> {
			if (isFinishing() || isDestroyed()) return;
			_loadingBar(false);
			if (task.isSuccessful()) {
				Toast.makeText(this, "Profile saved!", Toast.LENGTH_SHORT).show();
				finish();
			} else {
				Toast.makeText(this, "Save failed: " + task.getException().getMessage(),
						Toast.LENGTH_LONG).show();
			}
		});
	}

	// ─── Handle validation ────────────────────────────────────────────────────

	/** Regex: 4–20 chars, lowercase letters / digits / underscore only. */
	private boolean _isValidHandle(String handle) {
		if (handle == null || handle.isEmpty()) return false;
		return handle.matches("[a-z0-9_]{4,20}");
	}

	// ─── Image picker & upload ────────────────────────────────────────────────

	private final ActivityResultLauncher<String> pickImage =
		registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
			if (uri != null) {
				imageUri = uri;
				_hideKeyboard();
				_uploadImage();
			}
		});

	public void _uploadImage() {
		if (imageUri == null) {
			SketchwareUtil.showMessage(getApplicationContext(), "Select image first");
			return;
		}
		if (isUploading) return;
		isUploading = true;

		_loadingBar(true);
		textview2.setText("Updating Profile Picture...");
		UploadHelper.uploadImage(this, imageUri, new UploadHelper.UploadCallback() {
			@Override
			public void onSuccess(String url) {
				runOnUiThread(() -> {
					if (isFinishing() || isDestroyed()) return;
					isUploading      = false;
					uploadedPhotoUrl = url;

					Glide.with(img_profile)
						.load(url)
						.circleCrop()
						.placeholder(R.drawable.default_profile)
						.error(R.drawable.default_profile)
						.into(img_profile);

					_loadingBar(false);
					_saveProfile();
					Toast.makeText(EditProfileActivity.this, "Photo uploaded!", Toast.LENGTH_SHORT).show();
				});
			}

			@Override
			public void onError(String message) {
				runOnUiThread(() -> {
					_loadingBar(false);
					isUploading = false;
					Toast.makeText(EditProfileActivity.this, message, Toast.LENGTH_LONG).show();
				});
			}
		});
	}

	// ─── UI helpers ──────────────────────────────────────────────────────────

	public void _loadingBar(final boolean _isVisible) {
		btn_save.setEnabled(!_isVisible);
		if (_isVisible) {
			loadingOverlay.setAlpha(0f);
			loadingOverlay.setVisibility(View.VISIBLE);
			loadingOverlay.animate().alpha(1f).setDuration(200);
		} else {
			loadingOverlay.animate().alpha(0f).setDuration(200)
				.withEndAction(() -> loadingOverlay.setVisibility(View.GONE));
		}
	}

	private void _hideKeyboard() {
		InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
		View view = getCurrentFocus();
		if (view != null) {
			imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
		} else {
			imm.hideSoftInputFromWindow(linear180.getWindowToken(), 0);
		}
		linear180.requestFocus();
	}
}
