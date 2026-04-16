package com.billsnap.manager.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Utility for scheduling/cancelling the periodic Admin→Worker bill sync.
 *
 * Schedule: ~5 times per day → PeriodicWorkRequest with a ~4.8-hour interval (~288 minutes).
 * WorkManager's minimum periodic interval is 15 minutes, so 288 minutes is well within range.
 *
 * Constraints:
 *  - Requires network connectivity
 *  - Uses KEEP policy (won't replace existing schedule if already enqueued)
 */
object BillSyncScheduler {

    private const val TAG = "BillSyncScheduler"

    /**
     * Enqueues a periodic sync that runs ~5 times daily.
     * Safe to call multiple times — uses ExistingPeriodicWorkPolicy.KEEP.
     */
    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<BillSyncWorker>(
            288, TimeUnit.MINUTES  // ~4.8 hours = 5 times per day
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(BillSyncWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BillSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )

        Log.d(TAG, "Periodic bill sync scheduled (every ~4.8 hours)")
    }

    /**
     * Cancels all scheduled sync work. Called when access is revoked.
     */
    fun cancelAllSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(BillSyncWorker.WORK_NAME)
        Log.d(TAG, "All bill sync work cancelled")
    }

    /**
     * Enqueues a one-time immediate sync for initial download or re-grant recovery.
     * Uses KEEP policy so duplicate triggers don't queue multiple runs.
     */
    fun scheduleImmediateSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<BillSyncWorker>()
            .setConstraints(constraints)
            .addTag("bill_sync_immediate")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "bill_sync_immediate",
            ExistingWorkPolicy.KEEP,
            request
        )

        Log.d(TAG, "Immediate bill sync enqueued")
    }
}
