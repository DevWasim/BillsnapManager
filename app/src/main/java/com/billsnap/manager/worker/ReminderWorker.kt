package com.billsnap.manager.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.billsnap.manager.R
import com.billsnap.manager.data.AppDatabase
import com.billsnap.manager.util.LocaleManager

/**
 * One-time worker triggered at the scheduled reminder time for a specific bill.
 * Sends a notification and marks the bill as Overdue if still unpaid.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_BILL_NAME = "bill_name"
        const val KEY_BILL_ID = "bill_id"
        const val CHANNEL_ID = "bill_reminders"
    }

    override suspend fun doWork(): Result {
        val billName = inputData.getString(KEY_BILL_NAME) ?: return Result.failure()
        val billId = inputData.getLong(KEY_BILL_ID, -1)

        // Create notification channel (idempotent)
        val localizedContext = LocaleManager.getLocalizedContext(applicationContext)
        createNotificationChannel(localizedContext)

        // Send notification (check permission on Android 13+)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(localizedContext.getString(R.string.reminder_notification_title))
                .setContentText(
                    localizedContext.getString(R.string.reminder_notification_text, billName)
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(applicationContext)
                .notify(billId.toInt(), notification)
        }

        // Mark bill as overdue if still unpaid
        if (billId > 0) {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val bill = db.billDao().getBillById(billId)
                if (bill != null && bill.paymentStatus != "Paid") {
                    db.billDao().updatePaymentStatus(billId, "Overdue")
                }
            } catch (e: Exception) {
                // Don't fail the worker just because DB update failed
                android.util.Log.w("ReminderWorker", "Failed to mark bill overdue", e)
            }
        }

        return Result.success()
    }

    private fun createNotificationChannel(localizedContext: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                localizedContext.getString(R.string.channel_bill_reminders),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = localizedContext.getString(R.string.channel_bill_reminders_desc)
            }

            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
