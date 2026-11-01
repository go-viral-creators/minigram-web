package com.silu.minigram.e2ee;

import com.google.crypto.tink.subtle.Ed25519Sign;
import com.google.crypto.tink.subtle.Ed25519Verify;
import com.google.crypto.tink.subtle.Hkdf;
import com.google.crypto.tink.subtle.X25519;
import com.google.crypto.tink.subtle.XChaCha20Poly1305;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Primitive layer for Minigram end-to-end encryption. Pure Java (no Android classes) so it is
 * unit-testable on the JVM.
 *
 *   key agreement : X25519            (Tink subtle)
 *   signatures    : Ed25519           (signs the signed pre-key)
 *   KDF           : HKDF-SHA256, HMAC-SHA256 chain KDF
 *   AEAD          : XChaCha20-Poly1305 (24-byte random nonce, prepended)
 */
public final class Crypto {
    private static final SecureRandom RNG = new SecureRandom();
    private Crypto() {}

    public static final class DhKeyPair {
        public final byte[] priv, pub;
        DhKeyPair(byte[] priv, byte[] pub) { this.priv = priv; this.pub = pub; }
    }

    public static final class SigKeyPair {
        public final byte[] priv, pub;
        SigKeyPair(byte[] priv, byte[] pub) { this.priv = priv; this.pub = pub; }
    }

    public static DhKeyPair newDhKeyPair() throws GeneralSecurityException {
        byte[] priv = X25519.generatePrivateKey();
        return new DhKeyPair(priv, X25519.publicFromPrivate(priv));
    }

    public static byte[] dh(byte[] priv, byte[] pub) throws GeneralSecurityException {
        return X25519.computeSharedSecret(priv, pub);
    }

    public static SigKeyPair newSigKeyPair() throws GeneralSecurityException {
        Ed25519Sign.KeyPair kp = Ed25519Sign.KeyPair.newKeyPair();
        return new SigKeyPair(kp.getPrivateKey(), kp.getPublicKey());
    }

    public static byte[] sign(byte[] sigPriv, byte[] data) throws GeneralSecurityException {
        return new Ed25519Sign(sigPriv).sign(data);
    }

    public static boolean verify(byte[] sigPub, byte[] sig, byte[] data) {
        try { new Ed25519Verify(sigPub).verify(sig, data); return true; }
        catch (GeneralSecurityException e) { return false; }
    }

    public static byte[] hkdf(byte[] ikm, byte[] salt, String info, int len) throws GeneralSecurityException {
        return Hkdf.computeHkdf("HmacSha256", ikm, salt, info.getBytes(java.nio.charset.StandardCharsets.UTF_8), len);
    }

    public static byte[] hmac(byte[] key, byte tag) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(new byte[]{tag});
    }

    /** Encrypts with a 32-byte key. Output = nonce(24) || ciphertext || tag(16). */
    public static byte[] seal(byte[] key32, byte[] plain, byte[] aad) throws GeneralSecurityException {
        return new XChaCha20Poly1305(key32).encrypt(plain, aad);
    }

    public static byte[] open(byte[] key32, byte[] boxed, byte[] aad) throws GeneralSecurityException {
        return new XChaCha20Poly1305(key32).decrypt(boxed, aad);
    }

    public static DhKeyPair dhFrom(byte[] priv, byte[] pub) { return new DhKeyPair(priv, pub); }
    public static SigKeyPair sigFrom(byte[] priv, byte[] pub) { return new SigKeyPair(priv, pub); }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static boolean ctEquals(byte[] a, byte[] b) { return MessageDigest.isEqual(a, b); }

    public static String b64(byte[] b) { return com.google.crypto.tink.subtle.Base64.encode(b); }
    public static byte[] unb64(String s) { return com.google.crypto.tink.subtle.Base64.decode(s); }
}
