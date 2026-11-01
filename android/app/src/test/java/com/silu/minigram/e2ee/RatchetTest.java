package com.silu.minigram.e2ee;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

public class RatchetTest {
    Crypto.DhKeyPair aliceId, bobId, bobSpk;
    Ratchet alice, bob;

    @Before public void setUp() throws Exception {
        aliceId = Crypto.newDhKeyPair();
        bobId = Crypto.newDhKeyPair();
        bobSpk = Crypto.newDhKeyPair();
        alice = Ratchet.initiate(aliceId, bobId.pub, bobSpk.pub, 1);
        bob = null;
    }

    private Ratchet bobAccepts() throws Exception {
        return Ratchet.accept(bobId, bobSpk, 1, alice.x3dhIdentity, alice.x3dhEphemeral);
    }
    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String s(byte[] x) { return new String(x, StandardCharsets.UTF_8); }

    @Test public void firstMessageAndReply() throws Exception {
        byte[] m1 = alice.encrypt(b("hello bob"));
        bob = bobAccepts();
        assertEquals("hello bob", s(bob.decrypt(m1)));
        byte[] r1 = bob.encrypt(b("hi alice"));
        assertEquals("hi alice", s(alice.decrypt(r1)));
        assertFalse(alice.awaitingFirstReply);
        // long ping-pong keeps working across many DH ratchet steps
        for (int i = 0; i < 20; i++) {
            assertEquals("a" + i, s(bob.decrypt(alice.encrypt(b("a" + i)))));
            assertEquals("b" + i, s(alice.decrypt(bob.encrypt(b("b" + i)))));
        }
    }

    @Test public void ciphertextHidesPlaintextAndIsUnique() throws Exception {
        byte[] m1 = alice.encrypt(b("secret secret secret"));
        byte[] m2 = alice.encrypt(b("secret secret secret"));
        assertFalse(new String(m1, StandardCharsets.ISO_8859_1).contains("secret"));
        assertFalse(java.util.Arrays.equals(m1, m2));
    }

    @Test public void outOfOrderDelivery() throws Exception {
        List<byte[]> msgs = new ArrayList<>();
        for (int i = 0; i < 5; i++) msgs.add(alice.encrypt(b("m" + i)));
        bob = bobAccepts();
        assertEquals("m3", s(bob.decrypt(msgs.get(3))));
        assertEquals("m0", s(bob.decrypt(msgs.get(0))));
        assertEquals("m4", s(bob.decrypt(msgs.get(4))));
        assertEquals("m1", s(bob.decrypt(msgs.get(1))));
        assertEquals("m2", s(bob.decrypt(msgs.get(2))));
    }

    @Test public void outOfOrderAcrossRatchetSteps() throws Exception {
        byte[] a0 = alice.encrypt(b("a0"));
        bob = bobAccepts();
        bob.decrypt(a0);
        byte[] b0 = bob.encrypt(b("b0")), b1 = bob.encrypt(b("b1"));
        assertEquals("b1", s(alice.decrypt(b1)));          // b0 still in flight
        byte[] a1 = alice.encrypt(b("a1"));
        assertEquals("a1", s(bob.decrypt(a1)));
        assertEquals("b0", s(alice.decrypt(b0)));          // late message from the previous chain
    }

    @Test public void tamperedMessageRejectedAndStateSurvives() throws Exception {
        byte[] m = alice.encrypt(b("hello"));
        bob = bobAccepts();
        byte[] bad = m.clone();
        bad[bad.length - 1] ^= 1;
        try { bob.decrypt(bad); fail("tamper accepted"); } catch (GeneralSecurityException expected) {}
        byte[] badHeader = m.clone();
        badHeader[20] ^= 1;
        try { bob.decrypt(badHeader); fail("header tamper accepted"); } catch (GeneralSecurityException expected) {}
        assertEquals("hello", s(bob.decrypt(m)));          // genuine message still decrypts
    }

