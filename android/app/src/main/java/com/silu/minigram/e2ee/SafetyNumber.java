package com.silu.minigram.e2ee;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Safety number (12 groups of 5 digits) + QR payload for verifying a chat's identity keys.
 * Both phones compute the same number because the two halves are sorted before joining.
 */
public final class SafetyNumber {
    private SafetyNumber() {}

    /** 30-digit half: SHA-512 iterated 5200x over (version | identityKey | userId), like Signal. */
    static String half(byte[] identityPub, String uid) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] h = Crypto.concat(new byte[]{0, 0}, identityPub, uid.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 5200; i++) {
                md.update(h); md.update(identityPub);
                h = md.digest();
            }
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < 6; c++) {
                long v = 0;
                for (int k = 0; k < 5; k++) v = (v << 8) | (h[c * 5 + k] & 0xff);
                sb.append(String.format("%05d", v % 100000));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** @return 60 digits (12 groups of 5). */
    public static String compute(byte[] myIdentity, String myUid, byte[] theirIdentity, String theirUid) {
        String a = half(myIdentity, myUid), b = half(theirIdentity, theirUid);
        return a.compareTo(b) <= 0 ? a + b : b + a;
    }

    public static String[] groups(String number) {
        String[] g = new String[number.length() / 5];
        for (int i = 0; i < g.length; i++) g[i] = number.substring(i * 5, i * 5 + 5);
        return g;
    }

    /** What the QR code carries: the safety number itself (scanner compares with its own). */
    public static String qrPayload(String number) { return "minigram-verify:1:" + number; }

    public static boolean matchesQr(String scanned, String myNumber) {
        return scanned != null && MessageDigest.isEqual(
                scanned.getBytes(StandardCharsets.UTF_8), qrPayload(myNumber).getBytes(StandardCharsets.UTF_8));
    }

    /** Fingerprint stored for TOFU key-change detection. */
    public static String fingerprint(byte[] identityPub) {
        return Crypto.b64(Arrays.copyOf(Crypto.sha256(identityPub), 16));
    }
}
