package com.kafisir.wetalks

import com.google.crypto.tink.subtle.X25519
import com.kafisir.wetalks.data.Crypto
import org.junit.Assert.*
import org.junit.Test

class CryptoTest {
    private fun pair() = X25519.generatePrivateKey().let { it to X25519.publicFromPrivate(it) }

    @Test fun bothSidesDeriveTheSameKey() {
        val (ap, apub) = pair(); val (bp, bpub) = pair()
        assertArrayEquals(Crypto.shared(ap, apub, bpub), Crypto.shared(bp, bpub, apub))
    }

    @Test fun otherPeopleGetADifferentKey() {
        val (ap, apub) = pair(); val (_, bpub) = pair(); val (_, cpub) = pair()
        assertFalse(Crypto.shared(ap, apub, bpub).contentEquals(Crypto.shared(ap, apub, cpub)))
    }

    @Test fun sealAndOpenRoundTrip() {
        val k = Crypto.randomKey(); val msg = "Kal 7 baje milte hain? 😀".toByteArray()
        val ct = Crypto.sealBytes(k, msg)
        assertFalse(ct.contentEquals(msg))
        assertArrayEquals(msg, Crypto.openBytes(k, ct))
    }

    @Test fun tamperedOrWrongKeyFails() {
        val k = Crypto.randomKey(); val ct = Crypto.sealBytes(k, "hello".toByteArray())
        val bad = ct.clone(); bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { Crypto.openBytes(k, bad) }
        assertThrows(Exception::class.java) { Crypto.openBytes(Crypto.randomKey(), ct) }
    }

    @Test fun sealingTwiceGivesDifferentCiphertext() {
        val k = Crypto.randomKey(); val m = "same".toByteArray()
        assertFalse(Crypto.sealBytes(k, m).contentEquals(Crypto.sealBytes(k, m)))
    }

    @Test fun safetyNumberMatchesOnBothPhones() {
        val (_, a) = pair(); val (_, b) = pair()
        assertEquals(Crypto.safetyNumber(a, b), Crypto.safetyNumber(b, a))
        assertEquals(12 * 5 + 11, Crypto.safetyNumber(a, b).length)
    }

    // linking another phone: the old phone seals its private key for a one-time key of the new phone
    @Test fun linkedPhoneReceivesTheSameKeyAndNobodyElseCan() {
        val (oldPriv, oldPub) = pair()
        val e = Crypto.ephemeral()
        val sealed = Crypto.sealBytes(Crypto.shared(oldPriv, oldPub, e.pub), oldPriv)                    // old phone
        val got = Crypto.openBytes(Crypto.shared(e.priv, e.pub, oldPub), sealed)                          // new phone
        assertArrayEquals(oldPriv, got)
        assertArrayEquals(oldPub, Crypto.pubOf(got))                                                     // same identity => same public key
        val (evilPriv, evilPub) = pair()
        try { Crypto.openBytes(Crypto.shared(evilPriv, evilPub, oldPub), sealed); fail("a third party opened the key") } catch (_: Exception) {}
    }
}
