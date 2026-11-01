package com.silu.minigram;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central Play Billing Library integration for Minigram's two subscription
 * products:
 *   • Minigram Pro (per-user)      → no ads + blue tick + all features
 *   • Channel Verification (per-channel) → blue tick on a specific channel
 *
 * Both are Play Billing SUBSCRIPTIONS. Product IDs below MUST exactly match
 * what you create in Play Console → Monetize → Subscriptions.
 *
 * ⚠️ SECURITY NOTE: purchase state here is trusted from the on-device
 * BillingClient and written to Firebase directly by the client. That's fine
 * to ship and monetize with, but it is NOT server-verified — a sufficiently
 * determined user with a modified APK could fake the Firebase flag. For real
 * production hardening, verify purchases server-side (Google Play Developer
 * API + your existing Vercel backend) and only let a trusted server process
 * flip is_pro / is_verified. Documented in the setup guide.
 */
public class BillingManager implements PurchasesUpdatedListener {

    private static final String TAG = "BillingManager";

    // ── Product IDs — must match Play Console exactly ──────────────────────
    public static final String PRODUCT_PRO_MONTHLY              = "minigram_pro_monthly";
    public static final String PRODUCT_PRO_YEARLY               = "minigram_pro_yearly";
    public static final String PRODUCT_CHANNEL_VERIFIED_MONTHLY = "channel_verified_monthly";
    public static final String PRODUCT_CHANNEL_VERIFIED_YEARLY  = "channel_verified_yearly";

    private static final List<String> PRO_PRODUCTS = java.util.Arrays.asList(
        PRODUCT_PRO_MONTHLY, PRODUCT_PRO_YEARLY);
    private static final List<String> CHANNEL_PRODUCTS = java.util.Arrays.asList(
        PRODUCT_CHANNEL_VERIFIED_MONTHLY, PRODUCT_CHANNEL_VERIFIED_YEARLY);

    public interface ProductDetailsListener {
        void onLoaded(Map<String, ProductDetails> details);
    }

    public interface PurchaseResultListener {
        void onPurchaseSuccess(String productId);
        void onPurchaseFailed(String message);
    }

    private static BillingManager instance;

    private final Context appContext;
    private BillingClient billingClient;
    private boolean isConnected = false;
    private final Map<String, ProductDetails> productDetailsCache = new HashMap<>();

    // Set right before launching a channel-verification purchase so the
    // PurchasesUpdatedListener callback knows which channel to flag.
    private String pendingChannelId;
    private PurchaseResultListener activeListener;

    public static synchronized BillingManager get(Context context) {
        if (instance == null) instance = new BillingManager(context.getApplicationContext());
        return instance;
    }

