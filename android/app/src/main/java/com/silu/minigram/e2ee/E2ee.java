package com.silu.minigram.e2ee;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Minigram end-to-end encryption facade (1:1 chats only; channels are public and stay plaintext).
 *
 * What the server (Firebase) can see per message: sender uid, timestamp, type, read-status flags and an
 * opaque ciphertext. It never sees text, captions, reply previews, link previews, media URLs or media keys.
 *
 * Keys: X25519 identity + signed pre-key (Ed25519-signed), X3DH-style set-up, Double Ratchet per-message keys,
 * XChaCha20-Poly1305. Private keys are stored only AES-GCM-wrapped by a non-exportable Android Keystore key.
 */
public final class E2ee {
    public static final String PLACEHOLDER_UNREADABLE = "🔒 This message can't be decrypted on this device";
    public static final String PLACEHOLDER_KEYCHANGED = "🔒 Security code changed — verify to read new messages";
    public static final String PLACEHOLDER_PLAINTEXT = "⚠️ Unencrypted message hidden";
    public static final String GENERIC_PREVIEW = "🔒 Encrypted message";
    public static final String GENERIC_PUSH = "New message";

    private static E2ee instance;
    private final Context ctx;
    private final String uid;
    private final E2eeStore store;
    private E2eeStore.Identity identity;
    private final Map<String, E2eeStore.Chat> chats = new HashMap<>();
    private final Map<String, Map<String, String[]>> plain = new HashMap<>();
    private final Map<String, Bundle> bundleCache = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private E2ee(Context c, String uid) {
        this.ctx = c.getApplicationContext();
        this.uid = uid;
        this.store = new E2eeStore(ctx, uid);
    }

    public static synchronized E2ee get(Context c, String uid) {
        if (instance == null || !instance.uid.equals(uid)) instance = new E2ee(c, uid);
        return instance;
    }

    // =================================================================== identity
    static final class Bundle {
        byte[] ik, sik, spk, sig; int spkId;
    }

    /** Creates the device identity on first use and publishes the public key bundle. */
    public synchronized void ensureIdentity() {
        try {
            if (identity == null) identity = store.loadIdentity();
            if (identity == null) {
                E2eeStore.Identity id = new E2eeStore.Identity();
                id.identity = Crypto.newDhKeyPair();
                id.spk = Crypto.newDhKeyPair();
                id.sig = Crypto.newSigKeyPair();
                id.spkId = 1;
                id.spkSig = Crypto.sign(id.sig.priv, id.spk.pub);
                store.saveIdentity(id);
                identity = id;
            }
            if (!identity.published) publishBundle();
        } catch (Exception e) {
            android.util.Log.e("E2EE", "identity setup failed: " + e.getClass().getSimpleName());
        }
    }

    private void publishBundle() {
        Map<String, Object> m = new HashMap<>();
        m.put("ik", Crypto.b64(identity.identity.pub));
        m.put("sik", Crypto.b64(identity.sig.pub));
        m.put("spk", Crypto.b64(identity.spk.pub));
        m.put("spk_sig", Crypto.b64(identity.spkSig));
        m.put("spk_id", identity.spkId);
        m.put("ts", System.currentTimeMillis());
        FirebaseDatabase.getInstance().getReference("e2ee_keys").child(uid).setValue(m)
            .addOnSuccessListener(v -> {
                synchronized (E2ee.this) {
                    identity.published = true;
                    try { store.saveIdentity(identity); } catch (Exception ignored) { }
                }
            });
    }

    public synchronized byte[] myIdentityPub() { ensureIdentity(); return identity != null ? identity.identity.pub : null; }

