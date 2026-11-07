package com.kafisir.wetalks

import com.kafisir.wetalks.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Runs the resumable-upload client against a small fake of the Drive protocol. This proves the client follows the documented protocol; it does NOT prove anything about the real Google Drive. */
class DriveRestTest {
    class Req(val path: String, val query: String, val headers: Map<String, String>, val body: ByteArray)
    class Res(val code: Int, val headers: Map<String, String> = emptyMap(), val body: String = "")

    private lateinit var ss: ServerSocket
    private val routes = LinkedHashMap<String, (Req) -> Res>()
    private val received = ByteArrayOutputStream()
    private var total = 0L
    private var sessionHits = 0
    private var dropAfter = -1            // simulate a failure after this many received bytes (once)
    private var createCalls = 0
    private val tokens = object : DriveTokens { override suspend fun token() = "tok" }
    private lateinit var base: String

    private fun serve() = thread(isDaemon = true) {
        while (!ss.isClosed) {
            val c = try { ss.accept() } catch (e: Exception) { break }
            try {
                val inp = c.getInputStream().buffered(); fun line(): String { val sb = StringBuilder(); while (true) { val b = inp.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }; return sb.toString() }
                val first = line().split(" "); val h = HashMap<String, String>()
                while (true) { val l = line(); if (l.isEmpty()) break; h[l.substringBefore(":").trim().lowercase()] = l.substringAfter(":").trim() }
                val body = ByteArray(h["content-length"]?.toInt() ?: 0).also { var g = 0; while (g < it.size) { val r = inp.read(it, g, it.size - g); if (r < 0) break; g += r } }
                val path = first[1].substringBefore("?"); val q = first[1].substringAfter("?", "")
                val handler = routes.entries.firstOrNull { path.startsWith(it.key) }?.value
                val res = handler?.invoke(Req(path, q, h, body)) ?: Res(404)
                val out = c.getOutputStream(); val b = res.body.toByteArray()
                out.write(("HTTP/1.1 ${res.code} X\r\n" + res.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray()); out.write(b); out.flush()
            } catch (e: Exception) { /* a dropped test connection */ } finally { try { c.close() } catch (_: Exception) {} }
        }
    }

    @Before fun up() {
        ss = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1")); base = "http://127.0.0.1:${ss.localPort}"
        routes["/upload/drive/v3/files"] = { r ->
            createCalls++
            assertEquals("Bearer tok", r.headers["authorization"]); assertEquals("application/octet-stream", r.headers["x-upload-content-type"])
            total = r.headers["x-upload-content-length"]!!.toLong()
            assertTrue(String(r.body).contains("appDataFolder")); assertTrue(r.query.contains("uploadType=resumable"))
            Res(200, mapOf("Location" to "$base/session/1"))
        }
        routes["/session/1"] = { r ->
            sessionHits++
            val cr = r.headers["content-range"]!!
            if (cr.startsWith("bytes */")) {                                     // status query
                if (received.size().toLong() >= total && total > 0) Res(200, body = """{"id":"FILE1"}""")
                else Res(308, if (received.size() > 0) mapOf("Range" to "bytes=0-${received.size() - 1}") else emptyMap())
            } else {
                val start = cr.substringAfter("bytes ").substringBefore("-").toLong()
                assertEquals("chunks must continue where Drive is", received.size().toLong(), start)
                if (dropAfter >= 0 && dropAfter < received.size() + r.body.size) { received.write(r.body, 0, dropAfter - received.size()); dropAfter = -1; Res(503) }
                else { received.write(r.body); if (received.size().toLong() == total) Res(200, body = """{"id":"FILE1"}""") else Res(308, mapOf("Range" to "bytes=0-${received.size() - 1}")) }
            }
        }
        serve()
    }
    @After fun down() { ss.close() }

    private fun client(sessions: DriveRest.SessionStore = DriveRest.SessionStore.None) = DriveRest(tokens, "$base/drive/v3", "$base/upload/drive/v3", sessions, chunk = 1000, backoffMs = 0)
    private fun file(n: Int) = File.createTempFile("wtbk", ".bin").apply { writeBytes(ByteArray(n) { (it % 251).toByte() }); deleteOnExit() }

    @Test fun uploadsInChunks() {
        val f = file(3500)
        assertEquals("FILE1", runBlocking { client().upload(f, "x.wtbk") })
        assertArrayEquals(f.readBytes(), received.toByteArray()); assertEquals(1, createCalls); assertEquals(4, sessionHits)
    }

    @Test fun resumesAfterAFailedChunkFromWhatDriveKept() {
        val f = file(3500); dropAfter = 1500
        val mem = HashMap<String, String>()
        val store = object : DriveRest.SessionStore { override fun get(key: String) = mem[key]; override fun put(key: String, uri: String?) { if (uri == null) mem.remove(key) else mem[key] = uri } }
        assertEquals("FILE1", runBlocking { client(store).upload(f, "x.wtbk") })
        assertArrayEquals(f.readBytes(), received.toByteArray())
        assertEquals("the session was reused, not recreated", 1, createCalls); assertTrue(mem.isEmpty())
    }

    @Test fun failuresBecomeReadableMessages() {
        routes["/drive/v3/files"] = { Res(403, body = """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""") }
        try { runBlocking { client().list() }; fail() } catch (e: CloudException) { assertEquals("Your Google Drive is full.", e.message) }
    }

    @Test fun listReadsFilesAndPages() {
        var n = 0
        routes["/drive/v3/files"] = { if (n++ == 0) Res(200, body = """{"files":[{"id":"1","name":"a","size":"10"}],"nextPageToken":"t"}""") else Res(200, body = """{"files":[{"id":"2","name":"b","size":"20"}]}""") }
        assertEquals(listOf("a", "b"), runBlocking { client().list() }.map { it.name })
    }

    @Test fun notSetUpIsReportedWithoutCallingGoogle() {
        try { runBlocking { DriveAuth.token() }; fail() } catch (e: CloudException) { assertTrue(e.notSetUp); assertEquals("Google Drive is not set up for this app yet", e.message) }
    }
}