    private BillingManager(Context appContext) {
        this.appContext = appContext;
        billingClient = BillingClient.newBuilder(appContext)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build())
            .build();
        connect(null);
    }

    private void connect(Runnable onReady) {
        if (isConnected) { if (onReady != null) onReady.run(); return; }
        billingClient.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(@NonNull BillingResult result) {
                isConnected = result.getResponseCode() == BillingClient.BillingResponseCode.OK;
                if (isConnected) {
                    syncPurchases(); // pick up existing subscriptions on cold start
                    if (onReady != null) onReady.run();
                } else {
                    Log.e(TAG, "Billing setup failed: " + result.getDebugMessage());
                }
            }
            @Override public void onBillingServiceDisconnected() {
                isConnected = false;
            }
        });
    }

    // ── Product details (for showing real, localized prices) ───────────────

    public void loadProProducts(ProductDetailsListener listener) {
        loadProducts(PRO_PRODUCTS, listener);
    }

    public void loadChannelProducts(ProductDetailsListener listener) {
        loadProducts(CHANNEL_PRODUCTS, listener);
    }

    private void loadProducts(List<String> productIds, ProductDetailsListener listener) {
        connect(() -> {
            List<QueryProductDetailsParams.Product> products = new ArrayList<>();
            for (String id : productIds) {
                products.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build());
            }
            QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(products)
                .build();

            billingClient.queryProductDetailsAsync(params, (result, queryResult) -> {
                Map<String, ProductDetails> out = new HashMap<>();
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && queryResult != null) {
                    List<ProductDetails> detailsList = queryResult.getProductDetailsList();
                    for (ProductDetails pd : detailsList) {
                        productDetailsCache.put(pd.getProductId(), pd);
                        out.put(pd.getProductId(), pd);
                    }
                }
                listener.onLoaded(out);
            });
        });
    }

    /** Convenience: pulls the formatted, localized subscription price out of ProductDetails. */
    public static String formattedPrice(ProductDetails pd) {
        if (pd == null || pd.getSubscriptionOfferDetails() == null || pd.getSubscriptionOfferDetails().isEmpty())
            return "";
        List<ProductDetails.PricingPhase> phases =
            pd.getSubscriptionOfferDetails().get(0).getPricingPhases().getPricingPhaseList();
        if (phases.isEmpty()) return "";
        return phases.get(phases.size() - 1).getFormattedPrice();
    }

    // ── Launch purchase ──────────────────────────────────────────────────

    /** Buy Minigram Pro (no ads + blue tick + all features) for the current user. */
    public void launchProPurchase(Activity activity, String productId, PurchaseResultListener listener) {
        pendingChannelId = null;
        launchPurchase(activity, productId, listener);
    }

    /** Buy the blue tick for a specific channel. Owner-only — enforce that in the caller UI. */
    public void launchChannelVerifyPurchase(Activity activity, String productId, String channelId,
                                            PurchaseResultListener listener) {
        pendingChannelId = channelId;
        launchPurchase(activity, productId, listener);
    }

    private void launchPurchase(Activity activity, String productId, PurchaseResultListener listener) {
        activeListener = listener;
        ProductDetails pd = productDetailsCache.get(productId);
        if (pd == null || pd.getSubscriptionOfferDetails() == null || pd.getSubscriptionOfferDetails().isEmpty()) {
            if (listener != null) listener.onPurchaseFailed("Plan not available right now. Try again in a moment.");
            return;
        }
        String offerToken = pd.getSubscriptionOfferDetails().get(0).getOfferToken();

        BillingFlowParams.ProductDetailsParams productParams =
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(pd)
                .setOfferToken(offerToken)
                .build();

        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(java.util.Collections.singletonList(productParams))
            .build();

        billingClient.launchBillingFlow(activity, flowParams);
    }

    // ── Purchase result callback ────────────────────────────────────────

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result, @Nullable List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        } else if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            if (activeListener != null) activeListener.onPurchaseFailed("Cancelled.");
        } else {
            if (activeListener != null) activeListener.onPurchaseFailed(result.getDebugMessage());
        }
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return;

        for (String productId : purchase.getProducts()) {
            _applyEntitlement(productId, purchase, pendingChannelId);
        }

        // Subscriptions must be acknowledged within 3 days or Google auto-refunds them.
        if (!purchase.isAcknowledged()) {
            AcknowledgePurchaseParams ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();
            billingClient.acknowledgePurchase(ackParams, ackResult -> {
                if (activeListener != null) {
                    if (ackResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        for (String productId : purchase.getProducts())
                            activeListener.onPurchaseSuccess(productId);
                    } else {
                        activeListener.onPurchaseFailed(ackResult.getDebugMessage());
                    }
                }
            });
        } else if (activeListener != null) {
            for (String productId : purchase.getProducts())
                activeListener.onPurchaseSuccess(productId);
        }
    }

    private void _applyEntitlement(String productId, Purchase purchase, String channelId) {
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) return;
        long now = System.currentTimeMillis();

        if (PRO_PRODUCTS.contains(productId)) {
            Map<String, Object> update = new HashMap<>();
            update.put("is_pro",            true);
            update.put("pro_plan",          PRODUCT_PRO_YEARLY.equals(productId) ? "yearly" : "monthly");
            update.put("pro_purchase_time", now);
            FirebaseDatabase.getInstance().getReference("users").child(myUid).updateChildren(update);
            _notifyProActivated();

        } else if (CHANNEL_PRODUCTS.contains(productId) && channelId != null) {
            Map<String, Object> update = new HashMap<>();
            update.put("is_verified",            true);
            update.put("verified_plan",          PRODUCT_CHANNEL_VERIFIED_YEARLY.equals(productId) ? "yearly" : "monthly");
            update.put("verified_purchase_time", now);
            FirebaseDatabase.getInstance().getReference("channels").child(channelId).updateChildren(update);
        }
    }

    private static final String CHANNEL_ID_ADMIN   = "minigram_admin";
    private static final String CHANNEL_NAME_ADMIN = "Minigram Updates";

    /** Bug fix — a Pro purchase used to only show a Toast (gone the moment
     *  the screen closes) and never sent any actual notification. Local,
     *  not push: the purchasing device already knows the instant Billing
     *  confirms it, no server round-trip needed. Self-creates the
     *  notification channel (idempotent, safe to call repeatedly) rather
     *  than assuming FCMNotificationSender has already done it — this can
     *  legitimately be the very first notification a device ever gets. */
    private void _notifyProActivated() {
        NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID_ADMIN, CHANNEL_NAME_ADMIN, NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }

        Intent openIntent = new Intent(appContext, MyProfileActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(appContext, (int) System.currentTimeMillis(),
                openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID_ADMIN)
                .setSmallIcon(R.drawable.ic_notification) // small icon MUST stay monochrome (API 21+)
                .setContentTitle("Welcome to Minigram Pro! \uD83C\uDF89")
                .setContentText("No ads, blue tick, and everything unlocked.")
                .setColor(0xFF2196F3)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    // ── Restore / re-sync on app start ──────────────────────────────────

    /**
     * Re-queries Play for this user's active subscriptions and reconciles
     * Firebase — turns is_pro OFF if the Pro subscription is no longer
     * active (expired / cancelled and lapsed / refunded). Channel
     * verification purchases are per-channel and aren't auto-revoked here
     * since we don't always know which channel a restored purchase maps to
     * across reinstalls; the owner can re-verify from Channel Settings if
     * that ever happens.
     */
    public void syncPurchases() {
        if (!isConnected) return;
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build();

        billingClient.queryPurchasesAsync(params, (result, purchases) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;

            boolean hasActivePro = false;
            for (Purchase p : purchases) {
                if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    for (String productId : p.getProducts()) {
                        if (PRO_PRODUCTS.contains(productId)) hasActivePro = true;
                    }
                    if (!p.isAcknowledged()) handlePurchase(p);
                }
            }

            String myUid = FirebaseAuth.getInstance().getUid();
            if (myUid == null) return;

            if (hasActivePro) {
                FirebaseDatabase.getInstance().getReference("users").child(myUid)
                    .child("is_pro").setValue(true);
                return;
            }

            // BUG FIXED: this used to unconditionally write
            // is_pro = hasActivePro, which silently REVOKED Pro on the very
            // next app launch for anyone granted it from the admin panel —
            // there's no Play subscription behind an admin grant, so Play
            // always answers "no active Pro" for them. That's why an
            // admin-granted blue tick never actually removed ads or showed
            // the badge: the grant was being wiped before the user ever saw
            // it. Only revoke when the grant actually CAME from Play
            // (pro_plan is the monthly/yearly product) — an admin grant
            // (pro_plan == "admin_granted", written by users.php) is left
            // alone, and only the admin panel can take it back.
            FirebaseDatabase.getInstance().getReference("users").child(myUid).child("pro_plan")
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(
                            @NonNull com.google.firebase.database.DataSnapshot snap) {
                        String plan = snap.getValue(String.class);
                        if ("admin_granted".equals(plan)) {
                            Log.d(TAG, "keeping admin-granted Pro (no Play purchase expected)");
                            return;
                        }
                        FirebaseDatabase.getInstance().getReference("users").child(myUid)
                            .child("is_pro").setValue(false);
                    }
                    @Override public void onCancelled(
                            @NonNull com.google.firebase.database.DatabaseError e) {
                        // Couldn't read the plan — do NOT revoke on a failed
                        // read; wrongly stripping someone's Pro is far worse
                        // than briefly leaving a lapsed subscription active,
                        // and the next successful sync will correct it.
                    }
                });
        });
    }

    /** Quick synchronous-ish check used by ad helpers — reads the last-known Firebase flag. */
    public interface ProStatusCallback { void onResult(boolean isPro); }

    public static void isCurrentUserPro(ProStatusCallback callback) {
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) { callback.onResult(false); return; }

        // Safety net: if Firebase never calls back (e.g. offline with nothing
        // cached yet for this path), don't let callers hang forever waiting —
        // that would silently block things like interstitial ads from EVER
        // showing. Whichever fires first (the real read or this timeout) wins;
        // the other is a no-op.
        final boolean[] answered = {false};
        final android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable timeout = () -> {
            if (!answered[0]) {
                answered[0] = true;
                callback.onResult(false);
            }
        };
        timeoutHandler.postDelayed(timeout, 4000);

        FirebaseDatabase.getInstance().getReference("users").child(myUid).child("is_pro")
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snap) {
                    if (answered[0]) return;
                    answered[0] = true;
                    timeoutHandler.removeCallbacks(timeout);
                    callback.onResult(Boolean.TRUE.equals(snap.getValue(Boolean.class)));
                }
                @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                    if (answered[0]) return;
                    answered[0] = true;
                    timeoutHandler.removeCallbacks(timeout);
                    callback.onResult(false);
                }
            });
    }
}
