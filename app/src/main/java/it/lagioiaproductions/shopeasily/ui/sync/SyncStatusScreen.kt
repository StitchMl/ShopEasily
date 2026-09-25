package it.lagioiaproductions.shopeasily.ui.sync

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import it.lagioiaproductions.shopeasily.data.local.SourceState
import it.lagioiaproductions.shopeasily.data.local.SourceStatusEntity
import it.lagioiaproductions.shopeasily.data.repository.OnDeviceCatalogRepository
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class SyncStatusViewModel(application: Application) : AndroidViewModel(application) {
    val statuses = OnDeviceCatalogRepository(application).observeSourceStatuses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun SyncStatusScreen(modifier: Modifier = Modifier, viewModel: SyncStatusViewModel = viewModel()) {
    val statuses by viewModel.statuses.collectAsState()
    val lastScan = statuses.maxOfOrNull(SourceStatusEntity::lastAttemptAt)
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Dati", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            lastScan?.let { "Ultimo controllo ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}" }
                ?: "In attesa della prima scansione",
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(statuses, key = SourceStatusEntity::storeId) { status -> StatusCard(status) }
        }
    }
}

@Composable
private fun StatusCard(status: SourceStatusEntity) {
    val icon = when (status.state) {
        SourceState.UPDATED -> Icons.Rounded.CheckCircle
        SourceState.PENDING -> Icons.Rounded.HourglassTop
        SourceState.UNAVAILABLE, SourceState.BLOCKED, SourceState.PARSE_ERROR -> Icons.Rounded.CloudOff
        SourceState.NO_OFFERS -> Icons.Rounded.Info
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = status.state.name)
            Column(modifier = Modifier.weight(1f)) {
                Text(status.storeName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    if (status.offerCount > 0) "${status.offerCount} offerte" else status.detail ?: "Nessuna offerta disponibile",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
