package com.kafisir.wetalks.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Why a backup file could not be opened. Each kind has a message that can be shown to the person as it is. */
class BackupException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind { WRONG_PASSPHRASE, NEEDS_PASSPHRASE, CORRUPT, UNSUPPORTED, WRONG_ACCOUNT }
}

/** The AES-256 key of an encrypted backup together with the PBKDF2 parameters it came from (they are written into the file header). */
class BackupKey(val key: ByteArray, val salt: ByteArray, val iter: Int) {
    companion object {
        /** Derives the key with [Crypto.passKey] (PBKDF2-HMAC-SHA256), the same function the account key backup uses. Slow on purpose: call off the main thread. */
        fun derive(pass: String, salt: ByteArray = BackupFormat.randomBytes(16), iter: Int = BackupFormat.ITER): BackupKey {
            require(iter >= BackupFormat.MIN_ITER) { "too few PBKDF2 iterations" }
            return BackupKey(Crypto.passKey(pass, salt, iter), salt, iter)
        }
    }
}

/**
 * The backup container (version 1). A backup is ONE file:
 *
 *     "WTBK" | version(1 byte) | flags(1 byte, bit0 = encrypted) | [encrypted: headerLen(2 bytes, big endian) | header JSON] | payload
 *
 * The payload is a standard ZIP stream. Encrypted: the payload is cut into 64 KiB chunks, each sealed with AES-256-GCM
 * (`u32 length | ciphertext+tag`), so a 500 MB backup is written and read with a constant amount of memory.
 *  - the key comes from a passphrase with PBKDF2-HMAC-SHA256 (>= 600 000 iterations, random 16 byte salt); salt and iteration count are in the header
 *  - nonce = 8 random bytes (per file, in the header) + 4 byte chunk counter
 *  - the associated data of every chunk = the whole preamble + chunk counter + "is last" flag, so chunks cannot be reordered, dropped, truncated or moved to another file
 *  - chunk 0 is a fixed marker: if it does not open, the passphrase is wrong (all later chunks failing means the file is damaged)
 *
 * ZIP entries: `chats/<sha256(chatId)>.jsonl` (one decrypted message per line), `media/<sha256(mediaId)>`, `settings.json` and, always last, `manifest.json`
 * with the size and SHA-256 of every other entry. Restore verifies all of it.
 */
