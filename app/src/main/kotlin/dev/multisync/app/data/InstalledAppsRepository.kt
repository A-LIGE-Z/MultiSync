package dev.multisync.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import dev.multisync.core.InstalledApp
import java.text.Collator
import java.util.Locale

class InstalledAppsRepository(context: Context) {
    private val packageManager = context.packageManager
    private val ownPackage = context.packageName
    private val iconCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    @Suppress("DEPRECATION")
    fun read(): List<InstalledApp> {
        val packages = if (Build.VERSION.SDK_INT >= 33) packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        else packageManager.getInstalledPackages(0)
        val collator = Collator.getInstance(Locale.getDefault())
        return packages.mapNotNull { info ->
            val applicationInfo = info.applicationInfo ?: return@mapNotNull null
            if (info.packageName == ownPackage) return@mapNotNull null
            InstalledApp(
                packageName = info.packageName,
                label = runCatching { applicationInfo.loadLabel(packageManager).toString() }.getOrDefault(info.packageName),
                versionName = info.versionName.orEmpty(),
                versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
                isSystem = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isSplit = !applicationInfo.splitSourceDirs.isNullOrEmpty(),
            )
        }.sortedWith { first, second -> collator.compare(first.label, second.label).let { if (it == 0) first.packageName.compareTo(second.packageName) else it } }
    }

    fun icon(packageName: String, pixels: Int): Bitmap? {
        val key = "$packageName:$pixels"
        iconCache.get(key)?.let { return it }
        return runCatching {
            packageManager.getApplicationIcon(packageName).toBitmap(pixels, pixels).also { iconCache.put(key, it) }
        }.getOrNull()
    }

    fun invalidateIcons() { iconCache.evictAll() }
}
