package com.silu.minigram;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Thin wrapper over Android Keystore AES-256-GCM keys. The key material never leaves the
 * secure hardware (StrongBox when the device has it, TEE otherwise), so anything encrypted
 * here is useless if the app's files are copied off the phone.
 *
 * Used for: the PIN verifier, the E2EE identity private key, ratchet state, cached plaintext.
 * Blob layout: [1 byte iv length][iv][ciphertext+tag].
 */
public final class SecureVault {
    private static final String ANDROID_KS = "AndroidKeyStore";
    private static final SecureRandom RNG = new SecureRandom();

    private SecureVault() {}

    public static synchronized SecretKey key(String alias, boolean requireUserAuth) throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KS);
        ks.load(null);
        if (ks.containsAlias(alias)) return (SecretKey) ks.getKey(alias, null);

        for (boolean strongBox : new boolean[]{true, false}) {
            if (strongBox && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) continue;
            try {
                KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256);
                if (requireUserAuth) {
                    b.setUserAuthenticationRequired(true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) b.setInvalidatedByBiometricEnrollment(true);
                }
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.setIsStrongBoxBacked(true);
                KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KS);
                kg.init(b.build());
                return kg.generateKey();
            } catch (Exception e) {
                if (!strongBox) throw e;           // StrongBox unavailable -> retry without it
            }
        }
        throw new IllegalStateException("unreachable");
    }

    public static byte[] encrypt(String alias, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(alias, false));
        return pack(c.getIV(), c.doFinal(plain));
    }

    public static byte[] decrypt(String alias, byte[] blob) throws Exception {
        ByteBuffer bb = ByteBuffer.wrap(blob);
        byte[] iv = new byte[bb.get() & 0xff];
        bb.get(iv);
        byte[] ct = new byte[bb.remaining()];
        bb.get(ct);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(alias, false), new GCMParameterSpec(128, iv));
        return c.doFinal(ct);
    }

    public static byte[] pack(byte[] iv, byte[] ct) {
        ByteBuffer bb = ByteBuffer.allocate(1 + iv.length + ct.length);
        bb.put((byte) iv.length).put(iv).put(ct);
        return bb.array();
    }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static void delete(String alias) {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KS);
            ks.load(null);
            ks.deleteEntry(alias);
        } catch (Exception ignored) { }
    }
}
