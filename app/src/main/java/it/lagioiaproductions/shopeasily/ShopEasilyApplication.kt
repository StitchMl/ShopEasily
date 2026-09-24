package it.lagioiaproductions.shopeasily

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import it.lagioiaproductions.shopeasily.notifications.FlashOfferWorker
import java.util.concurrent.TimeUnit
import org.maplibre.android.MapLibre

class ShopEasilyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
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
