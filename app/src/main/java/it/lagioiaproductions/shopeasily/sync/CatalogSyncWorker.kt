package it.lagioiaproductions.shopeasily.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.repository.OnDeviceCatalogRepository
import it.lagioiaproductions.shopeasily.notifications.FlashOfferNotifier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Downloads stores and prices in the background, outside the UI.
 *
 * The collector used to run inside the Home ViewModel and the Map screen at
 * the same time: two full crawls in parallel, on the UI's lifecycle, with an
 * uncaught exception path in the Map. Now a single unique WorkManager job does
 * it, the screens simply observe the Room database and refresh silently.
 */
class CatalogSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val preferences = UserPreferencesRepository(applicationContext)
        val stored = preferences.lastLocation.first()
        val latitude = inputData.getDouble(KEY_LAT, Double.NaN).takeUnless(Double::isNaN) ?: stored?.first
        val longitude = inputData.getDouble(KEY_LON, Double.NaN).takeUnless(Double::isNaN) ?: stored?.second
        if (latitude == null || longitude == null) return Result.success()
        val radius = preferences.preferences.first().radiusKm
        val force = inputData.getBoolean(KEY_FORCE, false)
        return try {
            val repository = OnDeviceCatalogRepository(applicationContext)
            repository.synchronize(latitude, longitude, radius, force) { done, total ->
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            }
            // Ordinary shelf prices for what the user still has to buy, also when not on offer.
            val pending = preferences.shoppingItems.first().filterNot { it.second }.map { it.first }.take(MAX_LIST_LOOKUPS)
            for (item in pending) {
                if (isStopped) break
                runCatching { repository.enrichRegularPrices(item) }
            }
            // Big areas need several rounds: continue in a new job until every store is read.
            if (repository.remainingAfterRun > 0) continueLater(applicationContext, latitude, longitude)
            runCatching { FlashOfferNotifier.notifyIfRelevant(applicationContext) }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val ONE_TIME = "catalog-sync-now"
        private const val PERIODIC = "catalog-sync-periodic"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_FORCE = "force"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        /**
         * Requests a refresh for a position. If one is already running it is
         * kept (KEEP), so returning to Home or moving a little never restarts it.
         */
        fun requestNow(context: Context, latitude: Double, longitude: Double, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setInputData(workDataOf(KEY_LAT to latitude, KEY_LON to longitude, KEY_FORCE to force))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME,
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private const val CONTINUATION = "catalog-sync-continue"
        private const val MAX_LIST_LOOKUPS = 10

        private fun continueLater(context: Context, latitude: Double, longitude: Double) {
            val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_LAT to latitude, KEY_LON to longitude, KEY_FORCE to false))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CONTINUATION, ExistingWorkPolicy.REPLACE, request)
        }

        /** Keeps prices fresh every 6 hours while charging-friendly conditions hold. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Progress of the running refresh (null when idle), for a small non-blocking indicator. */
        fun observeProgress(context: Context): Flow<SyncProgress?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ONE_TIME).map { infos ->
                infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let { info ->
                    SyncProgress(info.progress.getInt(KEY_DONE, 0), info.progress.getInt(KEY_TOTAL, 0))
                }
            }
    }
}

data class SyncProgress(val completed: Int, val total: Int)
