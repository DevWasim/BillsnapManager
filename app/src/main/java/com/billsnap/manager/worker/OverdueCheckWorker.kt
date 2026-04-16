package com.billsnap.manager.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.billsnap.manager.data.AppDatabase

/**
 * Periodic worker that runs every hour to check for overdue bills.
 * Marks any unpaid bill whose reminder datetime has passed as "Overdue".
 */
class OverdueCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "OverdueCheckWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val now = System.currentTimeMillis()
            val updated = db.billDao().markOverdueBills(now)
            Log.d(TAG, "Overdue check complete: $updated bills marked overdue")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Overdue check failed", e)
            Result.retry()
        }
    }
}
