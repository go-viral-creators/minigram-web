package com.silu.minigram.e2ee;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * X3DH-style session set-up + Double Ratchet (per-message keys, forward secrecy and
 * post-compromise healing). One {@code Ratchet} is one session between two devices.
 *
 * Wire layout of a ciphertext:  version(1) | sid(8) | dhPub(32) | pn(4) | n(4) | AEAD(...)
 * AEAD key = per-message key, AAD = (identityA || identityB || header).
 */
public final class Ratchet {
    static final byte VERSION = 1;
    static final int MAX_SKIP = 256;
    static final int HEADER = 1 + 8 + 32 + 4 + 4;

    // ---- session identity ----
    public final byte[] sid;            // 8 bytes, derived from the initiator's ephemeral key
    final byte[] ad;                    // identityInitiator || identityResponder
    final boolean initiator;
    /** Initiator only: stays true until the first reply is decrypted (then X3DH info stops being attached). */
    public boolean awaitingFirstReply;
    // X3DH info the initiator attaches to early messages
    public final byte[] x3dhIdentity, x3dhEphemeral;
    public final int x3dhSpkId;

    // ---- ratchet state ----
    private byte[] rk;
    private byte[] dhsPriv, dhsPub, dhr;
    private byte[] cks, ckr;
    private int ns, nr, pn;
    private final LinkedHashMap<String, byte[]> skipped = new LinkedHashMap<>();

    private Ratchet(byte[] sid, byte[] ad, boolean initiator, byte[] x3dhIdentity, byte[] x3dhEphemeral, int spkId) {
        this.sid = sid; this.ad = ad; this.initiator = initiator;
        this.x3dhIdentity = x3dhIdentity; this.x3dhEphemeral = x3dhEphemeral; this.x3dhSpkId = spkId;
    }

    // ------------------------------------------------------------------ set-up

    /** Alice: start a session towards Bob's published bundle. */
    public static Ratchet initiate(Crypto.DhKeyPair myIdentity, byte[] bobIdentityPub,
                                   byte[] bobSpkPub, int bobSpkId) throws GeneralSecurityException {
        Crypto.DhKeyPair ek = Crypto.newDhKeyPair();
        byte[] sk = Crypto.hkdf(Crypto.concat(
                new byte[32],                                   // F: domain separation per X3DH
                Crypto.dh(myIdentity.priv, bobSpkPub),         // DH1
                Crypto.dh(ek.priv, bobIdentityPub),            // DH2
                Crypto.dh(ek.priv, bobSpkPub)),                // DH3
                new byte[32], "minigram-x3dh-v1", 32);
        Ratchet r = new Ratchet(Arrays.copyOf(Crypto.sha256(ek.pub), 8),
                Crypto.concat(myIdentity.pub, bobIdentityPub), true, myIdentity.pub, ek.pub, bobSpkId);
        r.awaitingFirstReply = true;
        r.dhsPriv = null;
        Crypto.DhKeyPair dhs = Crypto.newDhKeyPair();
        r.dhsPriv = dhs.priv; r.dhsPub = dhs.pub; r.dhr = bobSpkPub;
        byte[][] x = kdfRk(sk, Crypto.dh(dhs.priv, bobSpkPub));
        r.rk = x[0]; r.cks = x[1];
        return r;
    }

    /** Bob: accept a session announced by Alice's first message. */
    public static Ratchet accept(Crypto.DhKeyPair myIdentity, Crypto.DhKeyPair mySpk, int spkId,
                                 byte[] aliceIdentityPub, byte[] aliceEphemeralPub) throws GeneralSecurityException {
        byte[] sk = Crypto.hkdf(Crypto.concat(
                new byte[32],
                Crypto.dh(mySpk.priv, aliceIdentityPub),       // DH1
                Crypto.dh(myIdentity.priv, aliceEphemeralPub), // DH2
                Crypto.dh(mySpk.priv, aliceEphemeralPub)),     // DH3
                new byte[32], "minigram-x3dh-v1", 32);
        Ratchet r = new Ratchet(Arrays.copyOf(Crypto.sha256(aliceEphemeralPub), 8),
                Crypto.concat(aliceIdentityPub, myIdentity.pub), false, null, null, spkId);
        r.rk = sk; r.dhsPriv = mySpk.priv; r.dhsPub = mySpk.pub;
        return r;
    }

    // ----------------------------------------------------------------- messaging

    /** True once this session has a sending chain (initiators immediately; responders after the first receive). */
    public boolean canSend() { return cks != null; }

