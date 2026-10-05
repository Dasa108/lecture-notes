package com.lecturenotes.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lecturenotes.data.Recording
import com.lecturenotes.data.RecordingStatus
import com.lecturenotes.data.Speaker
import com.lecturenotes.data.SpeakerStatus

/** Speaker colours reuse the subject palette, by speaker number. */
fun speakerColor(number: Int) = subjectColor(number - 1)

/** Who's speaking: run labelling, show progress, or list speakers (tap to rename / mark lecturer). */
@Composable
fun SpeakersRow(
    rec: Recording,
    speakers: List<Speaker>,
    onLabel: () -> Unit,
    onCancel: () -> Unit,
    onRename: (Speaker, String) -> Unit,
    onSetLecturer: (Speaker) -> Unit,
) {
    if (rec.status != RecordingStatus.TRANSCRIBED) return
    var editing by remember { mutableStateOf<Speaker?>(null) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        when (rec.speakerStatus) {
            SpeakerStatus.QUEUED.name, SpeakerStatus.RUNNING.name -> {
                Text(
                    if (rec.speakerStatus == SpeakerStatus.QUEUED.name) "Speaker labelling is waiting…"
                    else "Labelling speakers… ${((rec.speakerProgress ?: 0f) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(progress = { rec.speakerProgress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            SpeakerStatus.DONE.name -> {
                val total = speakers.sumOf { it.talkMs }.coerceAtLeast(1)
                if (speakers.size <= 1) {
                    Text("One speaker found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Speakers · tap to rename", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        speakers.forEach { s ->
                            AssistChip(
                                onClick = { editing = s },
                                leadingIcon = {
                                    if (s.isLecturer) Icon(Icons.Filled.Star, "Lecturer", Modifier.size(16.dp), tint = speakerColor(s.number))
                                    else SubjectDot(s.number - 1)
                                },
                                label = { Text("${s.name} · ${s.talkMs * 100 / total}%") },
                            )
                        }
                    }
                }
            }
            else -> {
                if (rec.speakerStatus == SpeakerStatus.FAILED.name) {
                    Text("Couldn't label speakers.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onLabel) {
                    Icon(Icons.Outlined.RecordVoiceOver, null, Modifier.size(18.dp))
                    Text("  Label speakers")
                }
                Text(
                    "Finds who's talking (lecturer vs students) on your phone. First use downloads ~31 MB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    editing?.let { s ->
        var name by remember(s.id) { mutableStateOf(s.name) }
        var lecturer by remember(s.id) { mutableStateOf(s.isLecturer) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Speaker ${s.number}") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = lecturer, onCheckedChange = { lecturer = it })
                        Text("This is the lecturer")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    if (name.trim() != s.name) onRename(s, name.trim())
                    if (lecturer && !s.isLecturer) onSetLecturer(s)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
