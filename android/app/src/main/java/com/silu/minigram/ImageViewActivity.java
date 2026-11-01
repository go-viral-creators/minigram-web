package com.silu.minigram;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
* ImageViewActivity
* ─────────────────────────────────────────────────────────────────────────────
* Full-screen image viewer.  Uses custom ZoomableImageView — no PhotoView.
*
* Receives:
*   Intent extra "image_url" (String)  — HTTP/S URL loaded by Glide
* ─────────────────────────────────────────────────────────────────────────────
*/
public class ImageViewActivity extends AppCompatActivity {
	
	private FrameLayout       rootContainer;
	private ZoomableImageView imgView;
	private View              topBar, bottomBar;
	private TextView          btnClose, btnSave, btnShare;
	private ProgressBar       progressBar;
	private String            imageUrl;
	private boolean           uiVisible = true;
	
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	// Quick screen, so a lighter cap than chat/channel exits.
	private final InterstitialAdManager exitInterstitialAd =
		InterstitialAdManager.forImageViewExit();
	
	// ─────────────────────────────────────────────────────────────────────────
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
        /*
		// True immersive fullscreen
		getWindow().setFlags(
		WindowManager.LayoutParams.FLAG_FULLSCREEN,
		WindowManager.LayoutParams.FLAG_FULLSCREEN);
		getWindow().getDecorView().setSystemUiVisibility(
		View.SYSTEM_UI_FLAG_FULLSCREEN
		| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
		| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
		| View.SYSTEM_UI_FLAG_LAYOUT_STABLE
		| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
		| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        */
		EdgeToEdgeHelper.setup(this);
		
		setContentView(R.layout.image_view);
		EdgeToEdgeHelper.applyInsets(findViewById(R.id.root_container));
		rootContainer = findViewById(R.id.root_container);
		imgView       = findViewById(R.id.image_full);
		topBar        = findViewById(R.id.top_bar);
		bottomBar     = findViewById(R.id.bottom_bar);
		btnClose      = findViewById(R.id.btn_close);
		btnSave       = findViewById(R.id.btn_save);
		btnShare      = findViewById(R.id.btn_share);
		progressBar   = findViewById(R.id.progress_bar);
		
		imageUrl = getIntent().getStringExtra("image_url");
		com.google.android.gms.ads.MobileAds.initialize(this, initStatus -> {});
		exitInterstitialAd.preload(this);

