package com.kafisir.wetalks

import com.kafisir.wetalks.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupEngineTest {
    private val day = 86_400_000L
    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var work: File
    @Before fun setUp() { root = Files.createTempDirectory("wtbk").toFile(); dir = File(root, "backups"); work = File(root, "work") }
    @After fun tearDown() { root.deleteRecursively() }

    private class Fake(val chats: MutableMap<String, MutableList<Msg>>, val media: Map<String, File> = emptyMap()) : BackupSource {
        var failOn: String? = null
        val fetched = ArrayList<String>()
        override suspend fun chats() = chats.keys.sorted().map { ChatRef(it, Kind.DM, "p") }
        override suspend fun page(ref: ChatRef, afterKey: String?, limit: Int): List<Msg> {
            if (ref.id == failOn) throw java.io.IOException("network down")
            fetched.add(ref.id)
            return chats[ref.id]!!.sortedBy { it.id }.filter { afterKey == null || it.id > afterKey }.take(limit)
        }
        override fun settings() = JSONObject().put("v", 1)
        override fun mediaCandidates() = media.keys.toList()
        override fun mediaFile(id: String) = media[id]
    }

    private fun m(id: String, ts: Long) = Msg(id, "u", ts, "text", "text $id")
    private fun engine(src: BackupSource, now: Long, free: Long = Long.MAX_VALUE) = BackupEngine(src, dir, work, "acct", "1.0", { now }, { free }, pageSize = 3)
    private fun run(e: BackupEngine, key: BackupKey? = null, media: Boolean = false, full: Boolean = false, keep: Int = 2) =
        runBlocking { e.run(BackupEngine.Options(key, media, full, keep), {}, { false }) }

    private fun data(n: Int, t0: Long) = (1..n).map { m("k%03d".format(it), t0 + it) }.toMutableList()

    @Test fun fullThenIncrementalOnlyHoldsNewMessages() {
        val src = Fake(mutableMapOf("a" to data(7, 1 * day), "b" to data(2, 1 * day)))
        val d1 = run(engine(src, 10 * day)) as BackupEngine.Outcome.Done
        assertTrue(d1.manifest.full); assertEquals(9, d1.manifest.messageCount)
        // 3 new messages, aged past the one-day overlap by the next run
        src.chats["a"]!!.addAll((8..10).map { m("k%03d".format(it), 12 * day + it) })
        val d2 = run(engine(src, 20 * day)) as BackupEngine.Outcome.Done
        assertFalse(d2.manifest.full); assertEquals(1, d2.manifest.seq); assertEquals(d1.manifest.id, d2.manifest.base)
        assertEquals("only the 3 new ones", 3, d2.manifest.messageCount)
        // nothing changed: no new file
        assertTrue(run(engine(src, 30 * day)) is BackupEngine.Outcome.NothingNew)
        assertEquals(2, BackupFiles.list(dir).size)
    }

    @Test fun restoreMatchesTheSourceAndIsRepeatable() {
        val src = Fake(mutableMapOf("a" to data(7, 1 * day), "b" to data(2, 1 * day)))
        val d1 = run(engine(src, 10 * day), BackupKey.derive("a long passphrase")) as BackupEngine.Outcome.Done
        val store = ArchiveStore(File(root, "archive"))
        val key = BackupKey.derive("a long passphrase", BackupFormat.randomBytes(16))      // different salt: the restore derives from the header
        val r = d1.file.inputStream().use { BackupRestore(store, "acct").restore(it, { h -> BackupFormat.keyFor(h, "a long passphrase") }) }
        assertEquals(9, r.messagesAdded); assertEquals(2, r.chats)
        assertEquals(src.chats["a"]!!.map { it.id }, store.messages("a").map { it.id })
        assertEquals(src.chats["a"]!!.first(), store.messages("a").first())
        val again = d1.file.inputStream().use { BackupRestore(store, "acct").restore(it, { h -> BackupFormat.keyFor(h, "a long passphrase") }) }
        assertEquals("no duplicates", 0, again.messagesAdded)
        assertEquals(7, store.messages("a").size)
        assertNotNull(key)
    }

    @Test fun restoreRefusesOtherAccountAndDamagedFilesAndChangesNothing() {
        val src = Fake(mutableMapOf("a" to data(5, 1 * day)))
        val d = run(engine(src, 10 * day)) as BackupEngine.Outcome.Done
        val store = ArchiveStore(File(root, "archive"))
        try { d.file.inputStream().use { BackupRestore(store, "someone else").restore(it, { null }) }; fail() } catch (e: BackupException) { assertEquals(BackupException.Kind.WRONG_ACCOUNT, e.kind) }
        assertTrue(store.messages("a").isEmpty())
        val bytes = d.file.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 4).toByte()
        try { BackupRestore(store, "acct").restore(bytes.inputStream(), { null }); fail() } catch (e: BackupException) { assertEquals(BackupException.Kind.CORRUPT, e.kind) }
        assertTrue(store.messages("a").isEmpty()); assertFalse(File(root, "archive/.staging").exists())
    }

    @Test fun interruptedBackupResumesWithoutReadingFinishedChatsAgain() {
        val src = Fake(mutableMapOf("a" to data(4, 1 * day), "b" to data(4, 1 * day), "c" to data(4, 1 * day)))
        src.failOn = "c"
        val f = run(engine(src, 10 * day))
        assertTrue(f is BackupEngine.Outcome.Failed)
        assertEquals(listOf("a", "b"), src.fetched.distinct()); assertTrue(File(work, "journal.json").exists()); assertTrue(BackupFiles.list(dir).isEmpty())
        src.failOn = null; src.fetched.clear()
        val d = run(engine(src, 10 * day + 60_000)) as BackupEngine.Outcome.Done
        assertTrue(d.resumed); assertEquals(listOf("c"), src.fetched.distinct()); assertEquals(12, d.manifest.messageCount)
        assertFalse("work folder cleaned", File(work, "journal.json").exists())
    }

    @Test fun cancelKeepsProgress() {
        val src = Fake(mutableMapOf("a" to data(4, 1 * day), "b" to data(4, 1 * day)))
        var n = 0
        val c = runBlocking { engine(src, 10 * day).run(BackupEngine.Options(null, false, false), {}, { n++ >= 1 }) }
        assertTrue(c is BackupEngine.Outcome.Cancelled); assertTrue(BackupFiles.list(dir).isEmpty())
        assertTrue(run(engine(src, 10 * day + 1)) is BackupEngine.Outcome.Done)
    }

    @Test fun diskFullIsAMessageNotACrash() {
        val src = Fake(mutableMapOf("a" to data(4, 1 * day)))
        val r = run(engine(src, 10 * day, free = 1000))
        assertTrue(r is BackupEngine.Outcome.Failed && r.diskFull)
        assertTrue(BackupFiles.list(dir).isEmpty())
    }

    @Test fun mediaIsStoredOnceAndRetentionRemovesOldSets() {
        val pic = File(root, "pic").apply { writeBytes(ByteArray(500) { 7 }) }
        val src = Fake(mutableMapOf("a" to data(3, 1 * day)), mapOf("pic1" to pic))
        val d1 = run(engine(src, 10 * day), media = true) as BackupEngine.Outcome.Done
        assertEquals(1, d1.manifest.media.size)
        src.chats["a"]!!.add(m("k999", 12 * day))
        val d2 = run(engine(src, 20 * day), media = true) as BackupEngine.Outcome.Done
        assertEquals("pic not stored twice", 0, d2.manifest.media.size)
        // three full backups with keep = 2 sets: the oldest set goes
        run(engine(src, 21 * day), full = true, keep = 2); run(engine(src, 22 * day), full = true, keep = 2)
        val left = BackupFiles.list(dir)
        assertEquals(2, left.map { it.manifest.gen }.distinct().size)
        assertTrue(left.none { it.manifest.id == d1.manifest.id })
    }

    @Test fun lockedAndDeletedMessagesAreNotWritten() {
        val msgs = mutableListOf(m("k1", 1 * day), m("k2", 1 * day).copy(deleted = true), m("k3", 1 * day).copy(unreadable = true), m("k4", 1 * day))
        val src = Fake(mutableMapOf("a" to msgs))
        val d = run(engine(src, 10 * day)) as BackupEngine.Outcome.Done
        assertEquals(2, d.manifest.messageCount)
    }

    @Test fun archiveUnionLiveWinsAndFillsGaps() {
        val live = listOf(m("k2", 2).copy(text = "edited"), Msg("k4", "", 0, "text", "") )
        val arch = listOf(m("k1", 1), m("k2", 2), m("k4", 4))
        val u = ArchiveStore.union(live, arch)
        assertEquals(listOf("k1", "k2", "k4"), u.map { it.id }); assertEquals("edited", u[1].text); assertEquals("text k4", u[2].text)
        val deletedLive = listOf(m("k1", 1).copy(deleted = true))
        assertTrue("deleted for everyone stays hidden", ArchiveStore.union(deletedLive, arch).first { it.id == "k1" }.deleted)
        assertSame(live, ArchiveStore.union(live, emptyList()))
    }
}
