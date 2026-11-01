package com.silu.minigram;

import android.animation.*;
import android.app.*;
import android.content.*;
import androidx.annotation.NonNull;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import com.yalantis.ucrop.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.regex.*;
import jp.wasabeef.glide.transformations.*;
import org.json.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;


public class SettingsActivity extends AppCompatActivity {
	
	private LinearLayout linear20;
	private ScrollView vscroll4;
	private TextView btn_back;
	private TextView textview28;
	private LinearLayout linear21;
	private TextView textview29;
	private LinearLayout linear22;
	private TextView textview32;
	private LinearLayout linear24;
	private LinearLayout btn_logout;
	private TextView tvDisplayAppVersion;
	
	private LinearLayout btn_privacy;
	private LinearLayout btn_data_storage;
	private LinearLayout btn_minigram_pro;
	// Held so the live profile listener can be detached in onDestroy() —
	// without that it kept firing after the screen closed and crashed
	// Glide with a destroyed Activity.
	private com.google.firebase.database.DatabaseReference profileRef;
	private com.google.firebase.database.ValueEventListener profileListener;
	
	private TextView icon_privacy;
	private TextView textview31;
	private TextView chevron2;
	private LinearLayout btn_about;
	private View view34;
	private TextView icon_about;
	private TextView textview33;
	private TextView chevron3;
	private TextView textview40;
	private TextView textview35;
	
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
	private com.google.android.gms.ads.AdView bannerAd;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
        EdgeToEdgeHelper.setup(this);
		setContentView(R.layout.settings);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));
		initialize(_savedInstanceState);
		FirebaseApp.initializeApp(this);
		initializeLogic();
		bannerAd = BannerAdHelper.attachBottomBanner(this);
	}

	@Override protected void onResume() {
		super.onResume();
		if (bannerAd != null) bannerAd.resume();
	}

	@Override protected void onPause() {
		super.onPause();
		if (bannerAd != null) bannerAd.pause();
	}

	@Override protected void onDestroy() {
		super.onDestroy();
		if (bannerAd != null) bannerAd.destroy();
		if (profileRef != null && profileListener != null) {
			profileRef.removeEventListener(profileListener);
		}
	}
	
	private void initialize(Bundle _savedInstanceState) {
		linear20 = findViewById(R.id.linear20);
		vscroll4 = findViewById(R.id.vscroll4);
		btn_back = findViewById(R.id.btn_back);
		textview28 = findViewById(R.id.textview28);
		linear21 = findViewById(R.id.linear21);
		textview29 = findViewById(R.id.textview29);
		linear22 = findViewById(R.id.linear22);
		textview32 = findViewById(R.id.textview32);
		linear24 = findViewById(R.id.linear24);
		btn_logout = findViewById(R.id.btn_logout);
		tvDisplayAppVersion = findViewById(R.id.tvDisplayAppVersion);
		
		btn_privacy = findViewById(R.id.btn_privacy);
		btn_data_storage = findViewById(R.id.btn_data_storage);
		btn_minigram_pro = findViewById(R.id.btn_minigram_pro);
		
		icon_privacy = findViewById(R.id.icon_privacy);
		textview31 = findViewById(R.id.textview31);
		chevron2 = findViewById(R.id.chevron2);
		btn_about = findViewById(R.id.btn_about);
		view34 = findViewById(R.id.view34);
		icon_about = findViewById(R.id.icon_about);
		textview33 = findViewById(R.id.textview33);
		chevron3 = findViewById(R.id.chevron3);
		textview40 = findViewById(R.id.textview40);
		textview35 = findViewById(R.id.textview35);
		auth = FirebaseAuth.getInstance();

		LinearLayout row_my_profile = findViewById(R.id.row_my_profile);
		ImageView img_settings_avatar = findViewById(R.id.img_settings_avatar);
		ImageView img_settings_badge = findViewById(R.id.img_settings_badge);
		TextView txt_settings_name = findViewById(R.id.txt_settings_name);

		if (row_my_profile != null) {
			row_my_profile.setOnClickListener(v ->
				startActivity(new Intent(SettingsActivity.this, MyProfileActivity.class)));
		}
		if (auth.getUid() != null && img_settings_avatar != null && txt_settings_name != null) {
			profileRef = com.google.firebase.database.FirebaseDatabase.getInstance()
				.getReference("users").child(auth.getUid());
			profileListener = new com.google.firebase.database.ValueEventListener() {
					@Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snap) {
						// CRASH FIX: this is a LIVE listener (needed so the Pro
						// badge appears the moment Pro is granted). Without this
						// guard a callback arriving after the screen closed
						// reached AvatarHelper.load → Glide.with(this), and
						// Glide throws outright when handed a destroyed
						// Activity. The detach in onDestroy() below is the real
						// cure; this guard covers a callback already in flight
						// when destroy happens.
						if (isFinishing() || isDestroyed()) return;

						String fullname = snap.child("fullname").getValue(String.class);
						String photo = snap.child("photo_url").getValue(String.class);
						String shown = (fullname != null && !fullname.isEmpty()) ? fullname : "You";
						txt_settings_name.setText(shown);
						AvatarHelper.load(SettingsActivity.this, img_settings_avatar, photo, shown);

						// Pro/verified badge — overlaid on the avatar (top-left,
						// half on the image and half outside it). Live listener,
						// not a one-shot read, so it appears the instant Pro is
						// granted: the "Minigram Pro" card right below already
						// hides live, and a badge that only showed up after
						// leaving and reopening Settings would look broken
						// sitting next to it.
						boolean isPro = Boolean.TRUE.equals(snap.child("is_pro").getValue(Boolean.class));
						if (img_settings_badge != null) {
							img_settings_badge.setVisibility(isPro ? View.VISIBLE : View.GONE);
						}
						// Same signal drives the promo card: it used to show
						// "Upgrade to Pro" forever, even to someone who'd
						// already bought it, because nothing checked is_pro.
						if (btn_minigram_pro != null) {
							btn_minigram_pro.setVisibility(isPro ? View.GONE : View.VISIBLE);
						}
					}
					@Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {}
				};
			profileRef.addValueEventListener(profileListener);
		}
		
		btn_back.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				finish();
			}
		});
		
		btn_logout.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				// FIX: token pehle hatao — signOut() ke baad currentUser null ho
				// jaata hai aur uid pata hi nahi chalta. Bina iske logged-out user
				// ke phone par purane account ke messages aate rehte hain.
				FcmTokenManager.clearTokenOnLogout(SettingsActivity.this);
				FirebaseAuth.getInstance().signOut();
				Intent intent = new Intent();
				intent.setClass(getApplicationContext(), LoginActivity.class);
				startActivity(intent);
				finish();
			}
		});
		
		chevron2.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				startActivity(new Intent(getApplicationContext(), PrivacySecurityActivity.class));
				
			}
		});
		
		if (btn_data_storage != null) {
			btn_data_storage.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View _view) {
					DataStorageSheet sheet = new DataStorageSheet();
					sheet.show(getSupportFragmentManager(), "data_storage");
				}
			});
		}
		
		if (btn_minigram_pro != null) {
			btn_minigram_pro.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View _view) {
					startActivity(new Intent(SettingsActivity.this, SubscriptionActivity.class));
				}
			});
			// The Pro card's visibility is driven by the single profile
			// listener above — it already reads this same user node
			// (is_pro included), so a second live listener here would just
			// be a duplicate query and a second thing to detach.
		}
		
		chevron3.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
			}
		});
		
		auth_updateEmailListener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		auth_updatePasswordListener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		auth_emailVerificationSentListener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		auth_deleteUserListener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		auth_phoneAuthListener = new OnCompleteListener<AuthResult>() {
			@Override
			public void onComplete(Task<AuthResult> task) {
				final boolean _success = task.isSuccessful();
				final String _errorMessage = task.getException() != null ? task.getException().getMessage() : "";
				
			}
		};
		
		auth_updateProfileListener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		auth_googleSignInListener = new OnCompleteListener<AuthResult>() {
			@Override
			public void onComplete(Task<AuthResult> task) {
				final boolean _success = task.isSuccessful();
				final String _errorMessage = task.getException() != null ? task.getException().getMessage() : "";
				
			}
		};
		
		_auth_create_user_listener = new OnCompleteListener<AuthResult>() {
			@Override
			public void onComplete(Task<AuthResult> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		_auth_sign_in_listener = new OnCompleteListener<AuthResult>() {
			@Override
			public void onComplete(Task<AuthResult> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		_auth_reset_password_listener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				
			}
		};
	}
	
	private void initializeLogic() {
		tvDisplayAppVersion.setText("Minigram v".concat(_getAppVersion()));
	}
	
	public String _getAppVersion() {
		try {
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
				return getPackageManager()
				.getPackageInfo(getPackageName(),
				android.content.pm.PackageManager.PackageInfoFlags.of(0))
				.versionName;
			} else {
				return getPackageManager()
				.getPackageInfo(getPackageName(), 0)
				.versionName;
			}
		} catch (Exception e) {
			e.printStackTrace();
			return "0.0";
		}
		
	}
	
}
