package com.lecturenotes.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lecturenotes.app
import com.lecturenotes.transcribe.Jobs
import com.lecturenotes.transcribe.ModelDownloadWorker
import com.lecturenotes.whisper.DeviceInfo
import com.lecturenotes.whisper.WhisperModel
import com.lecturenotes.whisper.WhisperModels
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val progress: Float, val verifying: Boolean) : ModelState
    data class Failed(val message: String) : ModelState
    data object Downloaded : ModelState
}

data class ModelRow(val model: WhisperModel, val state: ModelState, val selected: Boolean, val recommended: Boolean)

class ModelsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app
    private val repo = app.models
    val device = DeviceInfo.read(application)
    val recommended = WhisperModels.recommend(device)

    private val workStates = combine(
        WhisperModels.all.map { model ->
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWorkFlow(Jobs.downloadName(model))
                .map { infos -> model.id to infos.lastOrNull() }
        },
    ) { it.toMap() }

    val rows = combine(workStates, repo.selectedId, repo.changes) { work, _, _ ->
        val active = repo.activeModel()
        WhisperModels.all.map { model ->
            ModelRow(model, stateOf(model, work[model.id]), active == model, model == recommended)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun stateOf(model: WhisperModel, info: WorkInfo?): ModelState {
        if (repo.isDownloaded(model)) return ModelState.Downloaded
        return when (info?.state) {
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                val partial = repo.partFileFor(model).length().toFloat() / model.sizeBytes
                ModelState.Downloading(
                    info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, partial),
                    info.progress.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false),
                )
            }
            WorkInfo.State.FAILED -> ModelState.Failed(
                info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed",
            )
            else -> ModelState.NotDownloaded
        }
    }

    fun download(model: WhisperModel) = Jobs.downloadModel(app, model)
    fun cancel(model: WhisperModel) = Jobs.cancelDownload(app, model)
    fun select(model: WhisperModel) = repo.select(model)
    fun delete(model: WhisperModel) = repo.delete(model)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(onBack: () -> Unit, vm: ModelsViewModel = viewModel()) {
    val rows by vm.rows.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speech models") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { DeviceCard(vm.device, vm.recommended) }
            items(rows, key = { it.model.id }) { row ->
                ModelCard(row, vm::download, vm::cancel, vm::select, vm::delete)
            }
            item {
                Text(
                    "All models are English-only and run fully on your phone. Larger models are more accurate but slower. " +
                        "A benchmark-based recommendation is coming in the device check.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceInfo, recommended: WhisperModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Your phone", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "%s · %.1f GB RAM · %d cores (%d fast) · %s free".format(
                    device.soc, device.totalRamGb, device.cores, device.bigCores, formatBytes(device.freeStorageBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Recommended: ${recommended.displayName}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ModelCard(
    row: ModelRow,
    onDownload: (WhisperModel) -> Unit,
    onCancel: (WhisperModel) -> Unit,
    onSelect: (WhisperModel) -> Unit,
    onDelete: (WhisperModel) -> Unit,
) {
    val m = row.model
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.state == ModelState.Downloaded) {
                    RadioButton(selected = row.selected, onClick = { onSelect(m) })
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.displayName, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(formatBytes(m.sizeBytes), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (row.recommended) {
                            Spacer(Modifier.width(8.dp))
                            AssistChip(onClick = {}, label = { Text("Recommended") })
                        }
                    }
                    Text(m.description, style = MaterialTheme.typography.bodySmall)
                }
                when (val s = row.state) {
                    ModelState.Downloaded -> IconButton(onClick = { onDelete(m) }) {
                        Icon(Icons.Outlined.Delete, "Delete ${m.displayName} model")
                    }
                    is ModelState.Downloading -> TextButton(onClick = { onCancel(m) }) { Text("Cancel") }
                    else -> FilledTonalButton(onClick = { onDownload(m) }) {
                        Text(if (s is ModelState.Failed) "Retry" else "Download")
                    }
                }
            }
            when (val s = row.state) {
                is ModelState.Downloading -> {
                    Spacer(Modifier.height(8.dp))
                    if (s.verifying) {
                        Text("Verifying…", style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
                    } else {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                    }
                }
                is ModelState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                else -> {}
            }
        }
    }
}
