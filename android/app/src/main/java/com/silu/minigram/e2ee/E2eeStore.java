package com.silu.minigram.e2ee;

import android.content.Context;

import com.silu.minigram.SecureVault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-device storage for everything secret. Every file is AES-256-GCM encrypted with a key that
 * lives only in the Android Keystore (see {@link SecureVault}); copying the app's files off the
 * phone yields nothing usable. Never uploaded, never backed up (allowBackup=false).
 */
final class E2eeStore {
    private static final String ALIAS = "minigram_e2ee_store";
    private final File dir;

    E2eeStore(Context c, String uid) {
        dir = new File(new File(c.getFilesDir(), "e2ee"), uid.replaceAll("[^A-Za-z0-9_\\-]", "_"));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    // ------------------------------------------------------------ raw encrypted files
    synchronized byte[] read(String name) {
        File f = new File(dir, name);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] all = new byte[(int) f.length()];
            int off = 0, n;
            while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
            return SecureVault.decrypt(ALIAS, all);
        } catch (Exception e) {
            return null;                        // unreadable (e.g. keystore key lost) -> treated as absent
        }
    }

    synchronized void write(String name, byte[] plain) throws IOException {
        try {
            byte[] enc = SecureVault.encrypt(ALIAS, plain);
            File tmp = new File(dir, name + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(enc); out.getFD().sync(); }
            if (!tmp.renameTo(new File(dir, name))) throw new IOException("rename failed");
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException(e); }
    }

    synchronized void delete(String name) { //noinspection ResultOfMethodCallIgnored
        new File(dir, name).delete(); }

    synchronized void wipeAll() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
        SecureVault.delete(ALIAS);
    }

    // ------------------------------------------------------------ identity
    static final class Identity {
        Crypto.DhKeyPair identity, spk;
        Crypto.SigKeyPair sig;
        int spkId;
        byte[] spkSig;
        boolean published;
    }

    Identity loadIdentity() {
        byte[] b = read("identity.bin");
        if (b == null) return null;
        try {
            DataInputStream i = new DataInputStream(new ByteArrayInputStream(b));
            Identity id = new Identity();
            id.identity = dh(i); id.spk = dh(i);
            byte[] sp = bytes(i), sq = bytes(i);
            id.sig = sig(sp, sq);
            id.spkId = i.readInt(); id.spkSig = bytes(i); id.published = i.readBoolean();
            return id;
        } catch (Exception e) { return null; }
    }

    void saveIdentity(Identity id) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        w(o, id.identity.priv); w(o, id.identity.pub); w(o, id.spk.priv); w(o, id.spk.pub);
        w(o, id.sig.priv); w(o, id.sig.pub);
        o.writeInt(id.spkId); w(o, id.spkSig); o.writeBoolean(id.published);
        write("identity.bin", bo.toByteArray());
    }

    // ------------------------------------------------------------ per-chat state
    static final class Chat {
        final List<Ratchet> sessions = new ArrayList<>();
        byte[] activeSid;
        String peerIkFp = "";         // TOFU fingerprint of the peer's identity key
        byte[] peerIk;
        boolean verified;
        boolean e2ee;                 // once true, plaintext from the peer is never accepted
        long e2eeSince;
        String pendingIkFp = "";      // set when the peer's key changed and the user has not accepted yet
        byte[] pendingIk;
        String preview = "";
        long previewTs;
    }

    Chat loadChat(String chatId) {
        Chat c = new Chat();
        byte[] b = read("chat_" + safe(chatId) + ".bin");
        if (b == null) return c;
        try {
            DataInputStream i = new DataInputStream(new ByteArrayInputStream(b));
            if (i.readByte() != 1) return c;
            c.activeSid = bytes(i); c.peerIkFp = i.readUTF(); c.peerIk = bytes(i);
            c.verified = i.readBoolean(); c.e2ee = i.readBoolean(); c.e2eeSince = i.readLong();
            c.pendingIkFp = i.readUTF(); c.pendingIk = bytes(i); c.preview = i.readUTF(); c.previewTs = i.readLong();
            int n = i.readInt();
            for (int k = 0; k < n; k++) c.sessions.add(Ratchet.fromBytes(bytes(i)));
        } catch (Exception e) { return new Chat(); }
        return c;
    }

    void saveChat(String chatId, Chat c) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.writeByte(1);
        w(o, c.activeSid); o.writeUTF(c.peerIkFp); w(o, c.peerIk);
        o.writeBoolean(c.verified); o.writeBoolean(c.e2ee); o.writeLong(c.e2eeSince);
        o.writeUTF(c.pendingIkFp); w(o, c.pendingIk); o.writeUTF(c.preview); o.writeLong(c.previewTs);
        o.writeInt(c.sessions.size());
        for (Ratchet r : c.sessions) w(o, r.toBytes());
        write("chat_" + safe(chatId) + ".bin", bo.toByteArray());
    }

    // ------------------------------------------------------------ plaintext cache (msgId -> payload JSON)
    /** Decrypted payloads: a ratchet message can be decrypted exactly once, so we keep the result. */
    Map<String, String[]> loadPlain(String chatId) {
        Map<String, String[]> m = new LinkedHashMap<>();
        byte[] b = read("plain_" + safe(chatId) + ".bin");
        if (b == null) return m;
        try {
            DataInputStream i = new DataInputStream(new ByteArrayInputStream(b));
            int n = i.readInt();
            for (int k = 0; k < n; k++) m.put(i.readUTF(), new String[]{i.readUTF(), i.readUTF()});
        } catch (IOException ignored) { }
        return m;
    }

    void savePlain(String chatId, Map<String, String[]> m) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.writeInt(m.size());
        for (Map.Entry<String, String[]> e : m.entrySet()) {
            o.writeUTF(e.getKey()); o.writeUTF(e.getValue()[0]); o.writeUTF(e.getValue()[1]);
        }
        write("plain_" + safe(chatId) + ".bin", bo.toByteArray());
    }

    // ------------------------------------------------------------ helpers
    private static String safe(String s) { return s.replaceAll("[^A-Za-z0-9_\\-]", "_"); }

    private static void w(DataOutputStream o, byte[] v) throws IOException {
        if (v == null) { o.writeInt(-1); return; }
        o.writeInt(v.length); o.write(v);
    }
    private static byte[] bytes(DataInputStream i) throws IOException {
        int n = i.readInt();
        if (n < 0) return null;
        byte[] v = new byte[n]; i.readFully(v); return v;
    }
    private static Crypto.DhKeyPair dh(DataInputStream i) throws IOException {
        byte[] priv = bytes(i), pub = bytes(i);
        return Crypto.dhFrom(priv, pub);
    }
    private static Crypto.SigKeyPair sig(byte[] priv, byte[] pub) { return Crypto.sigFrom(priv, pub); }
}
