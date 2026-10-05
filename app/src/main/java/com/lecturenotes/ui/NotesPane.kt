package com.lecturenotes.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lecturenotes.data.Notes
import com.lecturenotes.data.NotesStatus
import com.lecturenotes.notes.NoteStyle
import com.lecturenotes.notes.NotesGenerator
import com.lecturenotes.notes.ProviderConfig
import com.lecturenotes.notes.ProviderPreset
import com.lecturenotes.notes.QuizQuestion
import com.lecturenotes.notes.StudyNotes
import com.lecturenotes.notes.formatTimestamp

/** State and actions for the Notes tab. */
class NotesPaneModel(
    val notes: Notes?,
    val study: StudyNotes?,
    val ai: ProviderConfig,
    /** Whether there's anything to make notes from: a finished transcript or slide text. */
    val hasTranscript: Boolean,
    val hasSlides: Boolean,
    val onOpenSlide: (Int) -> Unit,
    val transcriptChars: Int,
    val onGenerate: (NoteStyle) -> Unit,
    val onCancel: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onSetUpAi: () -> Unit,
    val onShare: () -> Unit,
    /** The lecture's subject, whose notes instructions are used; null if unfiled. */
    val subject: com.lecturenotes.data.Subject?,
    val onEditSubject: (Long) -> Unit,
    val cardCounts: com.lecturenotes.data.StudyCounts?,
    val attempts: List<com.lecturenotes.data.QuizAttempt>,
    val onStudyCards: () -> Unit,
    val onQuiz: () -> Unit,
)

