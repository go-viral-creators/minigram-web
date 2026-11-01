package com.silu.minigram;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.ProductDetails;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.Map;

/**
 * Channel blue-tick purchase — owner-only, always tied to ONE specific
 * channel (the one whose settings screen opened this sheet), separate from
 * and priced independently of the user "Minigram Pro" subscription.
 */
public class ChannelVerifySheet extends BottomSheetDialogFragment {

    private static final String A_CHANNEL_ID   = "channel_id";
    private static final String A_CHANNEL_NAME = "channel_name";

    public static ChannelVerifySheet newInstance(String channelId, String channelName) {
        ChannelVerifySheet sheet = new ChannelVerifySheet();
        Bundle b = new Bundle();
        b.putString(A_CHANNEL_ID, channelId);
        b.putString(A_CHANNEL_NAME, channelName);
        sheet.setArguments(b);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.sheet_channel_verify, container, false);
        if (getContext() == null || getArguments() == null) return v;

        String channelId   = getArguments().getString(A_CHANNEL_ID);
        String channelName = getArguments().getString(A_CHANNEL_NAME);
        if (channelId == null) { dismissAllowingStateLoss(); return v; }

        TextView txtName            = v.findViewById(R.id.txt_channel_name);
        TextView txtPriceMonthly    = v.findViewById(R.id.txt_ch_price_monthly);
        TextView txtPriceYearly     = v.findViewById(R.id.txt_ch_price_yearly);
        TextView btnSubMonthly      = v.findViewById(R.id.btn_ch_subscribe_monthly);
        TextView btnSubYearly       = v.findViewById(R.id.btn_ch_subscribe_yearly);
        TextView txtCurrentStatus   = v.findViewById(R.id.txt_ch_current_status);
        ProgressBar progressLoading = v.findViewById(R.id.progress_ch_loading);

        if (channelName != null && !channelName.isEmpty()) {
            txtName.setText("Verify \"" + channelName + "\"");
        }

        BillingManager billingManager = BillingManager.get(requireContext());

        // Current status
        FirebaseDatabase.getInstance().getReference("channels").child(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    boolean isVerified = Boolean.TRUE.equals(snap.child("is_verified").getValue(Boolean.class));
                    if (isVerified) {
                        String plan = snap.child("verified_plan").getValue(String.class);
                        txtCurrentStatus.setVisibility(View.VISIBLE);
                        txtCurrentStatus.setText("✓ Already verified (" +
                            ("yearly".equals(plan) ? "Yearly" : "Monthly") + " plan)");
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });

        // Prices
        progressLoading.setVisibility(View.VISIBLE);
        billingManager.loadChannelProducts(details -> {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                progressLoading.setVisibility(View.GONE);
                _bindPrice(details, BillingManager.PRODUCT_CHANNEL_VERIFIED_MONTHLY, txtPriceMonthly, "/ month");
                _bindPrice(details, BillingManager.PRODUCT_CHANNEL_VERIFIED_YEARLY, txtPriceYearly, "/ year");
            });
        });

        btnSubMonthly.setOnClickListener(v2 ->
            _buy(billingManager, channelId, BillingManager.PRODUCT_CHANNEL_VERIFIED_MONTHLY));
        btnSubYearly.setOnClickListener(v2 ->
            _buy(billingManager, channelId, BillingManager.PRODUCT_CHANNEL_VERIFIED_YEARLY));

        return v;
    }

    private void _bindPrice(Map<String, ProductDetails> details, String productId,
                            TextView target, String suffix) {
        ProductDetails pd = details.get(productId);
        target.setText(pd != null ? BillingManager.formattedPrice(pd) + " " + suffix : "Unavailable");
    }

    private void _buy(BillingManager billingManager, String channelId, String productId) {
        if (getActivity() == null) return;
        billingManager.launchChannelVerifyPurchase(getActivity(), productId, channelId,
            new BillingManager.PurchaseResultListener() {
                @Override public void onPurchaseSuccess(String productId) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "✓ Channel verified!", Toast.LENGTH_LONG).show();
                        dismissAllowingStateLoss();
                    });
                }
                @Override public void onPurchaseFailed(String message) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show());
                }
            });
    }
}
