package it.lagioiaproductions.shopeasily.notifications

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
import it.lagioiaproductions.shopeasily.R
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.repository.OnDeviceCatalogRepository
import it.lagioiaproductions.shopeasily.domain.ProductMatcher
import java.util.Locale
import kotlinx.coroutines.flow.first

/**
 * Notifies real promotions (from the on-device catalogue, not demo data) for
 * items still to buy in the shopping list. Called after each background sync.
 */
object FlashOfferNotifier {
    private const val CHANNEL_ID = "flash-offers"

    suspend fun notifyIfRelevant(context: Context) {
        val preferencesRepository = UserPreferencesRepository(context)
        val preferences = preferencesRepository.preferences.first()
        if (!preferences.flashNotificationsEnabled) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pending = preferencesRepository.shoppingItems.first().filterNot { it.second }.map { it.first }
        if (pending.isEmpty()) return
        val radius = preferences.radiusKm * 1_000
        val offer = OnDeviceCatalogRepository(context).search("").first()
            .filter { it.promotional && it.distanceMeters <= radius }
            .filter { offer -> pending.any { ProductMatcher.matches(offer.productName, it) } }
            .minByOrNull { it.price } ?: return
        if (preferences.lastNotifiedOfferId == offer.id) return

        createNotificationChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("In offerta dalla tua lista")
            .setContentText("${offer.productName} a € ${"%.2f".format(Locale.ITALY, offer.price)} da ${offer.storeName}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(offer.id.toInt(), notification) }
        preferencesRepository.setLastNotifiedOfferId(offer.id)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Offerte lampo", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Offerte reali per gli articoli della tua lista della spesa"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

/** Kept so that work enqueued by older versions still resolves; it delegates to the notifier. */
class FlashOfferWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        runCatching { FlashOfferNotifier.notifyIfRelevant(applicationContext) }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "flash-offer-check"
    }
}
