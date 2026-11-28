package dev.multisync.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

val SyncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Canonicalises a user-entered WebDAV base URL.
 *
 * A trailing slash is required: OpenList/Nginx answer a PROPFIND to `/dav` with a 301 redirect to
 * `/dav/`, and following that redirect for a PROPFIND breaks the request (the body is dropped and the
 * method downgraded), which previously showed up as a version scan that hung at "读取中".
 * Storing the normalised value prevents that class of failure. Blank input is rejected.
 */
fun normalizeWebDavUrl(value: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty()) { "WebDAV 地址不能为空" }
    require(trimmed.none { it.isWhitespace() }) { "WebDAV 地址不能包含空白字符" }
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val adjusted = if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    val url = adjusted.toHttpUrlOrNull() ?: error("WebDAV 地址格式不正确")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "连接地址不能包含凭据或查询参数"
    }
    val text = url.toString()
    return if (text.endsWith("/")) text else "$text/"
}

@Serializable
data class AppRule(
    val id: String,
    val name: String,
    val packageName: String,
    val remotePath: String,
    val exportTreeUri: String = "",
    val deleteExportAfterVerify: Boolean = true,
    val enabled: Boolean = true,
    val publish: Boolean = true,
    val subscribe: Boolean = false,
    val autoInstall: Boolean = false,
    val trustedSigners: List<String> = emptyList(),
    val followUploadPath: Boolean = false,
    val applicationName: String = "",
    val legacyRemotePaths: List<String> = emptyList(),
)

@Serializable
data class BackupRule(
    val id: String,
    val name: String,
    val sourceTreeUri: String,
    val remotePath: String,
    val enabled: Boolean = true,
    val recursive: Boolean = false,
    val deleteAfterVerify: Boolean = true,
    val stableSeconds: Long = 120,
    val followUploadPath: Boolean = false,
)

@Serializable
data class ArchiveRecord(
    val schema: Int = 1,
    val deviceId: String,
    val ruleId: String,
    val sourceIdentity: String,
    val originalName: String,
    val relativePath: String = "",
    val objectPath: String,
    val sha256: String,
    val size: Long,
    val archivedAt: Long,
)

@Serializable
data class Release(
    val schema: Int = 1,
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String,
    val signerDigests: List<String>,
    val apkPath: String,
    val sha256: String,
    val size: Long,
    val minSdk: Int,
    val supportedAbis: List<String> = emptyList(),
    val publisherId: String,
    val publishedAt: Long,
)

data class FileDigest(val sha256: String, val size: Long)
data class RemoteEntry(val name: String, val isDirectory: Boolean, val size: Long)

object VersionPolicy {
    fun isCompatible(release: Release, packageName: String, trustedSigners: Set<String>, sdk: Int, abis: Set<String>): Boolean =
        release.schema == 1 && release.packageName == packageName && trustedSigners.isNotEmpty() &&
            release.signerDigests.toSet() == trustedSigners && release.minSdk <= sdk &&
            (release.supportedAbis.isEmpty() || release.supportedAbis.any(abis::contains)) &&
            release.sha256.matches(Regex("[a-f0-9]{64}")) && release.size > 0

    fun choose(
        releases: List<Release>, packageName: String, currentVersion: Long,
        trustedSigners: Set<String>, sdk: Int, abis: Set<String>,
    ): Release? = releases.asSequence().filter {
        it.versionCode > currentVersion && isCompatible(it, packageName, trustedSigners, sdk, abis)
    }.sortedWith(compareByDescending<Release> { it.versionCode }.thenBy { it.sha256 }).firstOrNull()
}

fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

fun validatePackageName(value: String) {
    require(value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "软件包名格式不正确" }
}

fun normalizeRemotePath(value: String): String {
    val path = value.trim().trim('/')
    require(path.isNotEmpty()) { "远端目录不能为空" }
    require(path.split('/').none { it.isBlank() || it == "." || it == ".." || it.any { c -> c.isISOControl() || c == '\\' } }) {
        "远端路径包含无效片段"
    }
    return path
}

fun normalizeRemoteDirectory(value: String): String = if (value.trim() == "/") "/" else normalizeRemotePath(value)

fun remoteChildPath(directory: String, child: String): String {
    val parent = normalizeRemoteDirectory(directory)
    val name = normalizeRemotePath(child)
    return if (parent == "/") name else "$parent/$name"
}

fun applicationFolderName(label: String): String = label.trim()
    .map { if (it.isISOControl() || it in "/\\<>:\"|?*") '_' else it }.joinToString("")
    .trim().trimEnd('.', ' ').take(100).ifBlank { "未命名应用" }

fun appUploadPath(rule: AppRule, configuredDirectory: String): String {
    validatePackageName(rule.packageName)
    val folder = applicationFolderName(rule.applicationName.ifBlank { rule.name })
    if (rule.followUploadPath) return remoteChildPath(configuredDirectory, folder)
    val previous = normalizeRemotePath(rule.remotePath)
    return if (previous.substringAfterLast('/') == rule.packageName)
        remoteChildPath(previous.substringBeforeLast('/', "/"), folder) else previous
}

fun backupUploadPath(rule: BackupRule, configuredDirectory: String): String =
    normalizeRemoteDirectory(if (rule.followUploadPath) configuredDirectory else rule.remotePath)
