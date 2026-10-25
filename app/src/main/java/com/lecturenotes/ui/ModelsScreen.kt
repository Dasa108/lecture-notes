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
import com.lecturenotes.whisper.BenchResult
import com.lecturenotes.whisper.DeviceCheck
import com.lecturenotes.whisper.DeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

data class ModelRow(
    val model: WhisperModel,
    val state: ModelState,
    val selected: Boolean,
    val recommended: Boolean,
    /** Estimated minutes per hour of lecture with the app open, once the speed check has run. */
    val minutesPerHour: Double?,
    val fits: Boolean,
)

sealed interface CheckState {
    data object Idle : CheckState
    data class Running(val message: String) : CheckState
    data class Failed(val message: String) : CheckState
}

class ModelsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app
    private val repo = app.models
    val device = DeviceInfo.read(application)
    val bench = repo.bench
    /** Why this phone can't run any model, or null. */
    val unsupported = WhisperModels.unsupportedReason(device)
    private val _check = MutableStateFlow<CheckState>(CheckState.Idle)
    val check = _check.asStateFlow()

    init {
        // A memory-only guess can be far off on a slow phone, so measure on the first visit.
        if (unsupported == null && repo.bench.value == null && !repo.autoCheckDone) runCheck()
    }

    /** Runs the speed test; the recommendation and per-model estimates update when it finishes. */
    fun runCheck() = viewModelScope.launch {
        if (_check.value is CheckState.Running) return@launch
        _check.value = CheckState.Running("Starting…")
        _check.value = try {
            DeviceCheck.run(app) { _check.value = CheckState.Running(it) }
            CheckState.Idle
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // left the screen: try again on the next visit
        } catch (e: Exception) {
            CheckState.Failed(e.message ?: "The speed test failed.")
        }
        repo.autoCheckDone = true
    }

    private val workStates = combine(
        WhisperModels.all.map { model ->
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWorkFlow(Jobs.downloadName(model))
                .map { infos -> model.id to infos.lastOrNull() }
        },
    ) { it.toMap() }

    val rows = combine(workStates, repo.selectedId, repo.changes, repo.bench) { work, _, _, b ->
        val active = repo.activeModel()
        val recommended = WhisperModels.recommend(device, b)
        val fitting = WhisperModels.fitting(device)
        WhisperModels.all.map { model ->
            ModelRow(model, stateOf(model, work[model.id]), active == model, model == recommended, b?.minutesPerHour(model), model in fitting)
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
            item {
                val bench by vm.bench.collectAsState()
                val check by vm.check.collectAsState()
                DeviceCard(vm.device, vm.unsupported, rows.firstOrNull { it.recommended }?.model, bench, check, vm::runCheck)
            }
            items(rows, key = { it.model.id }) { row ->
                ModelCard(row, vm::download, vm::cancel, vm::select, vm::delete)
            }
            item {
                Text(
                    "All models are English-only and run fully on your phone. Larger models are more accurate but slower. " +
                        "Times are estimates with the app open; in the background some phones (e.g. vivo, Xiaomi) " +
                        "limit apps to their slower cores, which can make transcription 2–3× slower.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceInfo,
    unsupported: String?,
    recommended: WhisperModel?,
    bench: BenchResult?,
    check: CheckState,
    onCheck: () -> Unit,
) {
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
            if (unsupported != null) {
                Text("This phone can't transcribe on-device", style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text(
                    "$unsupported You can still make study notes from slides and PDFs.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            if (recommended != null) {
                Text("Recommended: ${recommended.displayName}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            val slowest = recommended?.let { bench?.minutesPerHour(it) }
            if (slowest != null && slowest > WhisperModels.TARGET_MINUTES_PER_HOUR) {
                Text(
                    "This phone is slow for transcription: even ${recommended.displayName} takes about " +
                        "${formatMinutes(slowest)} per lecture hour with the app open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                if (bench == null) "Based on memory only. Run the speed test for a recommendation that fits this phone."
                else "Based on a speed test (${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(bench.measuredAt))}): " +
                    "the most accurate model that does a 1-hour lecture in about ${WhisperModels.TARGET_MINUTES_PER_HOUR.toInt()} min or less with the app open.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            when (check) {
                is CheckState.Running -> {
                    Text(check.message, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                else -> {
                    if (check is CheckState.Failed) Text(check.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = onCheck) { Text(if (bench == null) "Test this phone's speed" else "Test again") }
                }
            }
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
                    val notes = listOfNotNull(
                        row.minutesPerHour?.let { "1-hour lecture ≈ ${formatMinutes(it)} with the app open" },
                        if (!row.fits) "too big for this phone's memory or free storage" else null,
                    )
                    if (notes.isNotEmpty()) {
                        Text(
                            notes.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (row.fits) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
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

private fun formatMinutes(min: Double): String = when {
    min < 1 -> "under 1 min"
    min < 90 -> "${min.toInt().coerceAtLeast(1)} min"
    else -> "%.1f h".format(min / 60)
}
