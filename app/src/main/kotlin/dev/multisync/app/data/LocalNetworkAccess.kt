package dev.multisync.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

fun hasLocalNetworkAccess(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
    ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

fun needsLocalNetwork(settings: Settings): Boolean {
    if (settings.sshEnabled) return true
    val host = settings.baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return false
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    return host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host == "::1" ||
        host.startsWith("fe80:") || host.startsWith("fc") && host.contains(':') || host.startsWith("fd") && host.contains(':') ||
        octets.size == 4 && (octets[0] in setOf(10, 127) || octets[0] == 192 && octets[1] == 168 ||
            octets[0] == 169 && octets[1] == 254 || octets[0] == 172 && octets[1] in 16..31)
}
