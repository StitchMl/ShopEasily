package it.lagioiaproductions.shopeasily

import android.app.Application
import androidx.work.WorkManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import it.lagioiaproductions.shopeasily.data.repository.BrandDirectory
import it.lagioiaproductions.shopeasily.notifications.FlashOfferWorker
import it.lagioiaproductions.shopeasily.sync.CatalogSyncWorker
import org.maplibre.android.MapLibre

class ShopEasilyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        PDFBoxResourceLoader.init(this)
        BrandDirectory.get(this)
        val workManager = WorkManager.getInstance(this)
        // The 15-minute demo-offer check is replaced by notifications after each real sync.
        workManager.cancelUniqueWork(FlashOfferWorker.WORK_NAME)
        CatalogSyncWorker.schedulePeriodic(this)
    }
}