		// See ChatActivity for full explanation: legacy onBackPressed() does
		// NOT reliably fire on Android 13+ devices with predictive back
		// gesture (common on OPPO/Realme/OnePlus ColorOS) — register the
		// modern callback too so gesture-based back is caught as well.
		getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
			@Override public void handleOnBackPressed() {
				animateClose();
			}
		});
		
		// Load image
		if (imageUrl != null && !imageUrl.isEmpty()) {
			Glide.with(this)
			.load(imageUrl)
			.placeholder(R.drawable.img_placeholder)
			.error(R.drawable.img_placeholder)
			.into(imgView);
		}
		
		// ── Single tap → toggle top/bottom bars ──────────────────────────────
		imgView.setOnSingleTapListener(this::toggleUiVisibility);
		
		// ── Swipe dismiss callbacks ───────────────────────────────────────────
		imgView.setOnSwipeDismissListener(new ZoomableImageView.OnSwipeDismissListener() {
			
			@Override
			public void onSwipeProgress(float alpha) {
				// Dim the root container as user drags down
				rootContainer.setAlpha(alpha);
			}
			
			@Override
			public void onSwipeDismiss(float direction) {
				performDismissAnim(direction);
			}
			
			@Override
			public void onSwipeSnapBack() {
				// Restore alpha smoothly
				ValueAnimator a = ValueAnimator.ofFloat(rootContainer.getAlpha(), 1f);
				a.setDuration(300);
				a.addUpdateListener(an -> rootContainer.setAlpha((float) an.getAnimatedValue()));
				a.start();
			}
		});
		
		btnClose.setOnClickListener(v -> animateClose());
		btnSave.setOnClickListener(v -> saveImage());
		btnShare.setOnClickListener(v -> shareImage());
	}
	
	// ─────────────────────────────────────────────────────────────────────────
	// UI bar toggle (single tap)
	// ─────────────────────────────────────────────────────────────────────────
	
	private void toggleUiVisibility() {
		if (uiVisible) {
			topBar.animate().alpha(0f)
			.translationY(-topBar.getHeight())
			.setDuration(200).start();
			bottomBar.animate().alpha(0f)
			.translationY(bottomBar.getHeight())
			.setDuration(200).start();
		} else {
			topBar.animate().alpha(1f).translationY(0f).setDuration(200).start();
			bottomBar.animate().alpha(1f).translationY(0f).setDuration(200).start();
		}
		uiVisible = !uiVisible;
	}
	
	// ─────────────────────────────────────────────────────────────────────────
	// Dismiss animations
	// ─────────────────────────────────────────────────────────────────────────
	
	/** Swipe-dismiss: slide image off screen + fade root → finish. */
	private void performDismissAnim(float direction) {
		float targetY = direction * getWindow().getDecorView().getHeight();
		
		imgView.animate()
		.translationY(targetY)
		.setDuration(220)
		.setInterpolator(new DecelerateInterpolator())
		.start();
		
		ValueAnimator alpha = ValueAnimator.ofFloat(rootContainer.getAlpha(), 0f);
		alpha.setDuration(200);
		alpha.addUpdateListener(a -> rootContainer.setAlpha((float) a.getAnimatedValue()));
		alpha.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				finish();
				overridePendingTransition(0, 0);
			}
		});
		alpha.start();
	}
	
	/** Close button: fade entire container → finish. */
	private void animateClose() {
		exitInterstitialAd.maybeShow(this, () -> {
			rootContainer.animate()
			.alpha(0f)
			.setDuration(180)
			.setInterpolator(new DecelerateInterpolator())
			.withEndAction(() -> {
				finish();
				overridePendingTransition(0, 0);
			})
			.start();
		});
	}

	@Override
	public void onBackPressed() {
		animateClose();
	}
	
	// ─────────────────────────────────────────────────────────────────────────
	// Save to Gallery  (MediaStore — works Android 9 and 10+)
	// ─────────────────────────────────────────────────────────────────────────
	
	private void saveImage() {
		if (imageUrl == null || imageUrl.isEmpty()) return;
		showProgress(true);
		btnSave.setAlpha(0.5f);
		btnSave.setEnabled(false);
		
		executor.execute(() -> {
			try {
				String fileName = "Minigram_" + System.currentTimeMillis() + ".jpg";
				
				ContentValues values = new ContentValues();
				values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
				values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					values.put(MediaStore.MediaColumns.RELATIVE_PATH,
					Environment.DIRECTORY_PICTURES + "/Minigram");
					values.put(MediaStore.MediaColumns.IS_PENDING, 1);
				}
				
				Uri uri = getContentResolver().insert(
				MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
				if (uri == null) throw new Exception("MediaStore insert returned null");
				
				// Use Glide's disk cache — avoids re-downloading
				File cached = Glide.with(getApplicationContext())
				.downloadOnly().load(imageUrl).submit().get();
				
				try (InputStream  in  = new FileInputStream(cached);
				OutputStream out = getContentResolver().openOutputStream(uri)) {
					if (out == null) throw new Exception("Cannot open output stream");
					byte[] buf = new byte[8192];
					int len;
					while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
				}
				
				// Clear IS_PENDING flag so gallery can see the file
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					ContentValues done = new ContentValues();
					done.put(MediaStore.MediaColumns.IS_PENDING, 0);
					getContentResolver().update(uri, done, null, null);
				}
				
				runOnUiThread(() -> {
					showProgress(false);
					btnSave.setAlpha(1f);
					btnSave.setEnabled(true);
					Toast.makeText(this, "✅ Saved to Gallery", Toast.LENGTH_SHORT).show();
				});
				
			} catch (Exception e) {
				e.printStackTrace();
				runOnUiThread(() -> {
					showProgress(false);
					btnSave.setAlpha(1f);
					btnSave.setEnabled(true);
					Toast.makeText(this, "❌ Save failed: " + e.getMessage(),
					Toast.LENGTH_SHORT).show();
				});
			}
		});
	}
	
	// ─────────────────────────────────────────────────────────────────────────
	// Share Image
	// ─────────────────────────────────────────────────────────────────────────
	
	private void shareImage() {
		if (imageUrl == null || imageUrl.isEmpty()) return;
		showProgress(true);
		btnShare.setAlpha(0.5f);
		btnShare.setEnabled(false);
		
		executor.execute(() -> {
			try {
				String fileName = "Minigram_share_" + System.currentTimeMillis() + ".jpg";
				
				ContentValues values = new ContentValues();
				values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
				values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					values.put(MediaStore.MediaColumns.RELATIVE_PATH,
					Environment.DIRECTORY_PICTURES + "/Minigram");
					values.put(MediaStore.MediaColumns.IS_PENDING, 1);
				}
				
				Uri uri = getContentResolver().insert(
				MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
				if (uri == null) throw new Exception("MediaStore insert returned null");
				
				File cached = Glide.with(getApplicationContext())
				.downloadOnly().load(imageUrl).submit().get();
				
				try (InputStream  in  = new FileInputStream(cached);
				OutputStream out = getContentResolver().openOutputStream(uri)) {
					if (out == null) throw new Exception("Cannot open output stream");
					byte[] buf = new byte[8192];
					int len;
					while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
				}
				
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					ContentValues done = new ContentValues();
					done.put(MediaStore.MediaColumns.IS_PENDING, 0);
					getContentResolver().update(uri, done, null, null);
				}
				
				final Uri shareUri = uri;
				runOnUiThread(() -> {
					showProgress(false);
					btnShare.setAlpha(1f);
					btnShare.setEnabled(true);
					
					Intent intent = new Intent(Intent.ACTION_SEND);
					intent.setType("image/jpeg");
					intent.putExtra(Intent.EXTRA_STREAM, shareUri);
					intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
					startActivity(Intent.createChooser(intent, "Share Image via"));
				});
				
			} catch (Exception e) {
				e.printStackTrace();
				runOnUiThread(() -> {
					showProgress(false);
					btnShare.setAlpha(1f);
					btnShare.setEnabled(true);
					Toast.makeText(this, "❌ Share failed", Toast.LENGTH_SHORT).show();
				});
			}
		});
	}
	
	// ─────────────────────────────────────────────────────────────────────────
	// Util
	// ─────────────────────────────────────────────────────────────────────────
	
	private void showProgress(boolean show) {
		runOnUiThread(() ->
		progressBar.setVisibility(show ? View.VISIBLE : View.GONE));
	}
	
	@Override
	public void finish() {
		super.finish();
		overridePendingTransition(0, android.R.anim.fade_out);
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		executor.shutdownNow();
	}
}
