package com.silu.minigram;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Minimal in-app browser so links never leave Minigram. Private by design: no saved passwords/forms, third-party
 * cookies blocked, no file/content access, and cookies + cache + storage are wiped when the screen closes.
 */
public class BrowserActivity extends AppCompatActivity {
    static final String EXTRA_URL = "url";
    private WebView web;
    private ProgressBar progress;
    private TextView titleTv, hostTv, lockTv;

    /** Opens http(s) links in the in-app browser; anything else (mailto:, tel:, ...) goes to the system. */
    public static void open(Context c, String url) {
        if (url == null || url.trim().isEmpty()) return;
        String u = url.trim();
        if (!u.contains("://") && !u.startsWith("mailto:") && !u.startsWith("tel:")) u = "https://" + u;
        Uri uri = Uri.parse(u);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        Intent i;
        if (scheme.equals("http") || scheme.equals("https")) {
            i = new Intent(c, BrowserActivity.class).putExtra(EXTRA_URL, u);
        } else {
            i = new Intent(Intent.ACTION_VIEW, uri);
        }
        if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (Exception ignored) {}
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_browser);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));
        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        titleTv = findViewById(R.id.txt_title);
        hostTv = findViewById(R.id.txt_host);
        lockTv = findViewById(R.id.txt_lock);
        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_more).setOnClickListener(v -> InkMenu.show(this, v,
            new String[]{"refresh", "content_copy", "share", "open_in_new"},
            new String[]{"Reload", "Copy link", "Share", "Open in another app"},
            i -> {
                String cur = web.getUrl() != null ? web.getUrl() : getIntent().getStringExtra(EXTRA_URL);
                if (i == 0) web.reload();
                else if (i == 1) { SensitiveClip.copy(this, "link", cur); android.widget.Toast.makeText(this, "Link copied", android.widget.Toast.LENGTH_SHORT).show(); }
                else if (i == 2) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, cur), "Share link"));
                else try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(cur))); } catch (Exception ignored) {}
            }));

        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setAllowFileAccess(false);
        st.setAllowContentAccess(false);
        st.setSavePassword(false);
        st.setSaveFormData(false);
        st.setGeolocationEnabled(false);
        st.setSupportMultipleWindows(false);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        st.setLoadWithOverviewMode(true);
        st.setUseWideViewPort(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                if ("http".equals(sc) || "https".equals(sc)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) {}
                return true;
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap f) { progress.setVisibility(View.VISIBLE); show(url); }
            @Override public void onPageFinished(WebView v, String url) { progress.setVisibility(View.GONE); show(url); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { progress.setProgress(p); }
            @Override public void onReceivedTitle(WebView v, String t) { if (t != null && !t.isEmpty()) titleTv.setText(t); }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { if (web.canGoBack()) web.goBack(); else finish(); }
        });
        String url = getIntent().getStringExtra(EXTRA_URL);
        show(url);
        if (url != null) web.loadUrl(url);
    }

    private void show(String url) {
        if (url == null) return;
        Uri u = Uri.parse(url);
        String host = u.getHost() == null ? url : u.getHost();
        hostTv.setText(host.startsWith("www.") ? host.substring(4) : host);
        boolean https = "https".equals(u.getScheme());
        lockTv.setText(https ? "lock" : "lock_open");
        lockTv.setTextColor(Ink.res(https ? R.color.ink_ok : R.color.ink_danger));
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.clearHistory();
            web.clearCache(true);
            ((android.view.ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        super.onDestroy();
    }
}
