package dev.multisync.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import dev.multisync.app.data.InstalledAppsRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstalledAppsRepositoryTest {
    @Test fun readsLabelPackageVersionAndSplitMetadataFromPackageManager() {
        val context = RuntimeEnvironment.getApplication()
        val info = PackageInfo().apply {
            packageName = "org.example.catalog"
            versionName = "2.5"
            setLongVersionCode(250)
            applicationInfo = ApplicationInfo().apply {
                packageName = "org.example.catalog"
                nonLocalizedLabel = "测试阅读器"
                splitSourceDirs = arrayOf("/data/app/catalog/split.arm64.apk")
            }
        }
        shadowOf(context.packageManager).installPackage(info)
        val application = InstalledAppsRepository(context).read().single { it.packageName == info.packageName }
        assertEquals("测试阅读器", application.label)
        assertEquals("2.5", application.versionName)
        assertEquals(250L, application.versionCode)
        assertTrue(application.isSplit)
        assertFalse(application.isSystem)
    }
    @Test fun marksSystemApplicationsAndExcludesThisClient() {
        val context = RuntimeEnvironment.getApplication()
        val info = PackageInfo().apply {
            packageName = "org.example.system"
            applicationInfo = ApplicationInfo().apply {
                packageName = "org.example.system"
                nonLocalizedLabel = "系统应用"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        }
        shadowOf(context.packageManager).installPackage(info)
        val apps = InstalledAppsRepository(context).read()
        assertTrue(apps.single { it.packageName == info.packageName }.isSystem)
        assertFalse(apps.any { it.packageName == context.packageName })
    }
}