object BackupFormat {
    val MAGIC = byteArrayOf('W'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    const val VERSION = 1
    const val ITER = 600_000
    const val MIN_ITER = 600_000
    const val MAX_ITER = 5_000_000
    const val CHUNK = 64 * 1024
    const val MAX_ENTRY = 4L shl 30            // one entry larger than this is refused (zip bomb guard)
    const val MAX_ENTRIES = 300_000
    const val MAX_MANIFEST = 32 shl 20
    const val EXT = ".wtbk"
    private val VERIFY = "WTBK-VERIFY-1".toByteArray()
    private val rnd = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun sha256Hex(s: String) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))
    fun chatEntry(chatId: String) = "chats/" + sha256Hex(chatId).take(32) + ".jsonl"
    fun mediaEntry(mediaId: String) = "media/" + sha256Hex(mediaId).take(32)
    /** The account is recorded as a hash, so a backup of one account is never restored into another. */
    fun accountTag(uid: String) = sha256Hex("wetalks-backup-account:$uid").take(32)

    private val NAME = Regex("^(chats/[0-9a-f]{32}\\.jsonl|media/[0-9a-f]{32}|settings\\.json|manifest\\.json)$")
    fun validEntryName(n: String) = NAME.matches(n)

    class Header(val encrypted: Boolean, val iter: Int, val salt: ByteArray, internal val nonceBase: ByteArray, internal val chunk: Int, internal val preamble: ByteArray)

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be64(v: Long) = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }

    /** Writes the preamble (and for an encrypted backup the header) to [out]; returns the stream the ZIP payload is written to. Closing it finishes the file. */
    fun open(out: OutputStream, key: BackupKey?): OutputStream {
        val pre = ByteArrayOutputStream()
        pre.write(MAGIC); pre.write(VERSION); pre.write(if (key != null) 1 else 0)
        if (key == null) { out.write(pre.toByteArray()); return out }
        val np = randomBytes(8)
        val hj = JSONObject().put("kdf", "PBKDF2-HMAC-SHA256").put("iter", key.iter).put("salt", b64(key.salt)).put("cipher", "AES-256-GCM").put("chunk", CHUNK).put("np", b64(np)).toString().toByteArray()
        require(hj.size < 65535)
        pre.write(hj.size ushr 8); pre.write(hj.size and 0xff); pre.write(hj)
        val preamble = pre.toByteArray()
        out.write(preamble)
        return SealOut(out, SecretKeySpec(key.key, "AES"), np, preamble, CHUNK)
    }

    /** Reads the preamble. Throws [BackupException] when this is not a backup file or a newer version of the format. */
    fun readHeader(inp: InputStream): Header {
        val m = ByteArray(6)
        try { readFully(inp, m) } catch (e: EOFException) { throw BackupException(BackupException.Kind.CORRUPT, "This file is not a WeTalks backup.") }
        for (i in 0 until 4) if (m[i] != MAGIC[i]) throw BackupException(BackupException.Kind.CORRUPT, "This file is not a WeTalks backup.")
        if (m[4].toInt() != VERSION) throw BackupException(BackupException.Kind.UNSUPPORTED, "This backup was made by a newer version of WeTalks. Update the app to open it.")
        val flags = m[5].toInt()
        if (flags and 1 == 0) return Header(false, 0, ByteArray(0), ByteArray(0), 0, m)
        try {
            val l = ByteArray(2); readFully(inp, l)
            val len = ((l[0].toInt() and 0xff) shl 8) or (l[1].toInt() and 0xff)
            if (len < 20 || len > 4096) throw BackupException(BackupException.Kind.CORRUPT, "The backup header is damaged.")
            val hb = ByteArray(len); readFully(inp, hb)
            val j = JSONObject(String(hb, Charsets.UTF_8))
            if (j.optString("kdf") != "PBKDF2-HMAC-SHA256" || j.optString("cipher") != "AES-256-GCM") throw BackupException(BackupException.Kind.UNSUPPORTED, "This backup uses a method this version cannot open.")
            val iter = j.getInt("iter"); val chunk = j.getInt("chunk")
            if (iter < MIN_ITER || iter > MAX_ITER) throw BackupException(BackupException.Kind.CORRUPT, "The backup header is damaged.")
            if (chunk < 4096 || chunk > (1 shl 20)) throw BackupException(BackupException.Kind.CORRUPT, "The backup header is damaged.")
            val salt = unb64(j.getString("salt")); val np = unb64(j.getString("np"))
            if (salt.size < 16 || np.size != 8) throw BackupException(BackupException.Kind.CORRUPT, "The backup header is damaged.")
            return Header(true, iter, salt, np, chunk, m + l + hb)
        } catch (e: BackupException) { throw e }
        catch (e: Exception) { throw BackupException(BackupException.Kind.CORRUPT, "The backup header is damaged.") }
    }

    /** The decrypted (or, for a plain backup, the unchanged) payload stream. Throws WRONG_PASSPHRASE as soon as the first chunk does not open. */
    fun payload(inp: InputStream, h: Header, key: BackupKey?): InputStream {
        if (!h.encrypted) return inp
        if (key == null) throw BackupException(BackupException.Kind.NEEDS_PASSPHRASE, "This backup is protected with a passphrase.")
        if (key.iter != h.iter || !key.salt.contentEquals(h.salt)) throw BackupException(BackupException.Kind.WRONG_PASSPHRASE, "The passphrase does not match this backup.")
        return OpenIn(inp, SecretKeySpec(key.key, "AES"), h.nonceBase, h.preamble, h.chunk)
    }

    /** Key for a given header and passphrase (slow: PBKDF2). */
    fun keyFor(h: Header, pass: String): BackupKey = BackupKey.derive(pass, h.salt, h.iter)

    private fun nonce(base: ByteArray, idx: Long): ByteArray = base + be32(idx.toInt())
    private fun aad(pre: ByteArray, idx: Long, last: Boolean) = pre + be64(idx) + byteArrayOf(if (last) 1 else 0)
    private fun cipher(mode: Int, key: SecretKeySpec, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(128, nonce)); updateAAD(aad) }

    private class SealOut(val out: OutputStream, val key: SecretKeySpec, val np: ByteArray, val pre: ByteArray, val chunk: Int) : OutputStream() {
        private val buf = ByteArray(chunk); private var n = 0; private var idx = 0L; private var closed = false
        init { emit(VERIFY, VERIFY.size, false) }
        private fun emit(data: ByteArray, len: Int, last: Boolean) {
            if (idx >= 0xFFFF_FFFFL) throw IOException("backup too large")
            val ct = cipher(Cipher.ENCRYPT_MODE, key, nonce(np, idx), aad(pre, idx, last)).doFinal(data, 0, len)
            out.write(be32(ct.size)); out.write(ct); idx++
        }
        override fun write(b: Int) { val one = ByteArray(1); one[0] = b.toByte(); write(one, 0, 1) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var l = len
            while (l > 0) {
                if (n == chunk) { emit(buf, n, false); n = 0 }
                val c = minOf(l, chunk - n); System.arraycopy(b, o, buf, n, c); n += c; o += c; l -= c
            }
        }
        override fun flush() { out.flush() }
        override fun close() { if (closed) return; closed = true; emit(buf, n, true); out.flush(); out.close() }
    }

    private class OpenIn(val inp: InputStream, val key: SecretKeySpec, val np: ByteArray, val pre: ByteArray, val chunk: Int) : InputStream() {
        private var idx = 0L; private var cur = ByteArray(0); private var pos = 0; private var done = false
        private var nextLen = -2        // -2 = not read yet, -1 = end of file
        init {
            val first = rawChunk()
            val plain = try { cipher(Cipher.DECRYPT_MODE, key, nonce(np, 0), aad(pre, 0, false)).doFinal(first) } catch (e: Exception) { throw BackupException(BackupException.Kind.WRONG_PASSPHRASE, "The passphrase is wrong.") }
            if (!plain.contentEquals(VERIFY)) throw BackupException(BackupException.Kind.WRONG_PASSPHRASE, "The passphrase is wrong.")
            idx = 1
        }
        private fun readLen(): Int {
            val b = ByteArray(4); var got = 0
            while (got < 4) { val r = inp.read(b, got, 4 - got); if (r < 0) { if (got == 0) return -1; throw BackupException(BackupException.Kind.CORRUPT, "The backup is cut off (damaged).") }; got += r }
            return ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        }
        private fun rawChunk(): ByteArray {
            val len = if (nextLen == -2) readLen() else nextLen
            nextLen = -2
            if (len < 16 || len > chunk + 16) throw BackupException(BackupException.Kind.CORRUPT, "The backup is damaged.")
            val ct = ByteArray(len)
            try { readFully(inp, ct) } catch (e: EOFException) { throw BackupException(BackupException.Kind.CORRUPT, "The backup is cut off (damaged).") }
            return ct
        }
        private fun fill(): Boolean {
            if (done) return false
            val ct = rawChunk()
            nextLen = readLen()
            val last = nextLen == -1
            cur = try { cipher(Cipher.DECRYPT_MODE, key, nonce(np, idx), aad(pre, idx, last)).doFinal(ct) } catch (e: Exception) { throw BackupException(BackupException.Kind.CORRUPT, "The backup is damaged (a part does not match).") }
            idx++; pos = 0
            if (last) done = true
            return true
        }
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= cur.size) { if (!fill()) return -1 }
            val c = minOf(len, cur.size - pos); System.arraycopy(cur, pos, b, off, c); pos += c; return c
        }
        override fun close() { inp.close() }
    }

    internal fun readFully(inp: InputStream, b: ByteArray) {
        var got = 0
        while (got < b.size) { val r = inp.read(b, got, b.size - got); if (r < 0) throw EOFException(); got += r }
    }

    fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}

