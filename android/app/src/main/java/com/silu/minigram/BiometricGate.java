package com.silu.minigram;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Biometric unlock bound to the Android Keystore: the AES key requires a fresh strong
 * biometric for EVERY use ({@code setUserAuthenticationRequired(true)}), and it is invalidated
 * when fingerprints/faces are added. Unlock only succeeds if the Keystore itself lets the
 * cipher decrypt after the biometric - so a hooked/faked callback alone cannot unlock the app.
 */
public final class BiometricGate {
    private static final String ALIAS = "minigram_bio_gate";
    public static final String KEY_ENABLED = "biometric_unlock_enabled";
    private static final String K_MARK = "bio_mark";

    public interface Callback { void onUnlocked(); void onFailed(boolean userCancelled); }

    private BiometricGate() {}

    public static boolean isAvailable(Context c) {
        return BiometricManager.from(c).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, false) && isAvailable(c);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PrivacySecurityActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** Enrols: encrypts a random marker with the biometric-bound key after one successful prompt. */
    public static void enable(FragmentActivity a, Callback cb) {
        try {
            SecureVault.delete(ALIAS);
            SecretKey k = authKey();
            Cipher enc = Cipher.getInstance("AES/GCM/NoPadding");
            enc.init(Cipher.ENCRYPT_MODE, k);
            prompt(a, enc, "Enable biometric unlock", new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                    try {
                        Cipher c = r.getCryptoObject().getCipher();
                        byte[] ct = c.doFinal(SecureVault.random(32));
                        prefs(a).edit().putBoolean(KEY_ENABLED, true)
                                .putString(K_MARK, Base64.encodeToString(SecureVault.pack(c.getIV(), ct), Base64.NO_WRAP))
                                .apply();
                        cb.onUnlocked();
                    } catch (Exception e) { cb.onFailed(false); }
                }
                @Override public void onAuthenticationError(int code, @NonNull CharSequence msg) { cb.onFailed(true); }
            });
        } catch (Exception e) { cb.onFailed(false); }
    }

    public static void disable(Context c) {
        prefs(c).edit().putBoolean(KEY_ENABLED, false).remove(K_MARK).apply();
        SecureVault.delete(ALIAS);
    }

    public static void unlock(FragmentActivity a, Callback cb) {
        try {
            String mark = prefs(a).getString(K_MARK, null);
            if (mark == null) { cb.onFailed(false); return; }
            byte[] blob = Base64.decode(mark, Base64.NO_WRAP);
            int ivLen = blob[0] & 0xff;
            byte[] iv = java.util.Arrays.copyOfRange(blob, 1, 1 + ivLen);
            Cipher dec = Cipher.getInstance("AES/GCM/NoPadding");
            dec.init(Cipher.DECRYPT_MODE, authKey(), new GCMParameterSpec(128, iv));
            byte[] ct = java.util.Arrays.copyOfRange(blob, 1 + ivLen, blob.length);
            prompt(a, dec, "Unlock Minigram", new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                    try { r.getCryptoObject().getCipher().doFinal(ct); cb.onUnlocked(); }
                    catch (Exception e) { cb.onFailed(false); }
                }
                @Override public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                    cb.onFailed(code == BiometricPrompt.ERROR_USER_CANCELED
                            || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON);
                }
            });
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
            disable(a);                       // biometrics changed: fall back to PIN
            cb.onFailed(false);
        } catch (Exception e) { cb.onFailed(false); }
    }

    private static SecretKey authKey() throws Exception {
        return SecureVault.key(ALIAS, true);
    }

    private static void prompt(FragmentActivity a, Cipher cipher, String title,
                               BiometricPrompt.AuthenticationCallback cb) {
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title).setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build();
        new BiometricPrompt(a, ContextCompat.getMainExecutor(a), cb)
                .authenticate(info, new BiometricPrompt.CryptoObject(cipher));
    }
}
