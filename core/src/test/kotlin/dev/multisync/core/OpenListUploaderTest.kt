package dev.multisync.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.Collections
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class OpenListUploaderTest {
    private fun envelope(data: String, code: Int = 200) = MockResponse().setBody("""{"code":$code,"message":"response","data":$data}""")
    private fun snapshot(size: Long, chunk: Long, received: String = "[]", state: String = "receiving", bytes: Long = 0) =
        """{"upload_id":"session","state":"$state","size":$size,"chunk_size":$chunk,"total_chunks":${(size - 1) / chunk + 1},"received":$received,"received_bytes":$bytes,"storage_progress":35,"error":"driver failed"}"""

    private fun fixture(run: (MockWebServer, File) -> Unit) {
        val server = MockWebServer()
        server.start()
        val file = Files.createTempFile("multipart", ".apk").toFile().apply { writeBytes(ByteArray(8) { it.toByte() }) }
        try { run(server, file) } finally { file.delete(); server.shutdown() }
    }

    private fun uploader(server: MockWebServer, events: MutableList<TransferProgress>, wait: Long = 10_000) =
        OpenListUploader(server.url("/prefix/dav/"), "user", "private-password", OkHttpClient(), events::add, 1, wait)

    private fun dispatcher(handler: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/prefix/api/public/settings" -> envelope("""{"multipart_enabled":"true","multipart_chunk_size":"10"}""")
            "/prefix/api/auth/login/hash" -> envelope("""{"token":"private-token"}""")
            "/prefix/api/fs/resumable" -> envelope("null", 405).setResponseCode(405)
            else -> handler(request)
        }
    }

    @Test fun usesServerChunkSizeEncodedPathAndPollsBeforeAcknowledgingStorageCompletion() = fixture { server, file ->
        val chunks = ConcurrentHashMap<Int, ByteArray>()
        val arrived = CountDownLatch(3)
        val events = mutableListOf<TransferProgress>()
        var polls = 0
        var init: RecordedRequest? = null
        server.dispatcher = dispatcher { request ->
            assertEquals("private-token", request.getHeader("Authorization"))
            when {
                request.path!!.endsWith("/init") -> { init = request; envelope(snapshot(8, 3).replace("\"received\":[]", "\"received\":null")) }
                request.path!!.endsWith("/chunk") -> {
                    val i = request.getHeader("X-Chunk-Index")!!.toInt()
                    chunks[i] = request.body.readByteArray()
                    arrived.countDown()
                    if (!arrived.await(1, TimeUnit.SECONDS)) return@dispatcher MockResponse().setResponseCode(500)
                    envelope(snapshot(8, 3, "[[0,$i]]", bytes = minOf(8, (i + 1) * 3L)))
                }
                request.path!!.contains("/status?") -> envelope(snapshot(8, 3, "[[0,2]]", if (++polls == 1) "receiving" else "completed", 8))
                request.path!!.endsWith("/complete") -> {
                    assertEquals(2, polls)
                    assertFalse(events.any { it.phase == TransferPhase.COMPLETE })
                    envelope(snapshot(8, 3, "[[0,2]]", "completed", 8))
                }
                else -> MockResponse().setResponseCode(500)
            }
        }
        assertTrue(uploader(server, events).upload("备份/a +#.apk", file) { fail("live session should not need recovery verification") })
        assertEquals(setOf(0, 1, 2), chunks.keys)
        assertEquals(listOf(3, 3, 2), (0..2).map { chunks[it]!!.size })
        assertArrayEquals(file.readBytes(), (0..2).fold(byteArrayOf()) { a, i -> a + chunks[i]!! })
        assertEquals("%2F%E5%A4%87%E4%BB%BD%2Fa%20%2B%23.apk", init!!.getHeader("File-Path"))
        assertEquals(digest(file).sha256, init!!.getHeader("X-File-Sha256"))
        assertTrue(events.any { it.phase == TransferPhase.WAITING && it.storagePercent == 35f })
        assertEquals(TransferPhase.COMPLETE, events.last().phase)
        assertEquals(8L, events.last().bytes)
    }

    @Test fun lostChunkResponseIsRecoveredByStatusWithoutResendingConfirmedBytes() = fixture { server, file ->
        val indexes = Collections.synchronizedList(mutableListOf<String>())
        val events = mutableListOf<TransferProgress>()
        server.dispatcher = dispatcher { request -> when {
            request.path!!.endsWith("/init") -> envelope(snapshot(8, 4))
            request.path!!.endsWith("/chunk") -> {
                val index = request.getHeader("X-Chunk-Index")!!
                indexes += index
                if (index == "0") MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else envelope(snapshot(8, 4, "[[1,1]]", bytes = 4))
            }
            request.path!!.contains("/status?") -> envelope(snapshot(8, 4, "[[0,1]]", "completed", 8))
            else -> envelope(snapshot(8, 4, "[[0,1]]", "completed", 8))
        } }
        assertTrue(uploader(server, events).upload("file.apk", file) { fail("not needed") })
        assertEquals(listOf("0", "1"), indexes.sorted())
        assertTrue(events.all { it.bytes in 0..8 })
    }

    @Test fun resumesReceivedRangesAndHandlesWindowFlowControlWithoutOvercountingRetries() = fixture { server, file ->
        val events = mutableListOf<TransferProgress>()
        val indexes = Collections.synchronizedList(mutableListOf<String?>())
        server.dispatcher = dispatcher { request -> when {
            request.path!!.endsWith("/init") || request.path!!.contains("/status?") -> envelope(snapshot(8, 4, "[[0,0]]", bytes = 4))
            request.path!!.endsWith("/chunk") -> {
                indexes += request.getHeader("X-Chunk-Index")
                if (indexes.size == 1) envelope(snapshot(8, 4, "[[0,0]]", bytes = 4), 429).setResponseCode(429)
                else envelope(snapshot(8, 4, "[[0,1]]", "completed", 8))
            }
            else -> envelope(snapshot(8, 4, "[[0,1]]", "completed", 8))
        } }
        assertTrue(uploader(server, events).upload("file.apk", file) {})
        assertEquals(listOf("1", "1"), indexes)
        assertTrue(events.all { it.bytes in 0..8 })
    }

    @Test fun retriesFailedStorageFromChunkZeroInsteadOfTrustingOldReceivedRanges() = fixture { server, file ->
        val events = mutableListOf<TransferProgress>()
        val indexes = Collections.synchronizedList(mutableListOf<String?>())
        var failed = false
        server.dispatcher = dispatcher { request -> when {
            request.path!!.endsWith("/init") -> envelope(snapshot(8, 4))
            request.path!!.endsWith("/chunk") -> {
                val index = request.getHeader("X-Chunk-Index")
                indexes += index
                envelope(snapshot(8, 4, "[[0,$index]]", bytes = if (index == "0") 4 else 8))
            }
            request.path!!.contains("/status?") -> {
                if (!failed) { failed = true; envelope(snapshot(8, 4, state = "failed_retriable")) }
                else envelope(snapshot(8, 4, "[[0,1]]", "completed", 8))
            }
            else -> envelope(snapshot(8, 4, "[[0,1]]", "completed", 8))
        } }
        assertTrue(uploader(server, events).upload("file.apk", file) {})
        assertEquals(listOf("0", "1"), indexes.take(2).sortedBy { it })
        assertEquals(listOf("0", "1"), indexes.drop(2))
        assertTrue(events.all { it.bytes in 0..8 })
    }

    @Test fun missingSessionIsSuccessfulOnlyAfterRemoteDigestVerification() = fixture { server, file ->
        server.dispatcher = dispatcher { request -> when {
            request.path!!.endsWith("/init") -> envelope(snapshot(8, 8))
            request.path!!.endsWith("/chunk") -> envelope(snapshot(8, 8, "[[0,0]]", bytes = 8))
            else -> envelope("null", 404).setResponseCode(404)
        } }
        val events = mutableListOf<TransferProgress>()
        assertThrows(RemoteContentMismatch::class.java) {
            uploader(server, events).upload("file.apk", file) { throw RemoteContentMismatch() }
        }
        assertFalse(events.any { it.phase == TransferPhase.COMPLETE })
        var checks = 0
        assertTrue(uploader(server, mutableListOf()).upload("file.apk", file) { expected ->
            assertEquals(digest(file), expected); checks++
        })
        assertEquals(1, checks)
    }

    @Test fun storageFailureDoesNotCommitRecordsOrDeleteLocalBackup() = fixture { server, file ->
        val events = mutableListOf<TransferProgress>()
        var deleted = false
        var committed = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "HEAD" -> MockResponse().setResponseCode(404)
                request.method == "MKCOL" -> MockResponse().setResponseCode(201)
                request.method == "GET" && request.path == "/prefix/api/public/settings" -> envelope("""{"multipart_enabled":"true"}""")
                request.path == "/prefix/api/auth/login/hash" -> envelope("""{"token":"private-token"}""")
                request.path == "/prefix/api/fs/list" -> envelope("""{"provider":"139Yun","direct_upload_tools":null}""")
                request.path == "/prefix/api/fs/resumable" -> envelope("null", 405).setResponseCode(405)
                request.path!!.endsWith("/init") -> envelope(snapshot(8, 8, state = "failed_permanent"))
                else -> { committed = true; MockResponse().setResponseCode(500) }
            }
        }
        val cloud = WebDavClient(server.url("/prefix/dav/").toString(), "u", "p", true, onTransfer = events::add, preferOpenListUpload = true)
        assertThrows(IllegalStateException::class.java) {
            ArchiveEngine(cloud).archive(file, "backup.zip", "source", "backups", "device", "rule", true,
                { true }, { deleted = true; true })
        }
        assertFalse(deleted)
        assertFalse(committed)
        assertTrue(file.exists())
        assertFalse(events.any { it.phase == TransferPhase.COMPLETE })
    }

    @Test fun loginChecksJsonCodeAndDoesNotExposeSecretsOrAttemptUpload() = fixture { server, file ->
        var chunks = 0
        var logins = 0
        var loginBody = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/settings") -> envelope("""{"multipart_enabled":"true"}""")
                request.path!!.endsWith("/hash") -> {
                    logins++
                    loginBody = request.body.readUtf8()
                    MockResponse().setBody("""{"code":401,"message":"private-password private-token","data":null}""")
                }
                else -> { chunks++; MockResponse().setResponseCode(500) }
            }
        }
        val error = assertThrows(OpenListResponseException::class.java) {
            uploader(server, mutableListOf()).upload("file.apk", file) {}
        }
        assertEquals(401, error.statusCode)
        assertFalse(error.message!!.contains("private-password"))
        assertFalse(error.message!!.contains("private-token"))
        assertFalse(loginBody.contains("private-password"))
        assertTrue(loginBody.contains(digest("private-password-https://github.com/alist-org/alist".byteInputStream()).sha256))
        assertEquals(0, chunks)
        assertEquals(1, logins)
    }

    @Test fun olderServerKeepsWebDavUploadAndDoesNotAttemptApiLogin() = fixture { server, file ->
        server.enqueue(envelope("""{"version":"v4","multipart_enabled":"false"}"""))
        server.enqueue(MockResponse().setResponseCode(201))
        WebDavClient(server.url("/dav/").toString(), "u", "p", true, preferOpenListUpload = true).putFile("file.apk", file)
        assertEquals("/api/public/settings", server.takeRequest().path)
        val upload = server.takeRequest()
        assertEquals("PUT", upload.method)
        assertEquals("/dav/file.apk", upload.path)
        assertArrayEquals(file.readBytes(), upload.body.readByteArray())
        assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
    }

    @Test fun receivingSessionTimesOutWithoutClaimingCompletion() = fixture { server, file ->
        val events = mutableListOf<TransferProgress>()
        server.dispatcher = dispatcher { request ->
            envelope(snapshot(8, 8, if (request.path!!.endsWith("/init")) "[]" else "[[0,0]]", bytes = if (request.path!!.endsWith("/init")) 0 else 8))
        }
        assertThrows(java.io.IOException::class.java) { uploader(server, events, 150).upload("file.apk", file) {} }
        assertTrue(file.exists())
        assertFalse(events.any { it.phase == TransferPhase.COMPLETE })
    }

    @Test fun rebuildsAPersistentlyStalledSessionOnceInsteadOfResumingItForever() = fixture { server, file ->
        var initialized = 0
        var aborted = 0
        var chunks = 0
        server.dispatcher = dispatcher { request -> when {
            request.path!!.endsWith("/init") -> {
                initialized++
                envelope(snapshot(8, 8, state = if (initialized == 1) "receiving" else "completed"))
            }
            request.path!!.endsWith("/chunk") -> { chunks++; MockResponse().setResponseCode(503) }
            request.path!!.endsWith("/abort") -> { aborted++; envelope("null") }
            request.path!!.contains("/status?") -> envelope(snapshot(8, 8))
            else -> envelope(snapshot(8, 8, "[[0,0]]", "completed", 8))
        } }
        assertTrue(uploader(server, mutableListOf()).upload("file.apk", file) {})
        assertEquals(2, initialized)
        assertEquals(1, aborted)
        assertEquals(2, chunks)
    }
}