// ------------------------------------------------------------------------------------------------ manifest

class EntryInfo(val name: String, val size: Long, val sha256: String)

/** One chat inside a backup: how many messages this backup holds and the mark the next incremental backup continues from. */
class ChatPart(val id: String, val kind: String, val entry: String, val count: Int, val firstTs: Long, val lastTs: Long, val markKey: String, val markTs: Long)

class MediaPart(val id: String, val entry: String, val size: Long)

class BackupManifest(
    val id: String, val created: Long, val app: String, val account: String,
    val gen: String, val seq: Int, val base: String?, val full: Boolean, val includesMedia: Boolean,
    val chats: List<ChatPart>, val media: List<MediaPart>, val entries: List<EntryInfo>,
) {
    val messageCount get() = chats.sumOf { it.count }
    fun toJson(): JSONObject = JSONObject().put("format", "wetalks-backup").put("v", BackupFormat.VERSION).put("id", id).put("created", created).put("app", app).put("account", account)
        .put("gen", gen).put("seq", seq).put("base", base ?: JSONObject.NULL).put("full", full).put("media", includesMedia)
        .put("chats", JSONArray().also { a -> for (c in chats) a.put(JSONObject().put("id", c.id).put("kind", c.kind).put("entry", c.entry).put("count", c.count).put("firstTs", c.firstTs).put("lastTs", c.lastTs).put("markKey", c.markKey).put("markTs", c.markTs)) })
        .put("mediaFiles", JSONArray().also { a -> for (m in media) a.put(JSONObject().put("id", m.id).put("entry", m.entry).put("size", m.size)) })
        .put("entries", JSONArray().also { a -> for (e in entries) a.put(JSONObject().put("name", e.name).put("size", e.size).put("sha256", e.sha256)) })

    companion object {
        fun parse(s: String): BackupManifest {
            try {
                val j = JSONObject(s)
                if (j.optString("format") != "wetalks-backup") throw BackupException(BackupException.Kind.CORRUPT, "This is not a WeTalks backup.")
                if (j.optInt("v") != BackupFormat.VERSION) throw BackupException(BackupException.Kind.UNSUPPORTED, "This backup was made by a newer version of WeTalks. Update the app to open it.")
                val ch = j.getJSONArray("chats"); val me = j.getJSONArray("mediaFiles"); val en = j.getJSONArray("entries")
                return BackupManifest(j.getString("id"), j.getLong("created"), j.optString("app"), j.getString("account"), j.getString("gen"), j.getInt("seq"),
                    if (j.isNull("base")) null else j.getString("base"), j.getBoolean("full"), j.optBoolean("media"),
                    (0 until ch.length()).map { val c = ch.getJSONObject(it); ChatPart(c.getString("id"), c.optString("kind"), c.optString("entry"), c.getInt("count"), c.optLong("firstTs"), c.optLong("lastTs"), c.optString("markKey"), c.optLong("markTs")) },
                    (0 until me.length()).map { val m = me.getJSONObject(it); MediaPart(m.getString("id"), m.getString("entry"), m.optLong("size")) },
                    (0 until en.length()).map { val e = en.getJSONObject(it); EntryInfo(e.getString("name"), e.getLong("size"), e.getString("sha256")) })
            } catch (e: BackupException) { throw e }
            catch (e: Exception) { throw BackupException(BackupException.Kind.CORRUPT, "The backup list of contents is damaged.") }
        }
    }
}

