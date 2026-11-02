package com.silu.minigram;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** Legacy entry point: Discover/Settings now live as tabs inside ChatlistActivity. */
public class SettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        startActivity(new Intent(this, ChatlistActivity.class).putExtra(InkNav.EXTRA_TAB, 4)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
