// When to sync: at once after a new fix, and through WorkManager with a
// network constraint for retries and the periodic safety net. WorkManager
// wakes the app when connectivity returns, which covers the WireGuard
// tunnel coming up.
package io.github.lopezec007.tracker.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.lopezec007.tracker.TrackerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SyncScheduler(
    private val context: Context,
    private val engine: SyncEngine,
    private val scope: CoroutineScope,
) {
    private var pendingRun: Job? = null

    /** Sync soon (debounced a little so a burst of changes goes in one run). */
    fun requestSoon(delayMs: Long = 400) {
        pendingRun?.cancel()
        pendingRun = scope.launch {
            delay(delayMs)
            runNow()
        }
    }

    /** Sync now in the app scope; a retry is scheduled if anything is left queued. */
    fun requestNow() {
        pendingRun?.cancel()
        pendingRun = scope.launch { runNow() }
    }

    private suspend fun runNow() {
        val result = engine.sync()
        if (result !is SyncResult.Done && result !is SyncResult.NotConfigured) scheduleRetry()
    }

    /** WorkManager, or null where it is not initialised (JVM tests). */
    private fun workManager(): WorkManager? = try {
        WorkManager.getInstance(context)
    } catch (_: IllegalStateException) {
        null
    }

    /** One-time retry that waits for a network and backs off. */
    private fun scheduleRetry() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager()?.enqueueUniqueWork(RETRY_WORK, ExistingWorkPolicy.KEEP, req)
    }

    /** The safety net: every 15 minutes (WorkManager's minimum) when there is a network. */
    fun ensurePeriodic() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager()?.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    companion object {
        const val RETRY_WORK = "tracker-sync-retry"
        const val PERIODIC_WORK = "tracker-sync-periodic"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = TrackerApp.graph(applicationContext)
        return when (graph.syncEngine.sync()) {
            is SyncResult.Done, is SyncResult.NotConfigured -> Result.success()
            is SyncResult.Offline, is SyncResult.WrongServer -> Result.retry()
            is SyncResult.Failed -> if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