// ------------------------------------------------------------------------------------------------ writer / reader

/** Writes one backup file. Entries are hashed while they are written; [finish] adds the manifest as the last entry and closes everything. */
class BackupWriter(sink: OutputStream, key: BackupKey?) {
    private val zip = ZipOutputStream(BufferedOutputStream(BackupFormat.open(sink, key), 64 * 1024))
    private val infos = ArrayList<EntryInfo>()
    private var finished = false
    init { zip.setLevel(Deflater.DEFAULT_COMPRESSION) }

    fun addBytes(name: String, bytes: ByteArray) = addStream(name, bytes.inputStream(), compress = true)

    /** Copies [src] into the entry [name] and returns the entry's size. [compress] = false for photos / voice notes, which are compressed already. */
    fun addStream(name: String, src: InputStream, compress: Boolean): Long {
        check(!finished); require(BackupFormat.validEntryName(name) && name != "manifest.json")
        val md = MessageDigest.getInstance("SHA-256")
        zip.setLevel(if (compress) Deflater.DEFAULT_COMPRESSION else Deflater.NO_COMPRESSION)
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })        // fixed time: the same content always gives the same bytes
        val buf = ByteArray(32 * 1024); var total = 0L
        while (true) { val r = src.read(buf); if (r < 0) break; md.update(buf, 0, r); zip.write(buf, 0, r); total += r }
        zip.closeEntry()
        infos.add(EntryInfo(name, total, BackupFormat.hex(md.digest())))
        return total
    }

    val written: List<EntryInfo> get() = infos

    /** Closes the file without a manifest (a cancelled or failed backup): the caller deletes it. */
    fun abort() { finished = true; try { zip.close() } catch (_: Exception) {} }

    fun finish(build: (List<EntryInfo>) -> BackupManifest): BackupManifest {
        check(!finished); finished = true
        val m = build(infos.toList())
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0L })
        zip.write(m.toJson().toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
        zip.close()
        return m
    }
}

