package dev.multisync.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationCatalogTest {
    private val apps = listOf(
        InstalledApp("org.example.notes", "我的笔记", "2.0", 20, false, false),
        InstalledApp("org.example.reader", "Reader", "3.0", 30, false, true),
        InstalledApp("android.settings", "系统设置", "1.0", 1, true, false),
    )
    @Test fun searchesUserApplicationNames() { assertEquals(listOf(apps[0]), filterInstalledApps(apps, "笔记", false)) }
    @Test fun packageSearchIgnoresCaseAndWhitespace() { assertEquals(listOf(apps[1]), filterInstalledApps(apps, "  EXAMPLE.READER ", false)) }
    @Test fun systemApplicationsAreExcludedUntilRequested() {
        assertEquals(apps.take(2), filterInstalledApps(apps, "", false))
        assertEquals(apps, filterInstalledApps(apps, "", true))
    }
    @Test fun unmatchedSearchReturnsNoApplications() { assertEquals(emptyList<InstalledApp>(), filterInstalledApps(apps, "不存在的软件", true)) }
}
