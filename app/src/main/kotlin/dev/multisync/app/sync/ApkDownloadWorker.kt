package dev.multisync.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.*
import dev.multisync.app.MainActivity
import dev.multisync.app.R
import dev.multisync.app.SyncApplication
import dev.multisync.app.data.Settings
import dev.multisync.core.*
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

internal fun apkConnectionKey(settings: Settings): String = MessageDigest.getInstance("SHA-256").digest(
    listOf(settings.baseUrl, settings.username, settings.softwareUploadPath, settings.fileUploadPath,
        settings.allowHttp.toString(), settings.sshEnabled.toString()).joinToString("\u0000").toByteArray()
).joinToString("") { "%02x".format(it) }

class ApkDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result = coroutineScope {
        val app = applicationContext as SyncApplication
        var finished = false
        val destination = inputData.getString(DESTINATION)?.let(Uri::parse)
        val observer = launch { app.coordinator.transfer.sample(500L).collect {
            if (it.manualDownload) try { setForeground(foregroundInfo(it)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A progress notification failure must not discard a verified APK. */ }
        } }
        try {
            setForeground(foregroundInfo())
            val record = SyncJson.decodeFromString<CatalogRelease>(inputData.getString(RECORD) ?: error("缺少下载版本信息"))
            val dest = checkNotNull(destination) { "缺少保存位置" }
            val result = app.coordinator.downloadApk(record, dest, inputData.getString(CONNECTION).orEmpty())
            finished = true
            val install = runCatching { app.coordinator.autoInstallDownloaded(dest, result.name) }
                .getOrElse { it.message?.take(200) ?: "自动安装未完成" }
            Result.success(workDataOf(SAVED_URI to result.uri, FILE_NAME to result.name, INSTALL to install))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (exception: Exception) { Result.failure(workDataOf(ERROR to (exception.message?.take(250) ?: "下载失败，请重新下载"))) }
        finally {
            observer.cancel()
            if (!finished && destination != null) withContext(NonCancellable + Dispatchers.IO) { app.coordinator.discardIncompleteApk(destination) }
        }
    }

    private fun foregroundInfo(state: SyncTransferState? = null): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("apk-download", "安装包下载", NotificationManager.IMPORTANCE_LOW))
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(applicationContext, "apk-download") else Notification.Builder(applicationContext)
        val notification = builder.setSmallIcon(R.drawable.ic_notification).setContentTitle("安装包下载")
            .setContentText(state?.let { "${it.stage} · ${it.fileName}" } ?: "等待网络或当前同步任务完成")
            .setProgress(100, state?.network?.fraction?.let { (it * 100).toInt() } ?: 0, state?.network?.fraction == null)
            .setContentIntent(PendingIntent.getActivity(applicationContext, 1, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true).setOngoing(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(1002, notification)
    }

    companion object {
        const val UNIQUE_WORK = "manual-apk-download"
        const val PREFERENCES = "apk-download-state"
        const val WORK_ID = "work-id"
        const val RECORD = "record"
        const val DESTINATION = "destination"
        const val CONNECTION = "connection"
        const val SAVED_URI = "saved-uri"
        const val FILE_NAME = "file-name"
        const val INSTALL = "install"
        const val ERROR = "error"
        const val PACKAGE_TAG = "apk-package:"
        const val SHA_TAG = "apk-sha:"
        const val NAME_TAG = "apk-name:"

        fun enqueue(context: Context, record: CatalogRelease, destination: Uri, settings: Settings) {
            val payload = SyncJson.encodeToString(record)
            require(payload.toByteArray().size <= 8 * 1024) { "发布信息过大，请检查远端记录" }
            val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
                .setInputData(workDataOf(RECORD to payload, DESTINATION to destination.toString(), CONNECTION to apkConnectionKey(settings)))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .addTag(PACKAGE_TAG + record.release.packageName).addTag(SHA_TAG + record.release.sha256)
                .addTag(NAME_TAG + apkFileName(record.release.label, record.release.versionName, record.release.versionCode)).build()
            check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(WORK_ID, request.id.toString())
                .putString(DESTINATION, destination.toString()).commit()) { "无法保存下载任务" }
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
