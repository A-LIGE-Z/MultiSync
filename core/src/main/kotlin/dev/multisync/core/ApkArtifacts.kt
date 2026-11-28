package dev.multisync.core

import java.io.File
import java.io.IOException

fun apkFileName(applicationName: String, versionName: String, versionCode: Long): String {
    require(versionCode >= 0) { "安装包版本号无效" }
    val version = applicationFolderName(versionName.ifBlank { versionCode.toString() })
    return "${applicationFolderName(applicationName)}_$version.apk"
}

fun namedApkPath(directory: String, applicationName: String, versionName: String, versionCode: Long): String =
    remoteChildPath(directory, apkFileName(applicationName, versionName, versionCode))

fun legacyNamedApkPath(directory: String, sha256: String, applicationName: String): String {
    require(sha256.matches(Regex("[a-f0-9]{64}"))) { "安装包校验值无效" }
    return remoteChildPath(directory, "packages/$sha256/${applicationFolderName(applicationName)}.apk")
}

fun isReleaseApkPath(directory: String, release: Release): Boolean {
    val path = normalizeRemotePath(release.apkPath)
    return path == namedApkPath(directory, release.label, release.versionName, release.versionCode) ||
        path == legacyNamedApkPath(directory, release.sha256, release.label) ||
        path == remoteChildPath(directory, "packages/${release.sha256}.apk")
}

/** Copy a verified legacy artifact on the server when available; records are committed by the caller afterwards. */
fun CloudGateway.publishNamedApk(path: String, legacyPaths: List<String>, snapshot: File, expected: FileDigest, stage: (String) -> Unit = {}): Boolean {
    if (exists(path)) {
        stage("已有安装包回读校验")
        try { verify(path, expected) }
        catch (_: RemoteContentMismatch) { error("远端存在同名但内容不同的安装包：${path.substringAfterLast('/')}，请检查版本号或目标目录；本地副本已保留") }
        return false
    }
    for (legacyPath in legacyPaths.distinct().filter { it != path }) {
        if (!exists(legacyPath)) continue
        stage("校验已有安装包")
        val reusable = try { verify(legacyPath, expected); true } catch (_: RemoteContentMismatch) { false }
        if (reusable) {
            stage("生成名称与版本安装包")
            val copied = try { copyFile(legacyPath, path) }
            catch (exception: Exception) {
                val uncertain = exception is IOException || (exception is WebDavResponseException &&
                    (exception.statusCode >= 500 || exception.statusCode in listOf(409, 412, 423)))
                if (!uncertain) throw exception
                try { verify(path, expected); true }
                catch (verification: Exception) { exception.addSuppressed(verification); throw exception }
            }
            if (copied) {
                stage("远端回读校验")
                verify(path, expected)
                return false
            }
        }
    }
    stage("上传中")
    try { putFile(path, snapshot) }
    catch (exception: Exception) {
        if (exception !is IOException &&
            !(exception is WebDavResponseException && (exception.statusCode >= 500 || exception.statusCode == 423)) &&
            !(exception is OpenListResponseException && (exception.statusCode >= 500 || exception.statusCode == 423))) throw exception
        stage("上传响应中断，回读校验")
        try { verify(path, expected); return true }
        catch (verification: Exception) { exception.addSuppressed(verification); throw exception }
    }
    stage("远端回读校验")
    verify(path, expected)
    return true
}