object BackupReader {
    /** What kind of file this is (plain or passphrase protected) without reading any further. */
    fun peek(inp: InputStream): BackupFormat.Header = BackupFormat.readHeader(inp)

    /**
     * Reads and checks a whole backup. [keyFor] gives the key for an encrypted file (null = ask the person first).
     * [onEntry] receives every data entry once, in file order, and may read it partly or not at all.
     * Nothing may be trusted until this returns: it throws [BackupException] when anything (a byte of data, a size, a hash, the list of contents) does not match.
     */
    fun read(inp: InputStream, keyFor: (BackupFormat.Header) -> BackupKey?, onEntry: (String, InputStream) -> Unit): BackupManifest {
        val h = BackupFormat.readHeader(inp)
        val payload = BackupFormat.payload(inp, h, if (h.encrypted) keyFor(h) else null)
        val seen = LinkedHashMap<String, EntryInfo>()
        var manifest: BackupManifest? = null
        var total = 0L
        try {
            val zin = ZipInputStream(payload)
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory || !BackupFormat.validEntryName(e.name)) throw BackupException(BackupException.Kind.CORRUPT, "The backup contains something unexpected.")
                if (seen.size > BackupFormat.MAX_ENTRIES || manifest != null) throw BackupException(BackupException.Kind.CORRUPT, "The backup contains something unexpected.")
                if (e.name == "manifest.json") {
                    val bo = ByteArrayOutputStream(); val b = ByteArray(8192)
                    while (true) { val r = zin.read(b); if (r < 0) break; bo.write(b, 0, r); if (bo.size() > BackupFormat.MAX_MANIFEST) throw BackupException(BackupException.Kind.CORRUPT, "The backup is damaged.") }
                    manifest = BackupManifest.parse(bo.toString("UTF-8"))
                    continue
                }
                if (seen.containsKey(e.name)) throw BackupException(BackupException.Kind.CORRUPT, "The backup contains something unexpected.")
                val md = MessageDigest.getInstance("SHA-256"); var size = 0L
                val watch = object : InputStream() {
                    override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val r = zin.read(b, off, len)
                        if (r > 0) { md.update(b, off, r); size += r; total += r; if (size > BackupFormat.MAX_ENTRY) throw BackupException(BackupException.Kind.CORRUPT, "The backup is damaged (too large).") }
                        return r
                    }
                }
                onEntry(e.name, watch)
                val drain = ByteArray(32 * 1024); while (watch.read(drain, 0, drain.size) >= 0) { }
                seen[e.name] = EntryInfo(e.name, size, BackupFormat.hex(md.digest()))
            }
            // anything after the zip stream (but before the end of the encrypted payload) must still pass the chunk checks
            val tail = ByteArray(4096); while (payload.read(tail) >= 0) { }
        } catch (e: BackupException) { throw e }
        catch (e: java.util.zip.ZipException) { throw BackupException(BackupException.Kind.CORRUPT, "The backup is damaged.") }
        catch (e: EOFException) { throw BackupException(BackupException.Kind.CORRUPT, "The backup is cut off (damaged).") }
        catch (e: IllegalArgumentException) { throw BackupException(BackupException.Kind.CORRUPT, "The backup is damaged.") }
        val m = manifest ?: throw BackupException(BackupException.Kind.CORRUPT, "The backup is incomplete (no list of contents).")
        if (m.entries.size != seen.size) throw BackupException(BackupException.Kind.CORRUPT, "The backup does not match its list of contents.")
        for (en in m.entries) {
            val s = seen[en.name] ?: throw BackupException(BackupException.Kind.CORRUPT, "Part of the backup is missing.")
            if (s.size != en.size || !s.sha256.equals(en.sha256, true)) throw BackupException(BackupException.Kind.CORRUPT, "The backup is damaged (a checksum does not match).")
        }
        return m
    }
}

