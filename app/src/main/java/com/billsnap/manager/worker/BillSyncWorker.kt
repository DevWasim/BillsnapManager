package com.billsnap.manager.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.billsnap.manager.security.PermissionManager
import com.billsnap.manager.sync.BillSyncRepository

/**
 * Background worker for periodic Admin→Worker bill sync.
 *
 * TEMPORARILY SIMPLIFIED: just calls fullSyncForWorker.
 * Incremental/timestamp logic will be reintroduced after deterministic path is proven.
 */
class BillSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "BillSyncWorker"
        const val WORK_NAME = "bill_sync_periodic"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "BillSyncWorker starting (background)")

        val session = PermissionManager.session.value
        val workerId = session.uid
        val shopId = session.shopId

        if (workerId.isEmpty() || shopId.isNullOrEmpty()) {
            Log.w(TAG, "No valid session, skipping background sync")
            return Result.success()
        }

        if (!session.canViewBills) {
            Log.d(TAG, "No viewBills access, skipping")
            return Result.success()
        }

        return try {
            val syncRepo = BillSyncRepository.getInstance(applicationContext)
            syncRepo.fullSyncForWorker(workerId, shopId)
            Log.d(TAG, "Background sync completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed: ${e.message}", e)
            Result.retry()
        }
    }
}
