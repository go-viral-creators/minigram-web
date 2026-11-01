package com.silu.minigram;

import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.android.billingclient.api.ProductDetails;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.Map;

/**
 * "Minigram Pro" store screen — no ads + blue tick + all features, monthly
 * or yearly. Prices are pulled live from Play Billing (ProductDetails) so
 * whatever you set in Play Console is what shows here automatically.
 */
public class SubscriptionActivity extends AppCompatActivity {

    private TextView btn_back;
    private TextView txt_price_monthly, txt_price_yearly;
    private TextView btn_subscribe_monthly, btn_subscribe_yearly;
    private TextView txt_current_status;
    private ProgressBar progress_loading;

    private BillingManager billingManager;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.setup(this);
        setContentView(R.layout.subscription);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        btn_back              = findViewById(R.id.btn_back);
        txt_price_monthly     = findViewById(R.id.txt_price_monthly);
        txt_price_yearly      = findViewById(R.id.txt_price_yearly);
        btn_subscribe_monthly = findViewById(R.id.btn_subscribe_monthly);
        btn_subscribe_yearly  = findViewById(R.id.btn_subscribe_yearly);
        txt_current_status    = findViewById(R.id.txt_current_status);
        progress_loading      = findViewById(R.id.progress_loading);

        myUid = FirebaseAuth.getInstance().getUid();
        billingManager = BillingManager.get(this);

        btn_back.setOnClickListener(v -> finish());

        _checkCurrentStatus();
        _loadPlans();

        btn_subscribe_monthly.setOnClickListener(v ->
            _buy(BillingManager.PRODUCT_PRO_MONTHLY));
        btn_subscribe_yearly.setOnClickListener(v ->
            _buy(BillingManager.PRODUCT_PRO_YEARLY));
    }

    private void _checkCurrentStatus() {
        if (myUid == null) return;
        FirebaseDatabase.getInstance().getReference("users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    boolean isPro = Boolean.TRUE.equals(snap.child("is_pro").getValue(Boolean.class));
                    if (isPro) {
                        String plan = snap.child("pro_plan").getValue(String.class);
                        txt_current_status.setVisibility(View.VISIBLE);
                        txt_current_status.setText("✓ You're already Pro (" +
                            ("yearly".equals(plan) ? "Yearly" : "Monthly") + " plan)");
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void _loadPlans() {
        progress_loading.setVisibility(View.VISIBLE);
        billingManager.loadProProducts(details -> runOnUiThread(() -> {
            progress_loading.setVisibility(View.GONE);
            _bindPrice(details, BillingManager.PRODUCT_PRO_MONTHLY, txt_price_monthly, "/ month");
            _bindPrice(details, BillingManager.PRODUCT_PRO_YEARLY, txt_price_yearly, "/ year");
        }));
    }

    private void _bindPrice(Map<String, ProductDetails> details, String productId,
                            TextView target, String suffix) {
        ProductDetails pd = details.get(productId);
        if (pd != null) {
            target.setText(BillingManager.formattedPrice(pd) + " " + suffix);
        } else {
            target.setText("Unavailable");
        }
    }

    private void _buy(String productId) {
        billingManager.launchProPurchase(this, productId, new BillingManager.PurchaseResultListener() {
            @Override public void onPurchaseSuccess(String productId) {
                runOnUiThread(() -> {
                    Toast.makeText(SubscriptionActivity.this,
                        "🎉 Welcome to Minigram Pro!", Toast.LENGTH_LONG).show();
                    _checkCurrentStatus();
                });
            }
            @Override public void onPurchaseFailed(String message) {
                runOnUiThread(() ->
                    Toast.makeText(SubscriptionActivity.this, message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
