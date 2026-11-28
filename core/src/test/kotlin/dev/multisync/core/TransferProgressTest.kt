package dev.multisync.core

import java.nio.file.Files
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class TransferProgressTest {
    @Test fun uploadResponseCanWaitLongerThanTheMetadataReadTimeout() {
        val server = MockWebServer()
        server.start()
        val file = Files.createTempFile("upload", ".bin").toFile().apply { writeText("content") }
        try {
            server.enqueue(MockResponse().setResponseCode(201).setHeadersDelay(400, TimeUnit.MILLISECONDS))
            val base = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()
            WebDavClient(server.url("/dav/").toString(), "u", "p", true, client = base).putFile("file.bin", file)
            assertEquals(file.length(), server.takeRequest().bodySize)
        } finally { file.delete(); server.shutdown() }
    }

    @Test fun uploadCountsActualBytesAndCompletesOnlyAfterASuccessfulResponse() {
        val server = MockWebServer()
        server.start()
        val file = Files.createTempFile("upload", ".bin").toFile().apply { writeBytes(ByteArray(256 * 1024) { (it % 251).toByte() }) }
        try {
            server.enqueue(MockResponse().setResponseCode(201))
            val events = mutableListOf<TransferProgress>()
            WebDavClient(server.url("/dav/").toString(), "u", "p", true, onTransfer = events::add).putFile("file.bin", file)
            assertEquals(file.length(), server.takeRequest().bodySize)
            assertEquals(TransferDirection.UPLOAD, events.last().direction)
            assertEquals(file.length(), events.last().bytes)
            assertEquals(file.length(), events.last().totalBytes)
            assertEquals(TransferPhase.COMPLETE, events.last().phase)
            assertTrue(events.any { it.phase == TransferPhase.WAITING })
            assertTrue(events.all { it.bytes <= file.length() })
        } finally { file.delete(); server.shutdown() }
    }

    @Test fun rejectedUploadReportsFailureEvenWhenAllRequestBytesWereWritten() {
        val server = MockWebServer()
        server.start()
        val file = Files.createTempFile("upload", ".bin").toFile().apply { writeText("a complete body") }
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            val events = mutableListOf<TransferProgress>()
            assertThrows(IllegalStateException::class.java) {
                WebDavClient(server.url("/dav/").toString(), "u", "p", true, onTransfer = events::add).putFile("file.bin", file)
            }
            assertEquals(TransferPhase.FAILED, events.last().phase)
            assertFalse(events.any { it.phase == TransferPhase.COMPLETE })
        } finally { file.delete(); server.shutdown() }
    }

    @Test fun chunkedDownloadCountsOnceAndLearnsItsTotalAtEndOfStream() {
        val server = MockWebServer()
        server.start()
        try {
            val payload = ByteArray(50 * 1024) { (it % 211).toByte() }
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(payload), 2048))
            val events = mutableListOf<TransferProgress>()
            val actual = WebDavClient(server.url("/dav/").toString(), "u", "p", true, onTransfer = events::add).open("file.bin").use { it.readBytes() }
            assertArrayEquals(payload, actual)
            assertNull(events.first().totalBytes)
            assertEquals(payload.size.toLong(), events.last().bytes)
            assertEquals(payload.size.toLong(), events.last().totalBytes)
            assertEquals(TransferPhase.COMPLETE, events.last().phase)
        } finally { server.shutdown() }
    }

    @Test fun earlyCloseDoesNotClaimACompleteDownloadAndMetadataDoesNotGenerateProgress() {
        val server = MockWebServer()
        server.start()
        try {
            val events = mutableListOf<TransferProgress>()
            val client = WebDavClient(server.url("/dav/").toString(), "u", "p", true, onTransfer = events::add)
            server.enqueue(MockResponse().setBody("data"))
            client.open("file.bin").use { assertEquals('d'.code, it.read()) }
            assertEquals(1L, events.last().bytes)
            assertEquals(TransferPhase.CANCELLED, events.last().phase)
            events.clear()
            server.enqueue(MockResponse().setBody("{}"))
            client.open("record.json").use { it.readBytes() }
            assertTrue(events.isEmpty())
        } finally { server.shutdown() }
    }
}