    public byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        if (cks == null) throw new IllegalStateException("no sending chain yet (receive first)");
        byte[] mk = Crypto.hmac(cks, (byte) 1);
        cks = Crypto.hmac(cks, (byte) 2);
        byte[] header = header(dhsPub, pn, ns);
        ns++;
        byte[] body = Crypto.seal(mk, plain, Crypto.concat(ad, header));
        return Crypto.concat(header, body);
    }

    /** @throws GeneralSecurityException if authentication fails, the message is replayed, or is too far ahead. */
    public byte[] decrypt(byte[] msg) throws GeneralSecurityException {
        if (msg.length <= HEADER + 40 || msg[0] != VERSION) throw new GeneralSecurityException("bad format");
        byte[] header = Arrays.copyOf(msg, HEADER);
        byte[] body = Arrays.copyOfRange(msg, HEADER, msg.length);
        ByteBuffer bb = ByteBuffer.wrap(header, 9, HEADER - 9);
        byte[] theirDh = new byte[32];
        bb.get(theirDh);
        int theirPn = bb.getInt(), n = bb.getInt();
        byte[] aad = Crypto.concat(ad, header);

        // 1) a key we skipped earlier?
        String id = Crypto.b64(theirDh) + ":" + n;
        byte[] stored = skipped.get(id);
        if (stored != null) {
            byte[] plain = Crypto.open(stored, body, aad);
            skipped.remove(id);
            return plain;
        }

        // Work on copies so a forged/corrupt message cannot damage the live state.
        Ratchet work = this.copy();
        if (work.dhr == null || !Crypto.ctEquals(work.dhr, theirDh)) {
            work.skipMessageKeys(theirPn);
            work.dhRatchet(theirDh);
        }
        work.skipMessageKeys(n);
        byte[] mk = Crypto.hmac(work.ckr, (byte) 1);
        work.ckr = Crypto.hmac(work.ckr, (byte) 2);
        work.nr++;
        byte[] plain = Crypto.open(mk, body, aad);     // throws on tamper -> state untouched
        this.adopt(work);
        this.awaitingFirstReply = false;
        return plain;
    }

    private void skipMessageKeys(int until) throws GeneralSecurityException {
        if (ckr == null) return;
        if (until - nr > MAX_SKIP) throw new GeneralSecurityException("too many skipped messages");
        while (nr < until) {
            skipped.put(Crypto.b64(dhr) + ":" + nr, Crypto.hmac(ckr, (byte) 1));
            ckr = Crypto.hmac(ckr, (byte) 2);
            nr++;
            while (skipped.size() > 2 * MAX_SKIP) {
                Iterator<String> it = skipped.keySet().iterator();
                it.next(); it.remove();
            }
        }
    }

    private void dhRatchet(byte[] theirDh) throws GeneralSecurityException {
        pn = ns; ns = 0; nr = 0;
        dhr = theirDh;
        byte[][] a = kdfRk(rk, Crypto.dh(dhsPriv, dhr));
        rk = a[0]; ckr = a[1];
        Crypto.DhKeyPair nk = Crypto.newDhKeyPair();
        dhsPriv = nk.priv; dhsPub = nk.pub;
        byte[][] b = kdfRk(rk, Crypto.dh(dhsPriv, dhr));
        rk = b[0]; cks = b[1];
    }

    private static byte[][] kdfRk(byte[] rk, byte[] dhOut) throws GeneralSecurityException {
        byte[] o = Crypto.hkdf(dhOut, rk, "minigram-ratchet-v1", 64);
        return new byte[][]{Arrays.copyOf(o, 32), Arrays.copyOfRange(o, 32, 64)};
    }

    private byte[] header(byte[] dhPub, int pn, int n) {
        ByteBuffer bb = ByteBuffer.allocate(HEADER);
        bb.put(VERSION).put(sid).put(dhPub).putInt(pn).putInt(n);
        return bb.array();
    }

    /** Reads the 8-byte session id out of a ciphertext without decrypting. */
    public static byte[] sidOf(byte[] msg) { return msg.length > 9 ? Arrays.copyOfRange(msg, 1, 9) : new byte[8]; }

    // ------------------------------------------------------------ (de)serialisation

    private Ratchet copy() {
        try { return fromBytes(toBytes()); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private void adopt(Ratchet o) {
        rk = o.rk; dhsPriv = o.dhsPriv; dhsPub = o.dhsPub; dhr = o.dhr; cks = o.cks; ckr = o.ckr;
        ns = o.ns; nr = o.nr; pn = o.pn;
        skipped.clear(); skipped.putAll(o.skipped);
    }

    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.writeByte(1);
        o.writeBoolean(initiator); o.writeBoolean(awaitingFirstReply);
        w(o, sid); w(o, ad); w(o, x3dhIdentity); w(o, x3dhEphemeral); o.writeInt(x3dhSpkId);
        w(o, rk); w(o, dhsPriv); w(o, dhsPub); w(o, dhr); w(o, cks); w(o, ckr);
        o.writeInt(ns); o.writeInt(nr); o.writeInt(pn);
        o.writeInt(skipped.size());
        for (Map.Entry<String, byte[]> e : skipped.entrySet()) { o.writeUTF(e.getKey()); w(o, e.getValue()); }
        return bo.toByteArray();
    }

    public static Ratchet fromBytes(byte[] b) throws IOException {
        DataInputStream i = new DataInputStream(new ByteArrayInputStream(b));
        if (i.readByte() != 1) throw new IOException("unknown state version");
        boolean init = i.readBoolean(), await = i.readBoolean();
        byte[] sid = r(i), ad = r(i), xi = r(i), xe = r(i);
        int spk = i.readInt();
        Ratchet s = new Ratchet(sid, ad, init, xi, xe, spk);
        s.awaitingFirstReply = await;
        s.rk = r(i); s.dhsPriv = r(i); s.dhsPub = r(i); s.dhr = r(i); s.cks = r(i); s.ckr = r(i);
        s.ns = i.readInt(); s.nr = i.readInt(); s.pn = i.readInt();
        int n = i.readInt();
        for (int k = 0; k < n; k++) s.skipped.put(i.readUTF(), r(i));
        return s;
    }

    private static void w(DataOutputStream o, byte[] v) throws IOException {
        if (v == null) { o.writeInt(-1); return; }
        o.writeInt(v.length); o.write(v);
    }
    private static byte[] r(DataInputStream i) throws IOException {
        int n = i.readInt();
        if (n < 0) return null;
        byte[] v = new byte[n]; i.readFully(v); return v;
    }
}
