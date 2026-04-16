package com.billsnap.manager.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.billsnap.manager.sync.CloudSyncManager

/**
 * Background periodic sync worker using WorkManager.
 * Syncs bills and customers to/from Firebase when network is available.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val syncManager = CloudSyncManager.getInstance(applicationContext)
            syncManager.syncAll()
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Sync failed", e)
            Result.retry()
        }
    }
}
