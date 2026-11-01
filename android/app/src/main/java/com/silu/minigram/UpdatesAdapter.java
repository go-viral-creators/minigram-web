package com.silu.minigram;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.GranularRoundedCorners;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Read-only feed adapter for the Updates tab — deliberately simple compared
 * to the chat/channel adapters (no reactions, no upload states, no
 * selection): this is just a list of official announcements to read.
 */
public class UpdatesAdapter extends RecyclerView.Adapter<UpdatesAdapter.VH> {

    private static final String TAG = "UpdatesLink";

    private final Context context;
    private final ArrayList<HashMap<String, Object>> items;
    // Top corners rounded to match the card's own rounded background
    // (plan_card_bg); bottom corners stay square since the image sits
    // flush against the text block below it.
    private final float imageCornerRadiusPx;

    public UpdatesAdapter(Context context, ArrayList<HashMap<String, Object>> items) {
        this.context = context;
        this.items = items;
        this.imageCornerRadiusPx = 14f * context.getResources().getDisplayMetrics().density;
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView imgUpdate;
        TextView  txtTime, txtTitle, txtBody, txtLink;
        VH(View v) {
            super(v);
            imgUpdate = v.findViewById(R.id.img_update);
            txtTime   = v.findViewById(R.id.txt_update_time);
            txtTitle  = v.findViewById(R.id.txt_update_title);
            txtBody   = v.findViewById(R.id.txt_update_body);
            txtLink   = v.findViewById(R.id.txt_update_link);
        }
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_update, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        HashMap<String, Object> item = items.get(position);
        String title   = (String) item.get("title");
        String body    = (String) item.get("body");
        String imageUrl= (String) item.get("image_url");
        String linkUrl = (String) item.get("link_url");
        Long   ts      = item.get("timestamp") instanceof Long ? (Long) item.get("timestamp") : null;

        h.txtTitle.setText(title != null ? title : "");
        h.txtBody.setText(body != null ? body : "");

        if (ts != null) {
            h.txtTime.setText(DateUtils.getRelativeTimeSpanString(
                ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        } else {
            h.txtTime.setText("");
        }

        if (imageUrl != null && !imageUrl.isEmpty()) {
            h.imgUpdate.setVisibility(View.VISIBLE);
            Glide.with(context).load(imageUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.img_placeholder)
                .transform(new CenterCrop(),
                        new GranularRoundedCorners(imageCornerRadiusPx, imageCornerRadiusPx, 0f, 0f))
                .into(h.imgUpdate);
        } else {
            h.imgUpdate.setVisibility(View.GONE);
        }

        if (linkUrl != null && !linkUrl.trim().isEmpty()) {
            final String normalizedUrl = normalizeUrl(linkUrl);
            h.txtLink.setVisibility(View.VISIBLE);
            h.itemView.setClickable(true);
            h.itemView.setFocusable(true);
            View.OnClickListener openLink = v -> openLink(normalizedUrl);
            h.itemView.setOnClickListener(openLink);
            // Redundant listener directly on "Learn more →" too — belt and
            // suspenders in case anything upstream ever intercepts the
            // itemView's touch before it reaches this handler.
            h.txtLink.setOnClickListener(openLink);
        } else {
            h.txtLink.setVisibility(View.GONE);
            h.itemView.setOnClickListener(null);
            h.itemView.setClickable(false);
            h.itemView.setFocusable(false);
            h.txtLink.setOnClickListener(null);
        }
    }

    private void openLink(String url) {
        Log.d(TAG, "Tapped update link: " + url);
        try {
            // Play Store links open DIRECTLY in the Play Store app — package-
            // targeted on the ORIGINAL https:// URL (not converted to market://).
            // market:// routes through an older/slower internal redirect inside
            // Play Store that showed a cold-start "flash and return" on some
            // devices; https:// is the same entry point Play Store's own
            // verified App Link uses (what you get tapping a link in a
            // browser) — direct, no internal redirect.
            if (isPlayStoreUrl(url) && openPlayStoreApp(url)) return;

            openInBrowser(url);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open link: " + url, e);
            Toast.makeText(context, "Yeh link nahi khul saka", Toast.LENGTH_SHORT).show();
        }
    }

    /** True agar URL Play Store ka "app details" link hai. */
    private static boolean isPlayStoreUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return false;
            if (!"play.google.com".equalsIgnoreCase(host) && !"market.android.com".equalsIgnoreCase(host)) return false;
            String path = uri.getPath();
            return path != null && path.contains("/store/apps");
        } catch (Exception e) {
            return false;
        }
    }

    /** True return karta hai agar Play Store app se successfully khul gaya. */
    private boolean openPlayStoreApp(String url) {
        try {
            Intent playStoreIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            playStoreIntent.setPackage("com.android.vending"); // force asli Play Store app, kuch aur nahi
            if (playStoreIntent.resolveActivity(context.getPackageManager()) != null) {
                Log.d(TAG, "Opening via Play Store app (https, package-targeted): " + url);
                context.startActivity(playStoreIntent);
                Log.d(TAG, "startActivity() for Play Store returned without throwing");
                return true;
            }
            Log.d(TAG, "Play Store app (com.android.vending) not resolvable on this device");
        } catch (Exception e) {
            Log.e(TAG, "Play Store app open failed for url=" + url, e);
        }
        return false; // Play Store app installed nahi — caller web URL try karega
    }

    /**
     * Chrome ko specifically try karta hai; Chrome installed nahi to device
     * ka default browser (apni khud ki app ko explicitly exclude karke —
     * isi exclusion ki kami se pehle URL "kuch nahi hota" wala bug tha,
     * jab yeh intent kabhi-kabhi Minigram ke hi kisi Activity mein loop
     * back ho jaata tha).
     */
    private void openInBrowser(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));

        Intent chromeIntent = new Intent(intent);
        chromeIntent.setPackage("com.android.chrome");
        if (chromeIntent.resolveActivity(context.getPackageManager()) != null) {
            Log.d(TAG, "Opening via Chrome for: " + url);
            context.startActivity(chromeIntent);
            Log.d(TAG, "startActivity() for Chrome returned without throwing");
            return;
        }
        Log.d(TAG, "Chrome not resolvable — falling back to default browser for: " + url);

        List<ResolveInfo> resolveInfos = context.getPackageManager().queryIntentActivities(intent, 0);
        ResolveInfo target = null;
        for (ResolveInfo info : resolveInfos) {
            if (!context.getPackageName().equals(info.activityInfo.packageName)) {
                target = info;
                break;
            }
        }
        if (target == null) {
            Log.e(TAG, "No external browser found for: " + url);
            Toast.makeText(context, "Is link ko kholne wala koi app nahi mila", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d(TAG, "Opening via fallback: " + target.activityInfo.packageName + "/" + target.activityInfo.name);
        intent.setClassName(target.activityInfo.packageName, target.activityInfo.name);
        context.startActivity(intent);
        Log.d(TAG, "startActivity() for fallback returned without throwing");
    }

    @Override public int getItemCount() { return items.size(); }

    /**
     * Admin panel se aaya link_url scheme-less ho sakta hai (e.g. "example.com"
     * bina "https://" ke) — aisa URI Uri.parse + ACTION_VIEW silently ignore
     * kar deta hai (koi Activity resolve nahi hoti, exception bhi nahi aati
     * kai devices pe) — yehi "click karo to kuch nahi hota" wali dikkat thi.
     */
    private static String normalizeUrl(String raw) {
        String url = raw.trim();
        if (!url.matches("(?i)^[a-z][a-z0-9+.\\-]*://.*")) {
            url = "https://" + url;
        }
        return url;
    }
}
