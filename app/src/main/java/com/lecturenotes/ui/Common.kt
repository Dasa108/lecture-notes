package com.lecturenotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lecturenotes.data.Recording
import com.lecturenotes.data.RecordingStatus

fun statusLabel(rec: Recording): String = when (rec.status) {
    RecordingStatus.RECORDING -> "Recording…"
    RecordingStatus.IMPORTING -> "Importing ${(rec.progress * 100).toInt()}%"
    RecordingStatus.READY -> if (rec.error != null) "Waiting for a speech model" else "Not transcribed"
    RecordingStatus.QUEUED -> "Waiting to transcribe"
    RecordingStatus.TRANSCRIBING -> "Transcribing ${(rec.progress * 100).toInt()}%"
    RecordingStatus.TRANSCRIBED -> "Transcribed"
    RecordingStatus.FAILED -> "Failed"
}

val Recording.isBusy: Boolean
    get() = status == RecordingStatus.IMPORTING || status == RecordingStatus.TRANSCRIBING || status == RecordingStatus.QUEUED

@Composable
fun StatusWithProgress(rec: Recording, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            statusLabel(rec),
            style = MaterialTheme.typography.labelMedium,
            color = if (rec.status == RecordingStatus.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (rec.status == RecordingStatus.IMPORTING || rec.status == RecordingStatus.TRANSCRIBING) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { rec.progress }, modifier = Modifier.fillMaxWidth())
        } else if (rec.status == RecordingStatus.QUEUED) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
    else -> "%.0f KB".format(bytes / 1e3)
}