    // =================================================================== chat state
    private E2eeStore.Chat chat(String chatId) {
        E2eeStore.Chat c = chats.get(chatId);
        if (c == null) { c = store.loadChat(chatId); chats.put(chatId, c); }
        return c;
    }
    private Map<String, String[]> plainOf(String chatId) {
        Map<String, String[]> p = plain.get(chatId);
        if (p == null) { p = store.loadPlain(chatId); plain.put(chatId, p); }
        return p;
    }
    private void persist(String chatId) {
        try { store.saveChat(chatId, chat(chatId)); } catch (Exception e) { android.util.Log.e("E2EE", "save failed"); }
    }
    private void persistPlain(String chatId) {
        try { store.savePlain(chatId, plainOf(chatId)); } catch (Exception e) { android.util.Log.e("E2EE", "save plain failed"); }
    }

    public synchronized boolean isEncrypted(String chatId) { return chat(chatId).e2ee; }
    public synchronized boolean hasKeyChange(String chatId) { return !chat(chatId).pendingIkFp.isEmpty(); }
    public synchronized boolean isVerified(String chatId) { return chat(chatId).verified; }
    public synchronized String localPreview(String chatId) {
        E2eeStore.Chat c = chat(chatId);
        return c.e2ee ? (c.preview.isEmpty() ? GENERIC_PREVIEW : c.preview) : null;
    }
    public synchronized void setLocalPreview(String chatId, String text, long ts) {
        E2eeStore.Chat c = chat(chatId);
        if (ts >= c.previewTs) { c.preview = text == null ? "" : text; c.previewTs = ts; persist(chatId); }
    }

    // =================================================================== peer bundle
    public interface BundleCb { void done(boolean ok); }

