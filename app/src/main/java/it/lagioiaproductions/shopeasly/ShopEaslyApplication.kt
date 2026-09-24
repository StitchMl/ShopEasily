package it.lagioiaproductions.shopeasly

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import it.lagioiaproductions.shopeasly.notifications.FlashOfferWorker
import java.util.concurrent.TimeUnit

class ShopEaslyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        scheduleFlashOfferChecks()
    }

    private fun scheduleFlashOfferChecks() {
        val request = PeriodicWorkRequestBuilder<FlashOfferWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            FlashOfferWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