    @Test public void replayRejected() throws Exception {
        byte[] m = alice.encrypt(b("once"));
        bob = bobAccepts();
        assertEquals("once", s(bob.decrypt(m)));
        try { bob.decrypt(m); fail("replay accepted"); } catch (GeneralSecurityException expected) {}
    }

    @Test public void wrongIdentityCannotDecrypt() throws Exception {
        byte[] m = alice.encrypt(b("for bob only"));
        Crypto.DhKeyPair mallory = Crypto.newDhKeyPair();
        Ratchet evil = Ratchet.accept(mallory, bobSpk, 1, alice.x3dhIdentity, alice.x3dhEphemeral);
        try { evil.decrypt(m); fail("wrong key decrypted"); } catch (GeneralSecurityException expected) {}
    }

    @Test public void tooManySkippedRejected() throws Exception {
        byte[] last = null;
        for (int i = 0; i < Ratchet.MAX_SKIP + 5; i++) last = alice.encrypt(b("x"));
        bob = bobAccepts();
        try { bob.decrypt(last); fail("accepted huge gap"); } catch (GeneralSecurityException expected) {}
    }

    @Test public void stateSurvivesSerialisation() throws Exception {
        byte[] m1 = alice.encrypt(b("one"));
        bob = bobAccepts();
        bob.decrypt(m1);
        bob = Ratchet.fromBytes(bob.toBytes());
        alice = Ratchet.fromBytes(alice.toBytes());
        assertEquals("two", s(bob.decrypt(alice.encrypt(b("two")))));
        assertEquals("three", s(alice.decrypt(bob.encrypt(b("three")))));
    }

    @Test public void forwardSecrecyOldKeysDoNotOpenNewMessages() throws Exception {
        bob = bobAccepts();
        bob.decrypt(alice.encrypt(b("warmup")));
        alice.decrypt(bob.encrypt(b("warmup2")));
        byte[] snapshot = bob.toBytes();                       // attacker steals state NOW
        for (int i = 0; i < 3; i++) {                          // conversation continues through ratchet steps
            alice.decrypt(bob.encrypt(b("x" + i)));
            bob.decrypt(alice.encrypt(b("y" + i)));
        }
        byte[] later = alice.encrypt(b("after compromise"));
        Ratchet stolen = Ratchet.fromBytes(snapshot);
        try { stolen.decrypt(later); fail("old state decrypted later message"); } catch (GeneralSecurityException expected) {}
    }

    @Test public void signedPrekeyVerifies() throws Exception {
        Crypto.SigKeyPair sig = Crypto.newSigKeyPair();
        byte[] signature = Crypto.sign(sig.priv, bobSpk.pub);
        assertTrue(Crypto.verify(sig.pub, signature, bobSpk.pub));
        Crypto.DhKeyPair other = Crypto.newDhKeyPair();
        assertFalse(Crypto.verify(sig.pub, signature, other.pub));
    }

    @Test public void safetyNumberIsSymmetricAndKeyDependent() throws Exception {
        String n1 = SafetyNumber.compute(aliceId.pub, "alice", bobId.pub, "bob");
        String n2 = SafetyNumber.compute(bobId.pub, "bob", aliceId.pub, "alice");
        assertEquals(60, n1.length());
        assertEquals(n1, n2);
        assertTrue(SafetyNumber.matchesQr(SafetyNumber.qrPayload(n1), n2));
        String changed = SafetyNumber.compute(aliceId.pub, "alice", Crypto.newDhKeyPair().pub, "bob");
        assertNotEquals(n1, changed);
        assertEquals(12, SafetyNumber.groups(n1).length);
    }

    @Test public void mediaSealRoundTrip() throws Exception {
        byte[] key = Crypto.random(32), data = Crypto.random(100_000);
        byte[] boxed = Crypto.seal(key, data, b("media"));
        assertArrayEquals(data, Crypto.open(key, boxed, b("media")));
        try { Crypto.open(key, boxed, b("other")); fail(); } catch (GeneralSecurityException expected) {}
    }
}
