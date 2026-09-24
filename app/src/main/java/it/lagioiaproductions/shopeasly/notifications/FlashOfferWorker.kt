package it.lagioiaproductions.shopeasly.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import it.lagioiaproductions.shopeasly.R
import it.lagioiaproductions.shopeasly.data.model.isActiveOn
import it.lagioiaproductions.shopeasly.data.model.isEligibleFor
import it.lagioiaproductions.shopeasly.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasly.data.repository.FakeOffersRepository
import it.lagioiaproductions.shopeasly.domain.currentOfferDay
import kotlinx.coroutines.flow.first

class FlashOfferWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val preferencesRepository = UserPreferencesRepository(applicationContext)
        val preferences = preferencesRepository.preferences.first()
        if (!preferences.flashNotificationsEnabled) return Result.success()

        val offer = FakeOffersRepository().search("").first().firstOrNull {
            it.flashOffer &&
                it.isActiveOn(currentOfferDay()) &&
                it.isEligibleFor(preferences.age)
        } ?: return Result.success()
        if (preferences.lastNotifiedOfferId == offer.id) return Result.success()

        createNotificationChannel()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Offerta lampo ShopEasly")
            .setContentText("${offer.productName} a € ${"%.2f".format(offer.price)} da ${offer.storeName}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(offer.id.toInt(), notification)
        preferencesRepository.setLastNotifiedOfferId(offer.id)
        return Result.success()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Offerte lampo",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Avvisi per offerte brevi compatibili con le tue preferenze"
            }
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val WORK_NAME = "flash-offer-check"
        private const val CHANNEL_ID = "flash-offers"
    }
}
