package dev.multisync.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import dev.multisync.app.R
import dev.multisync.app.SyncApplication
import dev.multisync.app.data.Settings
import dev.multisync.core.AppRule
import dev.multisync.core.SyncJson
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.FlowPreview
import android.app.PendingIntent
import dev.multisync.app.MainActivity

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result = coroutineScope {
        setForeground(foregroundInfo())
        val application = applicationContext as SyncApplication
        val observer = launch { application.coordinator.transfer.sample(500L).collect { state -> setForeground(foregroundInfo(state)) } }
        try {
            val scope = inputData.getString("scope")?.let { runCatching { SyncScope.valueOf(it) }.getOrNull() } ?: SyncScope.ALL
            if (application.coordinator.run(scope)) Result.success()
            else if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally { observer.cancel() }
    }

    private fun foregroundInfo(state: SyncTransferState? = null): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("sync", "同步任务", NotificationManager.IMPORTANCE_LOW))
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(applicationContext, "sync") else Notification.Builder(applicationContext)
        val notification = builder.setSmallIcon(R.drawable.ic_notification).setContentTitle("多端同步")
            .setContentText(state?.let { "${it.stage} · ${it.fileName}" } ?: "正在检查软件更新和备份文件")
            .setProgress(100, state?.network?.fraction?.let { (it * 100).toInt() } ?: 0, state?.network?.fraction == null)
            .setContentIntent(PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true).setOngoing(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(1001, notification)
    }
}

object SyncScheduler {
    private fun constraints(settings: Settings): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

    fun schedule(context: Context, settings: Settings) {
        if (settings.baseUrl.isBlank()) { WorkManager.getInstance(context).cancelUniqueWork("periodic-sync"); return }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(settings.intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(constraints(settings)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("periodic-sync", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelNow(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("manual-sync")
    }

    fun now(context: Context, settings: Settings, scope: SyncScope = SyncScope.ALL) {
        // Manual "check now" should run on any network (incl. mobile data / local OpenList) and replace any stuck queued work.
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("scope" to scope.name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("manual-sync", ExistingWorkPolicy.REPLACE, request)
    }
}

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED && intent.action != Intent.ACTION_PACKAGE_REPLACED) return
        if (intent.data?.scheme != "package") return
        val changed = intent.data?.schemeSpecificPart ?: return
        if (changed == context.packageName) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val application = context.applicationContext as SyncApplication
                val matches = application.database.dao().rules().any {
                    it.kind == "app" && runCatching { SyncJson.decodeFromString<AppRule>(it.payload) }
                        .getOrNull()?.let { rule -> rule.enabled && rule.packageName == changed } == true
                }
                if (matches) SyncScheduler.now(context, application.settings.state.value)
            } finally { pending.finish() }
        }
    }
}