fun LazyListScope.notesPane(m: NotesPaneModel) {
    val notes = m.notes
    when {
        !m.hasTranscript -> item {
            Hint("Notes can be made once this lecture has a transcript, or from its slides: add them in the Slides tab.")
        }
        !m.ai.isReady -> item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Set up Notes AI", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Notes are written by an AI service of your choice. Free options like Groq take a minute to set up.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = m.onSetUpAi) { Text("Set up") }
                }
            }
        }
        notes == null -> item { GenerateCard(m, retry = false) }
        notes.status == NotesStatus.QUEUED || notes.status == NotesStatus.GENERATING -> item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(notes.message ?: "Waiting for the network…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                if (notes.progress in 0f..1f && notes.progress > 0f) {
                    LinearProgressIndicator(progress = { notes.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Using ${notes.model} on ${ProviderPreset.byId(notes.provider)?.label ?: notes.provider}. You can leave this screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = m.onCancel) { Text("Cancel") }
            }
        }
        notes.status == NotesStatus.FAILED -> {
            // Show the error and a retry; any earlier notes stay visible below.
            item {
            Column {
                Text(notes.message ?: "Couldn't write notes.", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                if (notes.partialJson != null) {
                    Text(
                        "Finished parts are saved; trying again continues from there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GenerateCard(m, retry = true)
            }
            }
            if (m.study != null) studyNotes(m.study, m)
        }
        m.study != null -> studyNotes(m.study, m)
    }
}

@Composable
private fun GenerateCard(m: NotesPaneModel, retry: Boolean) {
    var style by rememberSaveable {
        val subjectDefault = m.subject?.defaultStyle
        mutableStateOf(
            listOf(m.notes?.style, subjectDefault).firstNotNullOfOrNull { s -> s?.let { runCatching { NoteStyle.valueOf(it) }.getOrNull() } }
                ?: NoteStyle.STANDARD,
        )
    }
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (!retry) {
                Text("Make study notes", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Summary, key terms, flashcards and a practice quiz, each linked to the moment in the lecture.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteStyle.entries.forEach { s ->
                    FilterChip(selected = s == style, onClick = { style = s }, label = { Text(s.label) })
                }
            }
            Text(style.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            val tokens = NotesGenerator.estimateTokens("x".repeat(m.transcriptChars))
            val words = m.transcriptChars / 6
            Text(
                "Sends about ${"%,d".format(words)} words (~${"%,d".format(tokens)} tokens) of lecture text to " +
                    "${m.ai.preset.label} (${m.ai.model}). " +
                    if (m.ai.preset.isFree) "Free within its limits." else "Billed to your ${m.ai.preset.label} account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val subject = m.subject
            if (subject != null) {
                val custom = subject.customPrompt != null || subject.promptInstructions != null ||
                    (subject.promptType != null && subject.promptType != "GENERAL")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (custom) "Uses the “${subject.name}” notes instructions." else "“${subject.name}” has no notes instructions yet.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { m.onEditSubject(subject.id) }) { Text(if (custom) "Edit" else "Add") }
                }
            } else {
                Text(
                    "Uses the default prompt. File this lecture under a subject to give it its own instructions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { m.onGenerate(style) }) {
                    Icon(Icons.Outlined.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (retry) "Try again" else "Generate notes")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = m.onSetUpAi) { Text("Change AI") }
            }
        }
    }
}

private fun LazyListScope.studyNotes(n: StudyNotes, m: NotesPaneModel) {
    item {
        Column(Modifier.padding(top = 8.dp)) {
            Text(n.title, style = MaterialTheme.typography.headlineSmall)
            if (n.overview.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(n.overview, style = MaterialTheme.typography.bodyLarge)
            }
            StudyActions(m, n)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = m.onShare, label = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null) })
            }
        }
    }
    items(n.sections.size) { i ->
        val s = n.sections[i]
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                RefChip(s.atMs, s.page, m)
            }
            s.bullets.forEach { b ->
                Row(Modifier.padding(top = 4.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodyLarge)
                    Text(b, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (s.explain.isNotEmpty()) {
                // The AI's own additions, kept visibly apart from what the lecturer said.
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Going deeper · AI explanation, not from the lecture",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        s.explain.forEach { x ->
                            Text(x, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
    if (n.keyTerms.isNotEmpty()) {
        item { SectionTitle("Key terms") }
        items(n.keyTerms.size) { i ->
            val t = n.keyTerms[i]
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(t.term, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(t.definition, style = MaterialTheme.typography.bodyMedium)
                }
                RefChip(t.atMs, t.page, m)
            }
        }
    }
    if (n.flashcards.isNotEmpty()) {
        item { SectionTitle("Flashcards · tap to flip") }
        items(n.flashcards.size) { i -> FlipCard(n.flashcards[i].front, n.flashcards[i].back) }
    }
    if (n.quiz.isNotEmpty()) {
        item { SectionTitle("Practice quiz") }
        itemsIndexed(n.quiz) { i, q -> QuizCard(i + 1, q, m) }
    }
    item {
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        RegenerateRow(m)
    }
}

/** Entry points into flashcard review and the quiz, with this lecture's progress. */
@Composable
private fun StudyActions(m: NotesPaneModel, n: StudyNotes) {
    val c = m.cardCounts
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Study this lecture", style = MaterialTheme.typography.titleMedium)
            val cardLine = when {
                c == null || c.total == 0 -> "${n.flashcards.size} flashcards"
                c.due + c.newCards == 0 -> "${c.total} flashcards · all reviewed for now"
                else -> "${c.total} flashcards · " + listOfNotNull(
                    if (c.due > 0) "${c.due} due" else null,
                    if (c.newCards > 0) "${c.newCards} new" else null,
                ).joinToString(" · ")
            }
            Text(cardLine, style = MaterialTheme.typography.bodySmall)
            val last = m.attempts.firstOrNull()
            if (last != null) {
                val best = m.attempts.maxOf { it.correct }
                Text(
                    "Quiz: last ${last.correct}/${last.total}, best $best/${last.total}, ${m.attempts.size} attempt${if (m.attempts.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = m.onStudyCards, enabled = n.flashcards.isNotEmpty()) { Text("Review flashcards") }
                OutlinedButton(onClick = m.onQuiz, enabled = n.quiz.isNotEmpty()) { Text("Take the quiz") }
            }
        }
    }
}

@Composable
private fun RegenerateRow(m: NotesPaneModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    val n = m.notes ?: return
    Text(
        "Made with ${n.model} on ${ProviderPreset.byId(n.provider)?.label ?: n.provider} · ${n.style.lowercase().replaceFirstChar { it.uppercase() }} style",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!open) {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Outlined.Refresh, null)
            Spacer(Modifier.width(6.dp))
            Text("Make new notes")
        }
    } else {
        GenerateCard(m, retry = false)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

/** A tappable source link: a time plays the recording there, "Slide N" opens that slide. */
@Composable
private fun RefChip(atMs: Long?, page: Int?, m: NotesPaneModel) {
    when {
        atMs != null -> TextButton(onClick = { m.onSeek(atMs) }) { Text(formatTimestamp(atMs), style = MaterialTheme.typography.labelMedium) }
        page != null && m.hasSlides -> TextButton(onClick = { m.onOpenSlide(page) }) { Text("Slide $page", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun FlipCard(front: String, back: String) {
    var flipped by rememberSaveable(front) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (flipped) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).animateContentSize().clickable { flipped = !flipped },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (flipped) "Answer" else "Question",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(if (flipped) back else front, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun QuizCard(number: Int, q: QuizQuestion, m: NotesPaneModel) {
    var chosen by rememberSaveable(q.question) { mutableStateOf<String?>(null) }
    var revealed by rememberSaveable(q.question) { mutableStateOf(false) }
    val correctColor = Color(0xFF2E7D32)
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            Text("$number. ${q.question}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            if (q.isMultipleChoice) {
                q.options.forEach { opt ->
                    val isAnswer = opt.trim().equals(q.answer.trim(), ignoreCase = true)
                    val border = when {
                        chosen == null -> null
                        isAnswer -> BorderStroke(2.dp, correctColor)
                        opt == chosen -> BorderStroke(2.dp, MaterialTheme.colorScheme.error)
                        else -> null
                    }
                    OutlinedButton(
                        onClick = { if (chosen == null) chosen = opt },
                        border = border ?: androidx.compose.material3.ButtonDefaults.outlinedButtonBorder,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) { Text(opt, modifier = Modifier.fillMaxWidth()) }
                }
            } else if (!revealed) {
                TextButton(onClick = { revealed = true }) {
                    Icon(Icons.AutoMirrored.Outlined.Send, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Show answer")
                }
            }
            val show = chosen != null || revealed
            if (show) {
                Spacer(Modifier.height(8.dp))
                if (q.isMultipleChoice) {
                    val right = chosen?.trim().equals(q.answer.trim(), ignoreCase = true)
                    Text(
                        if (right) "Correct!" else "Answer: ${q.answer}",
                        color = if (right) correctColor else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Text("Answer: ${q.answer}", fontWeight = FontWeight.SemiBold)
                }
                if (q.explanation.isNotBlank()) Text(q.explanation, style = MaterialTheme.typography.bodyMedium)
                when {
                    q.atMs != null -> TextButton(onClick = { m.onSeek(q.atMs) }) { Text("Hear it in the lecture (${formatTimestamp(q.atMs)})") }
                    q.page != null && m.hasSlides -> TextButton(onClick = { m.onOpenSlide(q.page) }) { Text("See slide ${q.page}") }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}
