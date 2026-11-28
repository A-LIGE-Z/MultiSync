package dev.multisync.core

import java.io.File
import java.security.MessageDigest

data class ApkIdentity(val packageName: String, val versionCode: Long, val versionName: String,
    val signers: Set<String>, val minSdk: Int, val abis: Set<String>)

fun validateDownloadedApk(identity: ApkIdentity, release: Release) {
    check(identity.packageName == release.packageName && identity.versionCode == release.versionCode && identity.versionName == release.versionName) {
        "安装包的包名或版本与发布记录不一致"
    }
    check(identity.signers.isNotEmpty() && identity.signers == release.signerDigests.toSet()) { "安装包签名与发布记录不一致" }
    check(identity.minSdk == release.minSdk && identity.abis == release.supportedAbis.toSet()) { "安装包的系统或架构信息与发布记录不一致" }
}

/** Downloads into an owned, disposable snapshot. Failed or cancelled snapshots are removed. */
fun CloudGateway.downloadVerifiedApk(record: CatalogRelease, snapshot: File,
    progress: (TransferProgress) -> Unit = {}, stage: (String) -> Unit = {}, validateApk: (File, Release) -> Unit) {
    val release = record.release
    val tracker = TransferTracker(TransferDirection.DOWNLOAD, release.apkPath, release.size, progress)
    try {
        validatePackageName(release.packageName)
        check(release.schema == 1 && release.versionCode >= 0 && release.minSdk > 0 && release.size > 0 &&
            release.sha256.matches(Regex("[a-f0-9]{64}")) && release.signerDigests.isNotEmpty() &&
            isReleaseApkPath(record.directory, release)) { "远端发布信息无效" }
        stage("确认远端发布信息")
        val fresh = RemoteVersionCatalog(this).read(record.directory, listOf(record.directory))
        check(record in fresh.versions) { "远端发布信息已变化或无法读取，请刷新版本后重新下载" }
        stage("下载安装包")
        tracker.start()
        val sha = MessageDigest.getInstance("SHA-256")
        var size = 0L
        open(release.apkPath).use { input -> snapshot.outputStream().use { output ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancellation()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                check(count.toLong() <= release.size - size) { "安装包超过发布记录中的大小" }
                output.write(buffer, 0, count)
                sha.update(buffer, 0, count)
                size += count
                tracker.add(count.toLong())
            }
        } }
        stage("校验安装包")
        check(size == release.size && sha.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "安装包大小或 SHA-256 校验失败" }
        validateApk(snapshot, release)
        tracker.complete()
        stage("安装包校验通过")
    } catch (exception: Exception) {
        if (Thread.currentThread().isInterrupted || exception is java.io.InterruptedIOException) tracker.cancelled() else tracker.failed()
        snapshot.delete()
        throw exception
    }
}
