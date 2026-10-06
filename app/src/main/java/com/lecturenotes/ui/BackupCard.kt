package com.lecturenotes.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lecturenotes.LectureNotesApp
import com.lecturenotes.app
import com.lecturenotes.data.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Backup/restore runs in the app's scope, so leaving Settings doesn't cut a backup short. */
private object BackupJob {
    sealed interface State {
        data object Idle : State
        data class Working(val label: String, val progress: Float?) : State
        data class Confirm(val staged: Backup.Staged) : State
        data class Message(val text: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    fun backUp(app: LectureNotesApp, uri: Uri) = app.appScope.launch(Dispatchers.IO) {
        Backup.busyReason(app)?.let { state.value = State.Message("Can't back up yet: $it Try again when it's finished."); return@launch }
        state.value = State.Working("Backing up…", 0f)
        state.value = try {
            app.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                Backup.write(app, app.db, out) { state.value = State.Working("Backing up…", it) }
            }
            State.Message("Backup saved. Keep it somewhere off this phone (e.g. Google Drive) so it survives losing the phone.")
        } catch (e: Exception) {
            runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
            State.Message("Backup failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun check(app: LectureNotesApp, uri: Uri) = app.appScope.launch(Dispatchers.IO) {
        Backup.busyReason(app)?.let { state.value = State.Message("Can't restore yet: $it Try again when it's finished."); return@launch }
        state.value = State.Working("Checking backup…", null)
        state.value = try {
            State.Confirm(app.contentResolver.openInputStream(uri)!!.use { Backup.stage(app, app.db, it) })
        } catch (e: Exception) {
            State.Message(e.message ?: "Couldn't read this backup.")
        }
    }

    fun restore(app: LectureNotesApp, staged: Backup.Staged) = app.appScope.launch(Dispatchers.IO) {
        state.value = State.Working("Restoring…", null)
        try {
            androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
            Backup.replace(app, app.db, staged)
            app.getSharedPreferences("restore", 0).edit()
                .putString("message", "Restored ${staged.lectures} lecture${if (staged.lectures == 1) "" else "s"}.").commit()
        } catch (e: Exception) {
            // The database was closed for the swap; restarting reopens whichever data is in place.
            app.getSharedPreferences("restore", 0).edit().putString("message", e.message).commit()
        }
        withContext(Dispatchers.Main) {
            app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                app.startActivity(Intent.makeRestartActivityTask(it.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Runtime.getRuntime().exit(0)
        }
    }

    fun cancel(app: LectureNotesApp) {
        (state.value as? State.Confirm)?.let { Backup.discard(it.staged) }
        state.value = State.Idle
    }
}

@Composable
fun BackupCard() {
    val context = LocalContext.current
    val app = context.app
    val state by BackupJob.state.collectAsState()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { BackupJob.backUp(app, it) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { BackupJob.check(app, it) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Backup, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "One file with all lectures, transcripts, notes, slides, audio, study progress and settings " +
                            "(~${formatBytes(Backup.estimateBytes(context))}). Speech models re-download; API keys must be entered again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val s = state
            if (s is BackupJob.State.Working) {
                Spacer(Modifier.height(8.dp))
                Text(s.label + (s.progress?.let { " ${(it * 100).toInt()}%" } ?: ""), style = MaterialTheme.typography.labelMedium)
                if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        save.launch("LectureNotes-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.zip")
                    }) { Text("Back up") }
                    OutlinedButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore") }
                }
            }
        }
    }

    when (val s = state) {
        is BackupJob.State.Confirm -> AlertDialog(
            onDismissRequest = { BackupJob.cancel(app) },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "It has ${s.staged.lectures} lecture${if (s.staged.lectures == 1) "" else "s"}, made " +
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.staged.createdAt)) +
                        ".\n\nEverything currently in the app is replaced by the backup: lectures not in it are removed. " +
                        "The app restarts when it's done.",
                )
            },
            confirmButton = { TextButton(onClick = { BackupJob.restore(app, s.staged) }) { Text("Replace and restore") } },
            dismissButton = { TextButton(onClick = { BackupJob.cancel(app) }) { Text("Cancel") } },
        )
        is BackupJob.State.Message -> AlertDialog(
            onDismissRequest = { BackupJob.state.value = BackupJob.State.Idle },
            text = { Text(s.text) },
            confirmButton = { TextButton(onClick = { BackupJob.state.value = BackupJob.State.Idle }) { Text("OK") } },
        )
        else -> {}
    }
}
