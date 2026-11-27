package dev.multisync.core

import java.io.File
import java.io.InputStream
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RemoteVersionsTest {
    @Test fun softwareAndFileRootsBothContributeVersionsAndBackupInternalDirectoriesAreSkipped() {
        val cloud = MemoryCloud()
        val software = "team/software"
        val files = "team/files"
        val softwareDir = "$software/${local.packageName}"
        val fileDir = "$files/${local.packageName}"
        cloud.listings[software] = listOf(RemoteEntry(local.packageName, true, 0))
        cloud.listings[files] = listOf(RemoteEntry(local.packageName, true, 0), RemoteEntry("objects", true, 0), RemoteEntry("records", true, 0))
        listOf(softwareDir to 20L, fileDir to 30L).forEach { (path, code) ->
            cloud.listings["$path/releases"] = listOf(RemoteEntry("one.json", false, 100))
            cloud.bytes["$path/releases/one.json"] = SyncJson.encodeToString(release(code).copy(apkPath = "$path/packages/${"a".repeat(64)}.apk")).toByteArray()
        }
        val result = RemoteVersionCatalog(cloud).readSources(listOf(software, files, "/$software/"))
        assertEquals(2, result.directoryCount)
        assertEquals(2, result.versions.size)
        assertTrue(result.problems.isEmpty())
        assertEquals(30L, compareAppVersions(local, result.versions, setOf("trusted"), 35, setOf("arm64-v8a")).latest?.release?.versionCode)
    }

    @Test fun recordsPublishedDirectlyInWebDavRootRemainReadable() {
        val cloud = MemoryCloud()
        cloud.listings["/"] = listOf(RemoteEntry("releases", true, 0), RemoteEntry("packages", true, 0))
        cloud.listings["releases"] = listOf(RemoteEntry("one.json", false, 100))
        cloud.bytes["releases/one.json"] = SyncJson.encodeToString(release().copy(apkPath = "packages/${"a".repeat(64)}.apk")).toByteArray()
        val result = RemoteVersionCatalog(cloud).readSources(listOf("/"))
        assertEquals("/", result.versions.single().directory)
        assertTrue(result.problems.isEmpty())
    }

    private val local = InstalledApp("com.example.app", "示例", "9.9", 10, false, false)
    private val directory = "多端同步/apps/com.example.app"
    private fun release(code: Long = 20, signer: String = "trusted", minSdk: Int = 24, abi: String = "arm64-v8a") = Release(
        packageName = local.packageName, label = local.label, versionCode = code, versionName = "1.0",
        signerDigests = listOf(signer), apkPath = "$directory/packages/${"a".repeat(64)}.apk", sha256 = "a".repeat(64),
        size = 1024, minSdk = minSdk, supportedAbis = listOf(abi), publisherId = "phone", publishedAt = 100,
    )
    private fun compare(vararg releases: Release) = compareAppVersions(local, releases.map { CatalogRelease(directory, it) },
        setOf("trusted"), 35, setOf("arm64-v8a"))

    @Test fun orderingUsesVersionCodeInsteadOfDisplayNameOrUploadTime() {
        val result = compare(release(11).copy(versionName = "99.0", publishedAt = 9999), release(20).copy(versionName = "1.0", publishedAt = 1))
        assertEquals(VersionRelation.REMOTE_NEWER, result.relation)
        assertEquals(20L, result.latest?.release?.versionCode)
        assertEquals(20L, result.compatibleUpdate?.release?.versionCode)
    }

    @Test fun sameOlderAndMissingVersionsRemainVisibleWithoutOfferingAnUpdate() {
        assertEquals(VersionRelation.SAME, compare(release(10)).relation)
        assertTrue(compare(release(10)).latestCompatible)
        assertNull(compare(release(10)).compatibleUpdate)
        assertEquals(VersionRelation.LOCAL_NEWER, compare(release(9)).relation)
        assertNull(compare(release(9)).compatibleUpdate)
        assertEquals(VersionRelation.NO_REMOTE, compare().relation)
    }

    @Test fun newestIncompatibleReleaseDoesNotHideALowerCompatibleUpdate() {
        val result = compare(release(30, signer = "other"), release(20))
        assertEquals(30L, result.latest?.release?.versionCode)
        assertFalse(result.latestCompatible)
        assertEquals(20L, result.compatibleUpdate?.release?.versionCode)
        assertNull(compare(release(30, minSdk = 36), release(20, abi = "x86_64")).compatibleUpdate)
    }

    @Test fun matchingVariantWinsWhenSeveralPublishersHaveTheSameVersionCode() {
        val result = compare(release().copy(publisherId = "old", publishedAt = 1), release(signer = "other").copy(publisherId = "new", publishedAt = 999))
        assertTrue(result.latestCompatible)
        assertEquals("old", result.latest?.release?.publisherId)
        assertEquals(2, result.versions.size)
    }

    @Test fun packageIdentityAndUnknownLocalSignersDoNotBecomeCompatibleUpdates() {
        assertEquals(VersionRelation.NO_REMOTE, compare(release().copy(packageName = "com.other.app")).relation)
        val result = compareAppVersions(local, listOf(CatalogRelease(directory, release())), emptySet(), 35, setOf("arm64-v8a"), "unreadable")
        assertEquals(VersionRelation.REMOTE_NEWER, result.relation)
        assertFalse(result.latestCompatible)
        assertNull(result.compatibleUpdate)
        assertEquals("unreadable", result.identityError)
    }

    @Test fun catalogDiscoversSoftwareWithoutALocalRuleAndOnlyReadsMetadata() {
        val server = MockWebServer()
        server.start()
        try {
            val parentPath = "/dav/%E5%A4%9A%E7%AB%AF%E5%90%8C%E6%AD%A5/apps"
            server.enqueue(MockResponse().setResponseCode(207).setBody(listing(parentPath, local.packageName, true)))
            server.enqueue(MockResponse().setResponseCode(207).setBody(listing("$parentPath/${local.packageName}/releases", "20.json", false)))
            server.enqueue(MockResponse().setBody(SyncJson.encodeToString(release())))
            val result = RemoteVersionCatalog(WebDavClient(server.url("/dav/").toString(), "user", "password", true)).read("多端同步/apps")
            assertEquals(listOf(CatalogRelease(directory, release())), result.versions)
            assertTrue(result.problems.isEmpty())
            assertEquals(1, result.directoryCount)
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("PROPFIND", server.takeRequest().method)
            val read = server.takeRequest()
            assertEquals("GET", read.method)
            assertTrue(read.path!!.endsWith("/releases/20.json"))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun customRulePathsAndSingleSoftwareDirectoriesAreSupportedWithoutDuplicates() {
        val cloud = MemoryCloud()
        cloud.listings[directory] = listOf(RemoteEntry("packages", true, 0), RemoteEntry("releases", true, 0))
        cloud.listings["$directory/releases"] = listOf(RemoteEntry("one.json", false, 100))
        cloud.bytes["$directory/releases/one.json"] = SyncJson.encodeToString(release()).toByteArray()
        val result = RemoteVersionCatalog(cloud).read(directory, listOf("/$directory/", directory))
        assertEquals(1, result.directoryCount)
        assertEquals(1, result.versions.size)
        assertEquals(1, cloud.reads.size)
    }

    @Test fun failuresAreReportedSeparatelyFromMissingVersionsAndOtherDirectoriesStillLoad() {
        val cloud = MemoryCloud()
        cloud.failures.add("多端同步/apps")
        cloud.listings["$directory/releases"] = listOf(RemoteEntry("one.json", false, 100))
        cloud.bytes["$directory/releases/one.json"] = SyncJson.encodeToString(release()).toByteArray()
        val result = RemoteVersionCatalog(cloud).read("多端同步/apps", listOf(directory))
        assertEquals(1, result.versions.size)
        assertEquals("多端同步/apps", result.problems.single().path)
        assertTrue(RemoteVersionCatalog(MemoryCloud()).read("多端同步/apps").problems.isEmpty())
    }

    @Test fun malformedRecordsAndForeignApkPathsAreExcludedWhileValidHistoryRemains() {
        val cloud = MemoryCloud()
        cloud.listings["$directory/releases"] = listOf("valid", "bad", "outside", "future").map { RemoteEntry("$it.json", false, 100) }
        cloud.bytes["$directory/releases/valid.json"] = SyncJson.encodeToString(release()).toByteArray()
        cloud.bytes["$directory/releases/bad.json"] = "invalid JSON".toByteArray()
        cloud.bytes["$directory/releases/outside.json"] = SyncJson.encodeToString(release().copy(apkPath = "other/packages/a.apk")).toByteArray()
        cloud.bytes["$directory/releases/future.json"] = SyncJson.encodeToString(release().copy(schema = 2)).toByteArray()
        val result = RemoteVersionCatalog(cloud).read("多端同步/apps", listOf(directory))
        assertEquals(1, result.versions.size)
        assertEquals(3, result.problems.size)
    }

    @Test fun flatVersionsAndBothLegacyLayoutsAreReadableButWrongNamesVersionsAndHashesAreRejected() {
        val cloud = MemoryCloud()
        cloud.listings[directory] = listOf(RemoteEntry("releases", true, 0))
        val legacy = release()
        val previous = release(21).copy(apkPath = legacyNamedApkPath(directory, legacy.sha256, legacy.label))
        val named = release(22).copy(versionName = "2.2", apkPath = namedApkPath(directory, legacy.label, "2.2", 22))
        cloud.listings["$directory/releases"] = listOf("legacy", "previous", "named", "wrong-name", "wrong-version", "wrong-hash").map { RemoteEntry("$it.json", false, 100) }
        cloud.bytes["$directory/releases/legacy.json"] = SyncJson.encodeToString(legacy).toByteArray()
        cloud.bytes["$directory/releases/named.json"] = SyncJson.encodeToString(named).toByteArray()
        cloud.bytes["$directory/releases/previous.json"] = SyncJson.encodeToString(previous).toByteArray()
        cloud.bytes["$directory/releases/wrong-name.json"] = SyncJson.encodeToString(named.copy(apkPath = named.apkPath.substringBeforeLast('/') + "/Other.apk")).toByteArray()
        cloud.bytes["$directory/releases/wrong-version.json"] = SyncJson.encodeToString(named.copy(apkPath = namedApkPath(directory, legacy.label, "9.9", 99))).toByteArray()
        cloud.bytes["$directory/releases/wrong-hash.json"] = SyncJson.encodeToString(previous.copy(apkPath = legacyNamedApkPath(directory, "b".repeat(64), legacy.label))).toByteArray()
        val result = RemoteVersionCatalog(cloud).read(directory)
        assertEquals(3, result.versions.size)
        assertEquals(3, result.problems.size)
        assertEquals(named, compareAppVersions(local, result.versions, setOf("trusted"), 35, setOf("arm64-v8a")).latest?.release)
        assertTrue(cloud.reads.all { it.endsWith(".json") })
    }

    private fun listing(parent: String, name: String, collection: Boolean) = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$parent/$name</d:href>
        <d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype>
        <d:getcontentlength>100</d:getcontentlength></d:prop></d:propstat></d:response></d:multistatus>"""

    private class MemoryCloud : CloudGateway {
        val listings = mutableMapOf<String, List<RemoteEntry>>()
        val bytes = mutableMapOf<String, ByteArray>()
        val failures = mutableSetOf<String>()
        val reads = mutableListOf<String>()
        override fun list(path: String): List<RemoteEntry> { check(path !in failures) { "HTTP 401" }; return listings[path].orEmpty() }
        override fun open(path: String): InputStream { reads.add(path); return bytes.getValue(path).inputStream() }
        override fun exists(path: String): Boolean = error("Version browsing must not inspect APKs")
        override fun putFile(path: String, file: File): Unit = error("Version browsing must not write files")
        override fun putBytes(path: String, bytes: ByteArray): Unit = error("Version browsing must not publish records")
    }
}
