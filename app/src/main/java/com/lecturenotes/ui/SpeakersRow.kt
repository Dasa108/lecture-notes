package com.lecturenotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lecturenotes.audio.formatDuration
import com.lecturenotes.data.Recording
import com.lecturenotes.data.RecordingStatus
import com.lecturenotes.data.Segment
import com.lecturenotes.data.Speaker
import com.lecturenotes.data.SpeakerStatus

/** Speaker colours reuse the subject palette, by speaker number. */
fun speakerColor(number: Int) = subjectColor(number - 1)

/** Up to [n] clear stretches of a speaker's voice (their longest lines, at most 8 s each). */
fun samplesFor(speaker: Speaker, segments: List<Segment>, n: Int = 5): List<Pair<Long, Long>> =
    segments.filter { it.speakerId == speaker.id && it.endMs - it.startMs >= 2_000 }
        .sortedByDescending { it.endMs - it.startMs }
        .take(n)
        .map { it.startMs to minOf(it.endMs, it.startMs + 8_000) }

/**
 * Who's speaking: run labelling, show progress, or list speakers. Tapping a speaker lets the user
 * hear their voice, rename them, mark the lecturer, or merge them into another speaker.
 */
@Composable
fun SpeakersRow(
    rec: Recording,
    speakers: List<Speaker>,
    segments: List<Segment>,
    onLabel: () -> Unit,
    onCancel: () -> Unit,
    /** Opens the speaker panel. It lives outside the scrolling list so playback can't close it. */
    onEdit: (Speaker) -> Unit,
) {
    if (rec.status != RecordingStatus.TRANSCRIBED) return
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        when (rec.speakerStatus) {
            SpeakerStatus.QUEUED.name, SpeakerStatus.RUNNING.name -> {
                Text(
                    if (rec.speakerStatus == SpeakerStatus.QUEUED.name) "Speaker labelling is waiting…"
                    else "Labelling speakers… ${((rec.speakerProgress ?: 0f) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(progress = { rec.speakerProgress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text(
                    "Keep this page open: it's much faster in the foreground.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            SpeakerStatus.DONE.name -> {
                val total = speakers.sumOf { it.talkMs }.coerceAtLeast(1)
                if (speakers.size <= 1) {
                    Text("One speaker found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Speakers · tap to listen, rename or merge", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        speakers.forEach { s ->
                            AssistChip(
                                onClick = { onEdit(s) },
                                leadingIcon = {
                                    if (s.isLecturer) Icon(Icons.Filled.Star, "Lecturer", Modifier.size(16.dp), tint = speakerColor(s.number))
                                    else SubjectDot(s.number - 1)
                                },
                                label = { Text("${s.name} · ${s.talkMs * 100 / total}%") },
                            )
                        }
                    }
                    // Honest about what automatic labelling can get wrong.
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Labels are automatic and can be wrong: one person may be split into several speakers " +
                                "(e.g. when they move or face the board), or two people mixed up. Tap a speaker, listen, " +
                                "and use “Same person as…” to merge.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onLabel) { Text("Label again") }
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
                    "Finds who's talking (lecturer vs students) by voice, on your phone. Most useful for tutorials and " +
                        "discussions. Takes a few minutes per hour of audio; first use downloads ~31 MB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

}

@Composable
fun SpeakerDialog(
    speaker: Speaker,
    others: List<Speaker>,
    samples: List<Pair<Long, Long>>,
    onPlay: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
    onSave: (name: String, lecturer: Boolean) -> Unit,
    onMerge: (Speaker) -> Unit,
) {
    var name by remember(speaker.id) { mutableStateOf(speaker.name) }
    var lecturer by remember(speaker.id) { mutableStateOf(speaker.isLecturer) }
    var sample by remember(speaker.id) { mutableIntStateOf(0) }
    var confirmMerge by remember(speaker.id) { mutableStateOf<Speaker?>(null) }

    confirmMerge?.let { into ->
        AlertDialog(
            onDismissRequest = { confirmMerge = null },
            title = { Text("Merge into ${into.name}?") },
            text = { Text("All of ${speaker.name}'s lines will be labelled ${into.name}. Use this when they're the same person.") },
            confirmButton = { TextButton(onClick = { onMerge(into) }) { Text("Merge") } },
            dismissButton = { TextButton(onClick = { confirmMerge = null }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speaker ${speaker.number}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Listen first: the only reliable way to tell who this is.
                if (samples.isEmpty()) {
                    Text("No clear sample of this voice (only very short lines).", style = MaterialTheme.typography.bodySmall)
                } else {
                    val (a, b) = samples[sample % samples.size]
                    FilledTonalButton(onClick = {
                        onPlay(a, b)
                        sample = (sample + 1) % samples.size
                    }) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text(" Play voice sample ${sample % samples.size + 1} of ${samples.size}")
                    }
                    Text(
                        "at ${formatDuration(a)} · tap again for the next sample",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = lecturer, onCheckedChange = { lecturer = it })
                    Text("This is the lecturer")
                }
                if (others.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Same person as…", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "If this voice is someone already listed, merge them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    others.forEach { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { confirmMerge = o }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (o.isLecturer) Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = speakerColor(o.number))
                            else SubjectDot(o.number - 1)
                            Spacer(Modifier.width(10.dp))
                            Text(o.name, modifier = Modifier.weight(1f))
                            Text("Merge", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), lecturer) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
