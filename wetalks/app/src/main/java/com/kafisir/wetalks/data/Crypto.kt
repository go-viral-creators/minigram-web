package com.kafisir.wetalks.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.subtle.AesGcmJce
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * End-to-end encryption for direct messages and groups.
 *  - every user owns an X25519 key pair; the private key never leaves the phone (wrapped by an Android Keystore key)
 *  - DM key = HKDF(X25519(myPriv, peerPub)); group messages use a random group key that admins wrap per member
 *  - payloads are sealed with AES-256-GCM
 */
object Crypto {
    private const val ALIAS = "wetalks_wrap_v1"
    private val rnd = SecureRandom()

    class Pair(val priv: ByteArray, val pub: ByteArray)

    @Volatile private var pair: Pair? = null

    fun init() { AeadConfig.register() }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    private fun wrap(data: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return b64(c.iv + c.doFinal(data))
    }

    private fun unwrap(s: String): ByteArray {
        val raw = unb64(s)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, raw, 0, 12))
        return c.doFinal(raw, 12, raw.size - 12)
    }

    /** Wraps a small secret with this phone's hardware-backed key (for keys kept on this phone only). */
    fun wrapForDevice(data: ByteArray): String = wrap(data)
    fun unwrapForDevice(s: String): ByteArray = unwrap(s)

    /** Loads (or creates) this device's key pair. */
    @Synchronized fun keys(ctx: Context): Pair {
        pair?.let { return it }
        val sp = ctx.getSharedPreferences("wt_keys", Context.MODE_PRIVATE)
        val w = sp.getString("priv", null)
        val p = if (w != null) {
            try { val priv = unwrap(w); Pair(priv, X25519.publicFromPrivate(priv)) } catch (e: Exception) { throw IllegalStateException("The encryption key on this phone could not be unlocked right now", e) }
        } else null
        val result = p ?: run {
            val priv = X25519.generatePrivateKey()
            sp.edit().putString("priv", wrap(priv)).apply()
            Pair(priv, X25519.publicFromPrivate(priv))
        }
        pair = result
        return result
    }

    /** Key derived from a passphrase for the encrypted key backup. */
    fun passKey(pass: String, salt: ByteArray, iter: Int): ByteArray =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(javax.crypto.spec.PBEKeySpec(pass.toCharArray(), salt, iter, 256)).encoded

    /** Replaces this phone's key pair with a restored private key. */
    @Synchronized fun install(ctx: Context, priv: ByteArray) {
        ctx.getSharedPreferences("wt_keys", Context.MODE_PRIVATE).edit().putString("priv", wrap(priv)).commit()
        pair = Pair(priv, X25519.publicFromPrivate(priv))
    }

    fun clear(ctx: Context) { pair = null; ctx.getSharedPreferences("wt_keys", Context.MODE_PRIVATE).edit().clear().apply() }

    fun shared(myPriv: ByteArray, myPub: ByteArray, peerPub: ByteArray): ByteArray {
        val secret = X25519.computeSharedSecret(myPriv, peerPub)
        val a = if (compare(myPub, peerPub) <= 0) myPub + peerPub else peerPub + myPub
        return Hkdf.computeHkdf("HMACSHA256", secret, a, "wetalks-e2ee-v1".toByteArray(), 32)
    }

    fun randomKey(): ByteArray = ByteArray(32).also { rnd.nextBytes(it) }
    fun sealBytes(key: ByteArray, data: ByteArray): ByteArray = AesGcmJce(key).encrypt(data, null)
    fun openBytes(key: ByteArray, data: ByteArray): ByteArray = AesGcmJce(key).decrypt(data, null)
    fun seal(key: ByteArray, data: ByteArray): String = b64(sealBytes(key, data))
    fun open(key: ByteArray, s: String): ByteArray = openBytes(key, unb64(s))
    fun sealText(key: ByteArray, t: String) = seal(key, t.toByteArray(Charsets.UTF_8))
    fun openText(key: ByteArray, s: String) = String(open(key, s), Charsets.UTF_8)

    /** 60-digit safety number, identical on both phones. */
    fun safetyNumber(a: ByteArray, b: ByteArray): String {
        val both = if (compare(a, b) <= 0) a + b else b + a
        val h = MessageDigest.getInstance("SHA-256").digest(both)
        val sb = StringBuilder()
        for (i in 0 until 12) {
            val v = ((h[i * 2].toInt() and 0xff) shl 8) or (h[i * 2 + 1].toInt() and 0xff)
            sb.append((v % 100000).toString().padStart(5, '0'))
            if (i < 11) sb.append(if (i % 4 == 3) "\n" else " ")
        }
        return sb.toString()
    }

    private fun compare(x: ByteArray, y: ByteArray): Int {
        for (i in 0 until minOf(x.size, y.size)) { val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff); if (d != 0) return d }
        return x.size - y.size
    }

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
