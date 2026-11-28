package dev.multisync.core

import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WebDavClientTest {
    @Test fun directoryPickerReadsRootAndIgnoresFilesButReportsMissingAndUnauthorizedDirectories() {
        val server = MockWebServer()
        server.start()
        try {
            val client = WebDavClient(server.url("/dav/").toString(), "u", "p", true)
            server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:">
                <d:response><d:href>/dav/team/</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                <d:response><d:href>/dav/file.apk</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat></d:response>
                </d:multistatus>"""))
            assertEquals(listOf(RemoteEntry("team", true, 0)), client.listDirectories("/"))
            val request = server.takeRequest()
            assertEquals("/dav/", request.path)
            assertEquals("PROPFIND", request.method)
            server.enqueue(MockResponse().setResponseCode(404))
            assertTrue(assertThrows(IllegalStateException::class.java) { client.listDirectories("missing") }.message!!.contains("目录不存在"))
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(assertThrows(IllegalStateException::class.java) { client.listDirectories("private") }.message!!.contains("401"))
            server.enqueue(MockResponse().setResponseCode(404))
            assertTrue(client.list("not-published").isEmpty())
        } finally { server.shutdown() }
    }

    @Test fun listingExcludesParentFailedPropertiesAndForeignHosts() {
        val server = MockWebServer()
        server.start()
        try {
            val client = WebDavClient(server.url("/dav/").toString(), "u", "p", true)
            val xml = """<d:multistatus xmlns:d="DAV:">
                <d:response><d:href>/dav/backups/</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                <d:response><d:href>/dav/backups/file%2Bname.zip</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat></d:response>
                <d:response><d:href>https://other.invalid/dav/backups/evil</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                <d:response><d:href>/dav/backups/missing</d:href><d:propstat><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
                </d:multistatus>"""
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
            assertEquals(listOf(RemoteEntry("file+name.zip", false, 42)), client.list("backups"))
            val request = server.takeRequest()
            assertEquals("PROPFIND", request.method)
            assertEquals("1", request.getHeader("Depth"))
            assertEquals(Credentials.basic("u", "p", Charsets.UTF_8), request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun traversalAndExternalEntitiesAreRejected() {
        val client = WebDavClient("https://example.com/dav/", "u", "p")
        assertThrows(IllegalArgumentException::class.java) { client.url("backups/../private") }
        assertThrows(IllegalArgumentException::class.java) {
            client.parseListing("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///secret'>]><x/>".toByteArray(), client.url("backups"))
        }
    }

    @Test fun filenamesAreEncodedAsPathSegments() {
        val client = WebDavClient("https://example.com/dav/", "u", "p")
        assertEquals("/dav/backup/a%20%23+.zip", client.url("backup/a #+.zip").encodedPath)
        assertEquals("a #+.zip", client.url("backup/a #+.zip").pathSegments.last())
    }

    @Test fun credentialsAreNotForwardedToAnotherHostDuringDownloadRedirect() {
        val source = MockWebServer()
        val destination = MockWebServer()
        source.start(); destination.start()
        try {
            source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/signed-download")))
            destination.enqueue(MockResponse().setBody("payload"))
            val client = WebDavClient(source.url("/dav/").toString(), "user", "secret", true)
            assertEquals("payload", client.open("file").use { it.readBytes().toString(Charsets.UTF_8) })
            assertNotNull(source.takeRequest().getHeader("Authorization"))
            assertNull(destination.takeRequest().getHeader("Authorization"))
        } finally { source.shutdown(); destination.shutdown() }
    }

    @Test fun existenceUsesAuthenticatedMetadataInsteadOfFollowingAGetSignedHeadRedirect() {
        val source = MockWebServer()
        val signed = MockWebServer()
        source.start(); signed.start()
        try {
            source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", signed.url("/get-only-signed-link")))
            source.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:">
                <d:response><d:href>/dav/release.json</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status>
                <d:prop><d:resourcetype/></d:prop></d:propstat></d:response></d:multistatus>"""))
            val client = WebDavClient(source.url("/dav/").toString(), "u", "p", true)
            assertTrue(client.exists("release.json"))
            assertEquals("HEAD", source.takeRequest().method)
            val metadata = source.takeRequest()
            assertEquals("PROPFIND", metadata.method)
            assertEquals("0", metadata.getHeader("Depth"))
            assertEquals(Credentials.basic("u", "p", Charsets.UTF_8), metadata.getHeader("Authorization"))
            assertEquals(0, signed.requestCount)
        } finally { source.shutdown(); signed.shutdown() }
    }

    @Test fun metadataOnlyConfirmsTheRequestedObjectAndPreservesPermissionErrors() {
        val server = MockWebServer().apply { start() }
        try {
            val client = WebDavClient(server.url("/dav/").toString(), "u", "p", true)
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:">
                <d:response><d:href>/dav/another.json</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                <d:response><d:href>/dav/file.json</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response></d:multistatus>"""))
            assertFalse(client.exists("file.json"))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(403))
            assertTrue(assertThrows(IllegalStateException::class.java) { client.exists("private.json") }.message!!.contains("403"))
        } finally { server.shutdown() }
    }

    @Test fun artifactCopyUsesAnEncodedDestinationAndCannotOverwriteAnExistingFile() {
        val server = MockWebServer().apply { start() }
        try {
            val client = WebDavClient(server.url("/dav/").toString(), "u", "p", true)
            server.enqueue(MockResponse().setResponseCode(201)) // create content directory
            server.enqueue(MockResponse().setResponseCode(201))
            assertTrue(client.copyFile("old.apk", "hash/示例应用.apk"))
            assertEquals("MKCOL", server.takeRequest().method)
            val copy = server.takeRequest()
            assertEquals("COPY", copy.method)
            assertEquals("F", copy.getHeader("Overwrite"))
            assertEquals(client.url("hash/示例应用.apk").toString(), copy.getHeader("Destination"))
            assertEquals(0L, copy.bodySize)
            server.enqueue(MockResponse().setResponseCode(501))
            assertFalse(client.copyFile("old.apk", "name.apk"))
        } finally { server.shutdown() }
    }
}
