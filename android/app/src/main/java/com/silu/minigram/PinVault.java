package com.silu.minigram;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PIN storage that is safe against a copied-off app data directory.
 *
 *   stored = AES-GCM_keystore( PBKDF2-HMAC-SHA256(pin, random salt, 150k iterations) )
 *
 * The Keystore key is non-exportable, so an attacker with the prefs file alone cannot even
 * start an offline brute force of the 4-6 digit PIN. Failed attempts are rate limited with an
 * exponential back-off. The old unsalted SHA-256 hash is migrated on the next successful unlock.
 */
public final class PinVault {
    private static final String ALIAS = "minigram_pin_vault";
    private static final String K_SALT = "pin_salt", K_BLOB = "pin_blob";
    private static final String K_FAILS = "pin_fails", K_NEXT = "pin_next_ok";
    private static final int ITER = 150_000;

    private PinVault() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PrivacySecurityActivity.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasPin(Context c) {
        SharedPreferences p = prefs(c);
        return p.contains(K_BLOB) || p.contains(PinLockActivity.KEY_PIN_HASH);
    }

    public static void setPin(Context c, String pin) throws Exception {
        byte[] salt = SecureVault.random(16);
        byte[] blob = SecureVault.encrypt(ALIAS, derive(pin, salt));
        prefs(c).edit()
                .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(K_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
                .remove(PinLockActivity.KEY_PIN_HASH).remove(K_FAILS).remove(K_NEXT).apply();
    }

    public static void clear(Context c) {
        prefs(c).edit().remove(K_SALT).remove(K_BLOB).remove(PinLockActivity.KEY_PIN_HASH)
                .remove(K_FAILS).remove(K_NEXT).apply();
        SecureVault.delete(ALIAS);
    }

    /** @return ms to wait before the next attempt is allowed (0 = allowed now). */
    public static long lockedForMs(Context c) {
        return Math.max(0, prefs(c).getLong(K_NEXT, 0) - System.currentTimeMillis());
    }

    public static boolean verify(Context c, String pin) {
        if (lockedForMs(c) > 0) return false;
        SharedPreferences p = prefs(c);
        boolean ok = false;
        try {
            String blob = p.getString(K_BLOB, null), salt = p.getString(K_SALT, null);
            if (blob != null && salt != null) {
                byte[] expect = SecureVault.decrypt(ALIAS, Base64.decode(blob, Base64.NO_WRAP));
                byte[] got = derive(pin, Base64.decode(salt, Base64.NO_WRAP));
                ok = MessageDigest.isEqual(expect, got);
            } else if (p.contains(PinLockActivity.KEY_PIN_HASH)) {   // legacy unsalted SHA-256
                byte[] d = MessageDigest.getInstance("SHA-256").digest(pin.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b));
                ok = MessageDigest.isEqual(sb.toString().getBytes("UTF-8"),
                        p.getString(PinLockActivity.KEY_PIN_HASH, "").getBytes("UTF-8"));
                if (ok) setPin(c, pin);                              // migrate
            }
        } catch (Exception e) {
            ok = false;
        }
        if (ok) {
            p.edit().remove(K_FAILS).remove(K_NEXT).apply();
        } else {
            int fails = p.getInt(K_FAILS, 0) + 1;
            long delay = fails < 5 ? 0 : Math.min(3_600_000L, 30_000L << Math.min(fails - 5, 7));
            p.edit().putInt(K_FAILS, fails).putLong(K_NEXT, System.currentTimeMillis() + delay).apply();
        }
        return ok;
    }

    private static byte[] derive(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITER, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