// ------------------------------------------------------------------------------------------------ message lines

/** One message as one line of JSON. Only readable, normal messages are written: deleted, undecryptable and still-sending ones are not. */
object BackupMsgCodec {
    fun keep(m: Msg) = !m.deleted && !m.unreadable && !m.pending && m.id.isNotEmpty() && !m.id.startsWith("out_")

    fun encode(m: Msg): String {
        val j = JSONObject().put("id", m.id).put("from", m.from).put("ts", m.ts).put("type", m.type).put("text", m.text)
        if (m.mediaId != null) j.put("m", m.mediaId)
        if (m.dur != 0) j.put("dur", m.dur)
        if (m.rid != null) j.put("rid", m.rid)
        if (m.edited) j.put("ed", true)
        if (m.reactions.isNotEmpty()) j.put("re", JSONObject(m.reactions as Map<*, *>))
        if (m.wave.isNotEmpty()) j.put("wv", JSONArray(m.wave))
        m.link?.let { j.put("lk", JSONObject().put("u", it.url).put("t", it.title).put("d", it.desc).put("i", it.thumb)) }
        if (m.mime.isNotEmpty()) j.put("mime", m.mime)
        if (m.w != 0) j.put("w", m.w); if (m.h != 0) j.put("h", m.h)
        if (m.fwd != null) j.put("fwd", m.fwd)
        if (m.name.isNotEmpty()) j.put("n", m.name); if (m.size != 0L) j.put("sz", m.size)
        if (m.fkey.isNotEmpty()) j.put("fk", m.fkey); if (m.thumb.isNotEmpty()) j.put("th", m.thumb)
        if (m.album.isNotEmpty()) j.put("al", m.album)
        return j.toString()
    }

    fun decode(line: String): Msg? = try {
        val j = JSONObject(line)
        val re = HashMap<String, String>(); j.optJSONObject("re")?.let { o -> o.keys().forEach { k -> re[k] = o.optString(k) } }
        val wv = j.optJSONArray("wv")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
        val lk = j.optJSONObject("lk")?.let { LinkPreview(it.optString("u"), it.optString("t"), it.optString("d"), it.optString("i")) }
        Msg(j.getString("id"), j.optString("from"), j.optLong("ts"), j.optString("type", "text"), j.optString("text"), if (j.has("m")) j.getString("m") else null, j.optInt("dur"),
            if (j.has("rid")) j.getString("rid") else null, false, j.optBoolean("ed"), re, false, false, wv, lk, j.optString("mime"), j.optInt("w"), j.optInt("h"),
            if (j.has("fwd")) j.getString("fwd") else null, j.optString("n"), j.optLong("sz"), j.optString("fk"), j.optString("th"), j.optString("al"))
    } catch (e: Exception) { null }

    /** The id of a line without building the whole message (used when merging big chat files). */
    fun idOf(line: String): String? = try { JSONObject(line).getString("id") } catch (e: Exception) { null }
}
