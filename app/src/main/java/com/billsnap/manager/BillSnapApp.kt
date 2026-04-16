package com.billsnap.manager

import android.content.Context
import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.billsnap.manager.data.AppDatabase
import com.billsnap.manager.data.BillRepository
import com.billsnap.manager.data.CustomerRepository
import com.billsnap.manager.util.LocaleManager
import com.billsnap.manager.util.ThemeManager
import com.billsnap.manager.worker.OverdueCheckWorker
import com.billsnap.manager.util.CurrencyManager
import java.util.concurrent.TimeUnit

/**
 * Application class providing singletons for database and repositories.
 * Schedules periodic background workers on startup.
 */
class BillSnapApp : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val billRepository by lazy { BillRepository(database.billDao()) }
    val customerRepository by lazy { CustomerRepository(database.customerDao()) }
    val workerAccessDao by lazy { database.workerAccessDao() }

    override fun attachBaseContext(base: Context) {
        ThemeManager.applyTheme(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        CurrencyManager.init(this)
        scheduleOverdueCheckWorker()
    }

    /**
     * Schedule a periodic worker to check for overdue bills every hour.
     * Uses KEEP policy so it doesn't restart if already running.
     */
    private fun scheduleOverdueCheckWorker() {
        val request = PeriodicWorkRequestBuilder<OverdueCheckWorker>(1, TimeUnit.HOURS)
            .addTag("overdue_check")
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "overdue_check",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
}
