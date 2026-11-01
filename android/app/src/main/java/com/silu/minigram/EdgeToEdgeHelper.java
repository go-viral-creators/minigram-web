package com.silu.minigram;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public class EdgeToEdgeHelper {
	
	public static void setup(Activity a) {
		try {
			
			Window w = a.getWindow();
			
			WindowCompat.setDecorFitsSystemWindows(w, false);
			
			w.setStatusBarColor(Color.TRANSPARENT);
			w.setNavigationBarColor(Color.TRANSPARENT);
			
			if (Build.VERSION.SDK_INT >= 29) {
				w.setNavigationBarContrastEnforced(false);
			}
			
		} catch (Exception ignored) {
		}
	}
	
	public static void applyInsets(View root) {
		if (root == null) return;
		ViewCompat.setOnApplyWindowInsetsListener(root,
		new androidx.core.view.OnApplyWindowInsetsListener() {
			@Override
			public WindowInsetsCompat onApplyWindowInsets(
			View v,
			WindowInsetsCompat wi
			) {
				Insets sysBar = wi.getInsets(
				WindowInsetsCompat.Type.systemBars()
				);
				Insets ime = wi.getInsets(
				WindowInsetsCompat.Type.ime()
				);
				// Keyboard open ho to ime.bottom > sysBar.bottom
				// Dono mein se jo bada ho wo bottom padding use karo
				int bottom = Math.max(sysBar.bottom, ime.bottom);
				v.setPadding(
				sysBar.left,
				sysBar.top,
				sysBar.right,
				bottom
				);
				return wi;
			}
		});
	}
}
