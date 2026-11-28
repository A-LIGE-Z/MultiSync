package dev.multisync.app

import android.os.Looper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.lifecycle.ViewModelStore
import dev.multisync.app.data.InstalledAppsRepository
import dev.multisync.app.ui.SyncViewModel
import dev.multisync.app.ui.RemoteVersionsState
import dev.multisync.app.ui.remoteVersionStatus
import dev.multisync.app.data.RuleEntity
import dev.multisync.core.*
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteVersionsStateTest {
    @Test fun refreshRereadsInstalledVersionAndRemoteVersionInsteadOfKeepingTheFirstComparison() = catalogFixture { app, model, version ->
        model.loadRemoteVersions()
        awaitRead(model)
        val first = model.remoteVersions.value.comparisons.single()
        assertEquals(VersionRelation.REMOTE_NEWER, first.relation)
        assertEquals(20L, first.latest!!.release.versionCode)
        installApp(app, 30)
        version[0] = 30
        model.loadRemoteVersions(force = true)
        awaitRead(model)
        val refreshed = model.remoteVersions.value.comparisons.single()
        assertEquals(30L, refreshed.local.versionCode)
        assertEquals(30L, refreshed.latest!!.release.versionCode)
        assertEquals(VersionRelation.SAME, refreshed.relation)
    }

    @Test fun completingASyncAutomaticallyRereadsAnAlreadyUsedComparison() = catalogFixture { app, model, version ->
        runBlocking {
            app.database.dao().putRule(RuleEntity("test", "app", SyncJson.encodeToString(AppRule(
                "test", "测试软件", "org.example.versions", "apps/demo", publish = false, subscribe = true,
                trustedSigners = listOf("trusted"), autoInstall = false,
            ))))
        }
        model.loadRemoteVersions()
        awaitRead(model)
        val checkedAt = model.remoteVersions.value.checkedAt
        version[0] = 30
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch { app.coordinator.run() }
        try {
            for (attempt in 0 until 1000) {
                shadowOf(Looper.getMainLooper()).idle()
                if (job.isCompleted && !model.remoteVersions.value.loading && model.remoteVersions.value.checkedAt > checkedAt) break
                Thread.sleep(10)
            }
            assertTrue(job.isCompleted)
            assertTrue(model.remoteVersions.value.checkedAt > checkedAt)
            assertEquals(30L, model.remoteVersions.value.comparisons.single().latest!!.release.versionCode)
        } finally { scope.cancel() }
    }

    @Test fun pendingAndFailedReadsAreNotShownAsMissingRemoteVersions() {
        assertEquals("等待读取远端版本", remoteVersionStatus(RemoteVersionsState(), null))
        assertEquals("正在更新版本对比…", remoteVersionStatus(RemoteVersionsState(loading = true), null))
        assertEquals("版本读取失败，请重试", remoteVersionStatus(RemoteVersionsState(error = "HTTP 401"), null))
        assertEquals("读取失败，显示上次结果", remoteVersionStatus(RemoteVersionsState(loaded = true, checkedAt = 1, error = "HTTP 401"), null))
    }

    private fun installApp(app: SyncApplication, version: Long) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "org.example.versions"
            versionName = "${version / 10}.0"
            setLongVersionCode(version)
            applicationInfo = ApplicationInfo().apply {
                packageName = "org.example.versions"
                nonLocalizedLabel = "测试软件"
            }
        })
    }

    private fun catalogFixture(run: (SyncApplication, SyncViewModel, LongArray) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as SyncApplication
        installApp(app, 10)
        val version = longArrayOf(20)
        val server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (request.method == "GET") {
                        val release = Release(packageName = "org.example.versions", label = "测试软件", versionCode = version[0],
                            versionName = "${version[0] / 10}.0", signerDigests = listOf("trusted"),
                            apkPath = namedApkPath("apps/demo", "测试软件", "${version[0] / 10}.0", version[0]),
                            sha256 = "a".repeat(64), size = 100, minSdk = 24, supportedAbis = emptyList(), publisherId = "test", publishedAt = 1)
                        return MockResponse().setBody(SyncJson.encodeToString(release))
                    }
                    val child = when (path) {
                        "/dav/apps" -> "<d:response><d:href>/dav/apps/demo/</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>"
                        "/dav/apps/demo/releases" -> "<d:response><d:href>/dav/apps/demo/releases/latest.json</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:resourcetype/><d:getcontentlength>100</d:getcontentlength></d:prop></d:propstat></d:response>"
                        else -> ""
                    }
                    return MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$child</d:multistatus>")
                        .setBodyDelay(80, TimeUnit.MILLISECONDS)
                }
            }
            start()
        }
        app.settings.save(app.settings.state.value.copy(baseUrl = server.url("/dav/").toString(), allowHttp = true,
            softwareUploadPath = "apps", fileUploadPath = "files"), null)
        val model = SyncViewModel(app)
        val store = ViewModelStore().apply { put("versions", model) }
        try { run(app, model, version) } finally { store.clear(); server.shutdown() }
    }

    @Test fun directoryBrowsingWithAnUnsavedDraftDoesNotOverwriteStoredConfiguration() {
        val application = RuntimeEnvironment.getApplication() as SyncApplication
        val before = application.settings.state.value
        val model = SyncViewModel(application)
        val store = ViewModelStore().apply { put("versions", model) }
        try {
            model.browseRemoteDirectories(before.copy(baseUrl = "", softwareUploadPath = "draft/software"), "draft password")
            repeat(500) {
                shadowOf(Looper.getMainLooper()).idle()
                if (!model.remoteDirectories.value.loading) return@repeat
                Thread.sleep(10)
            }
            assertTrue(model.remoteDirectories.value.error.contains("先填写 WebDAV"))
            assertFalse(model.remoteDirectories.value.loaded)
            assertEquals(before, application.settings.state.value)
            model.cancelDirectoryBrowse()
            assertFalse(model.remoteDirectories.value.loading)
        } finally { store.clear() }
    }

    @Test fun missingConnectionIsAnErrorInsteadOfAnEmptyRemoteCatalog() {
        val application = RuntimeEnvironment.getApplication() as SyncApplication
        val model = SyncViewModel(application)
        val store = ViewModelStore().apply { put("versions", model) }
        try {
            model.loadRemoteVersions()
            awaitRead(model)
            assertFalse(model.remoteVersions.value.loaded)
            assertTrue(model.remoteVersions.value.error.contains("请先配置 OpenList"))
            assertEquals(0L, model.remoteVersions.value.checkedAt)
        } finally { store.clear() }
    }

    @Test fun anEmptyPackageListDoesNotMisreportThatRemoteSoftwareIsAbsent() {
        val application = RuntimeEnvironment.getApplication() as SyncApplication
        assertTrue(InstalledAppsRepository(application).read().isEmpty())
        application.settings.save(application.settings.state.value.copy(baseUrl = "http://127.0.0.1:1/dav/", allowHttp = true), null)
        val model = SyncViewModel(application)
        val store = ViewModelStore().apply { put("versions", model) }
        try {
            model.loadRemoteVersions()
            awaitRead(model)
            assertFalse(model.remoteVersions.value.loaded)
            assertTrue(model.remoteVersions.value.error.contains("获取已安装应用信息"))
            assertEquals(0, model.remoteVersions.value.completedDirectories)
            assertTrue(model.remoteVersions.value.comparisons.isEmpty())
        } finally { store.clear() }
    }

    private fun awaitRead(model: SyncViewModel) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!model.remoteVersions.value.loading) return
            Thread.sleep(10)
        }
        fail("Version loading did not finish")
    }
}
