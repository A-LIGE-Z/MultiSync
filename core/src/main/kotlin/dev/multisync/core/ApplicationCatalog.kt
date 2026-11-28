package dev.multisync.core

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val isSplit: Boolean,
)

fun filterInstalledApps(apps: List<InstalledApp>, query: String, showSystem: Boolean): List<InstalledApp> {
    val search = query.trim()
    return apps.filter {
        (showSystem || !it.isSystem) && (search.isEmpty() || it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true))
    }
}
