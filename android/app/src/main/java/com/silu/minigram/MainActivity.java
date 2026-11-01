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
import android.view.View.*;
import android.view.animation.*;
import android.webkit.*;
import android.widget.*;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.bumptech.glide.Glide;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.*;
import java.text.*;
import java.util.*;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.*;
import org.json.*;
import android.content.Intent;
import java.util.Timer;
import java.util.TimerTask;
import com.google.firebase.auth.FirebaseAuth;


public class MainActivity extends AppCompatActivity {
	
	private Timer _timer = new Timer();
	
	private ImageView logo;
	private TextView textview3;
	private ProgressBar progressbar3;
	
	private FirebaseAuth auth;
	private OnCompleteListener<AuthResult> _auth_create_user_listener;
	private OnCompleteListener<AuthResult> _auth_sign_in_listener;
	private OnCompleteListener<Void> _auth_reset_password_listener;
	private OnCompleteListener<Void> auth_updateEmailListener;
	private OnCompleteListener<Void> auth_updatePasswordListener;
	private OnCompleteListener<Void> auth_emailVerificationSentListener;
	private OnCompleteListener<Void> auth_deleteUserListener;
	private OnCompleteListener<Void> auth_updateProfileListener;
	private OnCompleteListener<AuthResult> auth_phoneAuthListener;
	private OnCompleteListener<AuthResult> auth_googleSignInListener;
	private TimerTask timer;

	private final androidx.activity.result.ActivityResultLauncher<Intent> pinUnlockLauncher =
		registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
			if (result.getResultCode() == RESULT_OK) {
				Intent intent = new Intent();
				intent.setClass(getApplicationContext(), ChatlistActivity.class);
				startActivity(intent);
				finish();
			}
			// A non-OK result only happens if PinLockActivity finished
			// without unlocking — its own back-press handling sends the
			// whole app to background instead in MODE_VERIFY, so this
			// branch is effectively unreachable in normal use. Deliberately
			// not routing to ChatlistActivity here either way: the safe
			// default on anything other than a confirmed correct PIN is to
			// NOT enter the unlocked app.
		});
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		setContentView(R.layout.main);

		// FIX: pehle initialize() (jo FirebaseAuth.getInstance() call karta
		// hai) FirebaseApp.initializeApp(this) se PEHLE chalta tha. Ye ab tak
		// isliye chalta raha kyunki FirebaseInitProvider (manifest) Application
		// se pehle hi default app init kar deta tha. Ab wo provider hata diya
		// gaya hai (SketchApplication khud, defensively, initialize karta hai),
		// isliye order ab yahan bhi sahi hona chahiye - chahe SketchApplication
		// pehle hi kar chuka ho, dobara call karna harmless/idempotent hai.
		try {
			FirebaseApp.initializeApp(this);
		} catch (Throwable t) {
			android.util.Log.e("MinigramFCM", "MainActivity: FirebaseApp init failed: " + t);
		}

		if (!SketchApplication.isFirebaseReady()) {
			// Application-level init bhi fail ho chuka hai - Firebase abhi is
			// process mein kaam nahi karega. Login/chat/notifications sab isi
			// par depend karte hain, isliye yahan aage badhna sirf ek alag jagah
			// crash dega. Saaf error dikhao aur ruk jao - adb logcat -s
			// MinigramFCM mein "Firebase init FAILED" ke baad wali "Caused by:"
			// line hi asli wajah batayegi.
			android.widget.Toast.makeText(this,
				"Startup error - Firebase failed to initialize. Check logcat (tag: MinigramFCM).",
				android.widget.Toast.LENGTH_LONG).show();
			return;
		}

		initialize(_savedInstanceState);
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
		logo = findViewById(R.id.logo);
		textview3 = findViewById(R.id.textview3);
		progressbar3 = findViewById(R.id.progressbar3);
		auth = FirebaseAuth.getInstance();
	}
	
	private void initializeLogic() {
		timer = new TimerTask() {
			@Override
			public void run() {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						Intent intent = new Intent();
						if ((FirebaseAuth.getInstance().getCurrentUser() != null)) {
							boolean appLockOn = getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
									.getBoolean(PrivacySecurityActivity.KEY_APP_LOCK, false)
								&& PinLockActivity.hasPinSet(getApplicationContext());
							if (appLockOn) {
								// PIN screen decides where to go next on success —
								// see the ActivityResultLauncher below — rather
								// than routing to ChatlistActivity here and
								// letting an unlocked app sit behind it.
								Intent pinIntent = new Intent(getApplicationContext(), PinLockActivity.class);
								pinIntent.putExtra(PinLockActivity.EXTRA_MODE, PinLockActivity.MODE_VERIFY);
								pinUnlockLauncher.launch(pinIntent);
								return;
							}
							intent.setClass(getApplicationContext(), ChatlistActivity.class);
							String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
							PrivateProfile.readOwn(uid, new PrivateProfile.Callback() {
									@Override public void onResult(String phone, String cc, String num) {
										if (isFinishing() || isDestroyed()) return;
										if (phone == null || phone.isEmpty()) {
											// Requirement 5 — phone number became
											// mandatory after this account was
											// created. CompleteProfileActivity
											// detects an already-complete profile
											// on its own and only asks for the
											// missing phone in that case.
											Intent gate = new Intent(getApplicationContext(), CompleteProfileActivity.class);
											gate.putExtra("uid", uid);
											startActivity(gate);
										} else {
											startActivity(intent);
										}
										finish();
									}
								});
						} else {
							intent.setClass(getApplicationContext(), LoginActivity.class);
							startActivity(intent);
							finish();
						}
					}
				});
			}
		};
		_timer.schedule(timer, (int)(2500));
	}
	
}