    /** Fetches + verifies the peer's key bundle, applies trust-on-first-use / key-change detection. */
    public void refreshPeer(final String chatId, final String peerUid, final BundleCb cb) {
        FirebaseDatabase.getInstance().getReference("e2ee_keys").child(peerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    boolean ok = false;
                    try {
                        if (s.exists()) {
                            Bundle b = new Bundle();
                            b.ik = Crypto.unb64(s.child("ik").getValue(String.class));
                            b.sik = Crypto.unb64(s.child("sik").getValue(String.class));
                            b.spk = Crypto.unb64(s.child("spk").getValue(String.class));
                            b.sig = Crypto.unb64(s.child("spk_sig").getValue(String.class));
                            Long id = s.child("spk_id").getValue(Long.class);
                            b.spkId = id == null ? 1 : id.intValue();
                            if (b.ik.length == 32 && b.spk.length == 32 && Crypto.verify(b.sik, b.sig, b.spk)) {
                                synchronized (E2ee.this) {
                                    bundleCache.put(peerUid, b);
                                    noteIdentity(chatId, b.ik);
                                }
                                ok = true;
                            }
                        }
                    } catch (Exception ignored) { }
                    cb.done(ok);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { cb.done(false); }
            });
    }

    /** TOFU: remember the first identity key; flag any later change until the user accepts it. */
    private void noteIdentity(String chatId, byte[] ik) {
        E2eeStore.Chat c = chat(chatId);
        String fp = SafetyNumber.fingerprint(ik);
        if (c.peerIk == null) { c.peerIk = ik; c.peerIkFp = fp; persist(chatId); return; }
        if (!c.peerIkFp.equals(fp) && !fp.equals(c.pendingIkFp)) {
            c.pendingIk = ik; c.pendingIkFp = fp; persist(chatId);
        }
    }

    /** User accepted the new security code: start fresh sessions, verification state is reset. */
    public synchronized void acceptKeyChange(String chatId) {
        E2eeStore.Chat c = chat(chatId);
        if (c.pendingIk == null) return;
        c.peerIk = c.pendingIk; c.peerIkFp = c.pendingIkFp;
        c.pendingIk = null; c.pendingIkFp = "";
        c.sessions.clear(); c.activeSid = null; c.verified = false;
        persist(chatId);
    }

    public synchronized void setVerified(String chatId, boolean v) {
        E2eeStore.Chat c = chat(chatId);
        c.verified = v; persist(chatId);
    }

    public static final class Safety {
        public String number, qr; public String[] groups; public boolean verified, keyChanged;
    }

    public interface SafetyCb { void done(Safety s); }

    public void loadSafety(final String chatId, final String peerUid, final SafetyCb cb) {
        ensureIdentity();
        refreshPeer(chatId, peerUid, ok -> {
            synchronized (E2ee.this) {
                E2eeStore.Chat c = chat(chatId);
                if (identity == null || c.peerIk == null) { main.post(() -> cb.done(null)); return; }
                Safety s = new Safety();
                s.number = SafetyNumber.compute(identity.identity.pub, uid, c.peerIk, peerUid);
                s.groups = SafetyNumber.groups(s.number);
                s.qr = SafetyNumber.qrPayload(s.number);
                s.verified = c.verified; s.keyChanged = !c.pendingIkFp.isEmpty();
                main.post(() -> cb.done(s));
            }
        });
    }

    // =================================================================== outgoing
    public interface OutCb {
        void ready(Map<String, Object> toStore);       // store this map in the database
        void plainFallback();                          // peer has no keys yet and chat was never encrypted
        void error(String message);
    }

    private static final String[] SECRET_KEYS = {"text", "caption", "reply_preview", "reply_sender",
            "link_preview_url", "link_preview_title", "link_preview_desc", "link_preview_image", "link_preview_site"};

    /** Encrypts {@code msg} (full plaintext message map) for the peer. Callback runs on the main thread. */
    public void prepareOutgoing(final String chatId, final String peerUid, final String msgId,
                                final Map<String, Object> msg, final OutCb cb) {
        ensureIdentity();
        final boolean needFetch;
        synchronized (this) {
            E2eeStore.Chat c = chat(chatId);
            if (!c.pendingIkFp.isEmpty()) { cb.error("Security code changed. Open chat info → Verify encryption."); return; }
            needFetch = findSendSession(c) == null;
        }
        if (!needFetch) { finishOutgoing(chatId, peerUid, msgId, msg, cb); return; }
        refreshPeer(chatId, peerUid, ok -> {
            synchronized (E2ee.this) {
                E2eeStore.Chat c = chat(chatId);
                if (!c.pendingIkFp.isEmpty()) { cb.error("Security code changed. Open chat info → Verify encryption."); return; }
                Bundle b = bundleCache.get(peerUid);
                if (!ok || b == null) {
                    if (c.e2ee) cb.error("Can't reach " + "your contact's encryption keys right now. Message not sent.");
                    else cb.plainFallback();
                    return;
                }
                try {
                    if (findSendSession(c) == null) {
                        Ratchet r = Ratchet.initiate(identity.identity, b.ik, b.spk, b.spkId);
                        c.sessions.add(r); c.activeSid = r.sid;
                        if (!c.e2ee) { c.e2ee = true; c.e2eeSince = System.currentTimeMillis(); }
                        while (c.sessions.size() > 4) c.sessions.remove(0);
                        persist(chatId);
                    }
                } catch (Exception e) { cb.error("Encryption set-up failed"); return; }
            }
            finishOutgoing(chatId, peerUid, msgId, msg, cb);
        });
    }

    private Ratchet findSendSession(E2eeStore.Chat c) {
        if (c.activeSid != null)
            for (Ratchet r : c.sessions) if (java.util.Arrays.equals(r.sid, c.activeSid) && canSend(r)) return r;
        for (int i = c.sessions.size() - 1; i >= 0; i--) if (canSend(c.sessions.get(i))) return c.sessions.get(i);
        return null;
    }
    private static boolean canSend(Ratchet r) {
        try { return r.canSend(); } catch (Exception e) { return false; }
    }

    private synchronized void finishOutgoing(String chatId, String peerUid, String msgId,
                                             Map<String, Object> msg, OutCb cb) {
        try {
            E2eeStore.Chat c = chat(chatId);
            Ratchet r = findSendSession(c);
            if (r == null) { cb.error("No encrypted session"); return; }

            JSONObject p = new JSONObject();
            Map<String, Object> pub = new HashMap<>(msg);
            for (String k : SECRET_KEYS) {
                Object v = msg.get(k);
                if (v != null && !"".equals(v)) p.put(k, v.toString());
                if (pub.containsKey(k)) pub.put(k, "");
            }
            Object key = msg.get("e2ee_media_key");
            String type = String.valueOf(msg.get("type"));
            String url = "voice".equals(type) ? str(msg.get("voice_url")) : "image".equals(type) ? str(msg.get("image_url")) : "";
            if (!url.isEmpty()) { p.put("mu", url); p.put("mk", key != null ? key.toString() : ""); }
            pub.remove("e2ee_media_key"); pub.remove("local_uri");
            if (pub.containsKey("image_url")) pub.put("image_url", "");
            if (pub.containsKey("voice_url")) pub.put("voice_url", "");
            pub.put("text", "");

            byte[] pt = p.toString().getBytes(StandardCharsets.UTF_8);
            byte[] ct = r.encrypt(pt);
            pub.put("e2ee", 1);
            pub.put("ct", Crypto.b64(ct));
            if (r.awaitingFirstReply && r.x3dhIdentity != null) {
                pub.put("x_ik", Crypto.b64(r.x3dhIdentity));
                pub.put("x_ek", Crypto.b64(r.x3dhEphemeral));
                pub.put("x_spk", r.x3dhSpkId);
            }
            plainOf(chatId).put(msgId, new String[]{ctHash(pub.get("ct").toString()), p.toString()});
            trimPlain(chatId);
            persist(chatId); persistPlain(chatId);
            cb.ready(pub);
        } catch (Exception e) {
            cb.error("Encryption failed");
        }
    }

    // =================================================================== incoming
    /**
     * Fills {@code out} (the map the chat UI renders) from the raw database message.
     * Safe to call repeatedly: results are cached, since a ratchet message decrypts only once.
     * @return true if the message was an encrypted one.
     */
    public synchronized boolean decryptInto(String chatId, String peerUid, String msgId,
                                            Map<String, Object> raw, Map<String, Object> out) {
        String ctB64 = raw.get("ct") instanceof String ? (String) raw.get("ct") : null;
        E2eeStore.Chat c = chat(chatId);
        boolean fromPeer = peerUid.equals(raw.get("sender_uid"));

        if (ctB64 == null) {
            // plaintext in a conversation that is end-to-end encrypted: only OK for messages that predate it
            long ts = raw.get("timestamp") instanceof Long ? (Long) raw.get("timestamp") : 0L;
            if (c.e2ee && fromPeer && ts > c.e2eeSince) {
                out.put("text", PLACEHOLDER_PLAINTEXT); out.put("caption", ""); out.put("image_url", ""); out.put("voice_url", "");
            }
            return false;
        }

        Map<String, String[]> cache = plainOf(chatId);
        String h = ctHash(ctB64);
        String[] hit = cache.get(msgId);
        String json = (hit != null && hit[0].equals(h)) ? hit[1] : null;

        if (json == null && !fromPeer) {                           // my own message that I can't read (other device / reinstall)
            apply(out, null);
            return true;
        }
        if (json == null) {
            if (!c.pendingIkFp.isEmpty()) { apply(out, null); out.put("text", PLACEHOLDER_KEYCHANGED); return true; }
            try {
                byte[] ct = Crypto.unb64(ctB64);
                byte[] sid = Ratchet.sidOf(ct);
                Ratchet s = null;
                for (Ratchet r : c.sessions) if (java.util.Arrays.equals(r.sid, sid)) { s = r; break; }
                boolean created = false;
                if (s == null) {
                    String xik = (String) raw.get("x_ik"), xek = (String) raw.get("x_ek");
                    Object xs = raw.get("x_spk");
                    if (xik == null || xek == null || identity == null) throw new IllegalStateException("no session");
                    byte[] aIk = Crypto.unb64(xik), aEk = Crypto.unb64(xek);
                    if (c.peerIk != null && !c.peerIkFp.equals(SafetyNumber.fingerprint(aIk))) {
                        c.pendingIk = aIk; c.pendingIkFp = SafetyNumber.fingerprint(aIk); persist(chatId);
                        apply(out, null); out.put("text", PLACEHOLDER_KEYCHANGED); return true;
                    }
                    if (c.peerIk == null) { c.peerIk = aIk; c.peerIkFp = SafetyNumber.fingerprint(aIk); }
                    int spkId = xs instanceof Long ? ((Long) xs).intValue() : 1;
                    if (spkId != identity.spkId) throw new IllegalStateException("unknown pre-key");
                    s = Ratchet.accept(identity.identity, identity.spk, identity.spkId, aIk, aEk);
                    created = true;
                }
                byte[] pt = s.decrypt(ct);
                if (created) {
                    c.sessions.add(s);
                    while (c.sessions.size() > 4) c.sessions.remove(0);
                }
                c.activeSid = s.sid;
                if (!c.e2ee) { c.e2ee = true; c.e2eeSince = Math.max(0, System.currentTimeMillis() - 1); }
                json = new String(pt, StandardCharsets.UTF_8);
                cache.put(msgId, new String[]{h, json});
                trimPlain(chatId);
                persist(chatId); persistPlain(chatId);
            } catch (Exception e) {
                apply(out, null);
                return true;
            }
        }
        apply(out, json);
        return true;
    }

    private void apply(Map<String, Object> out, String json) {
        for (String k : SECRET_KEYS) out.put(k, "");
        out.put("image_url", ""); out.put("voice_url", "");
        out.put("e2ee", Boolean.TRUE);
        if (json == null) { out.put("text", PLACEHOLDER_UNREADABLE); out.put("e2ee_unreadable", Boolean.TRUE); return; }
        try {
            JSONObject p = new JSONObject(json);
            for (String k : SECRET_KEYS) if (p.has(k)) out.put(k, p.getString(k));
            if (p.has("mu")) { out.put("e2ee_media_url", p.getString("mu")); out.put("e2ee_media_key", p.getString("mk")); }
        } catch (JSONException e) { out.put("text", PLACEHOLDER_UNREADABLE); }
    }

    private void trimPlain(String chatId) {
        Map<String, String[]> m = plainOf(chatId);
        while (m.size() > 5000) { java.util.Iterator<String> it = m.keySet().iterator(); it.next(); it.remove(); }
    }

    private static String ctHash(String ct) { return Crypto.b64(java.util.Arrays.copyOf(Crypto.sha256(ct.getBytes(StandardCharsets.UTF_8)), 8)); }
    private static String str(Object o) { return o == null ? "" : o.toString(); }

    // =================================================================== media
    public static final class SealedMedia { public byte[] data; public String keyB64; }

    /** Encrypts a file's bytes with a fresh random key; the key travels only inside the encrypted message. */
    public static SealedMedia sealMedia(byte[] plainBytes) throws java.security.GeneralSecurityException {
        byte[] key = Crypto.random(32);
        SealedMedia m = new SealedMedia();
        m.data = Crypto.seal(key, plainBytes, "minigram-media".getBytes(StandardCharsets.UTF_8));
        m.keyB64 = Crypto.b64(key);
        return m;
    }

    public static byte[] openMedia(byte[] boxed, String keyB64) throws java.security.GeneralSecurityException {
        return Crypto.open(Crypto.unb64(keyB64), boxed, "minigram-media".getBytes(StandardCharsets.UTF_8));
    }

    /** Wipes keys and caches for this account (used by "delete account" / explicit reset). */
    public synchronized void wipe() {
        store.wipeAll();
        chats.clear(); plain.clear(); bundleCache.clear(); identity = null;
    }
}
