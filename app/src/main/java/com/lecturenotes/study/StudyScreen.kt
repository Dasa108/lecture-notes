package com.lecturenotes.study

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lecturenotes.app
import com.lecturenotes.data.LectureCards
import com.lecturenotes.data.ReviewCard
import com.lecturenotes.data.Subject
import com.lecturenotes.ui.SubjectDot
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.style.TextOverflow
import com.lecturenotes.notes.formatTimestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

enum class StudyPhase { LOADING, PICK, STUDY }

data class StudyUiState(
    val phase: StudyPhase = StudyPhase.LOADING,
    // Picker
    val lectures: List<LectureCards> = emptyList(),
    val subjects: List<Subject> = emptyList(),
    val selected: Set<Long> = emptySet(),
    /** Exam revision: every card in the selected lectures, without changing their schedule. */
    val cram: Boolean = false,
    val newAllowedToday: Int = StudyViewModel.NEW_PER_DAY,
    // Session
    val current: ReviewCard? = null,
    val revealed: Boolean = false,
    val remaining: Int = 0,
    val sessionTotal: Int = 0,
    val reviewed: Int = 0,
    val again: Int = 0,
    /** Next-review label for each rating of the current card. */
    val intervals: Map<Rating, String> = emptyMap(),
    /** When nothing is left: when the next card in the selected lectures becomes due. */
    val nextDueAt: Long? = null,
    val newLimitReached: Boolean = false,
) {
    val lectureTitles: Map<Long, String> get() = lectures.associate { it.recordingId to it.title }

    /** How many cards pressing Start will give. */
    val startCount: Int
        get() {
            val chosen = lectures.filter { it.recordingId in selected }
            return if (cram) chosen.sumOf { it.total } else chosen.sumOf { it.due } + minOf(newAllowedToday, chosen.sumOf { it.newCards })
        }
}

class StudyViewModel(application: Application, private val subjectId: Long?, private val recordingId: Long?) :
    AndroidViewModel(application) {
    private val db = application.app.db
    private val cards = db.reviewCards()
    private val queue = ArrayDeque<ReviewCard>()
    private val _state = MutableStateFlow(StudyUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            loadPicker(preselect = true)
            // From a lecture's Notes tab: go straight into that lecture.
            if (recordingId != null && _state.value.lectures.any { it.recordingId == recordingId }) start()
        }
    }

    private suspend fun loadPicker(preselect: Boolean) {
        val lectures = cards.lectureCards(System.currentTimeMillis())
        val subjects = db.subjects().observeWithCounts().first().map { it.subject }
        val newAllowed = (NEW_PER_DAY - cards.newStudiedSince(startOfToday())).coerceAtLeast(0)
        val selected = if (!preselect) {
            _state.value.selected.filter { id -> lectures.any { it.recordingId == id } }.toSet()
        } else {
            when {
                recordingId != null -> setOf(recordingId)
                subjectId != null -> lectures.filter { it.subjectId == subjectId }.map { it.recordingId }.toSet()
                else -> lectures.filter { it.due + it.newCards > 0 }.map { it.recordingId }.toSet()
                    .ifEmpty { lectures.map { it.recordingId }.toSet() }
            }
        }
        _state.update {
            it.copy(
                phase = StudyPhase.PICK, lectures = lectures, subjects = subjects, selected = selected,
                newAllowedToday = newAllowed, current = null, reviewed = 0, again = 0,
            )
        }
    }

    fun toggle(recordingId: Long) = _state.update {
        it.copy(selected = if (recordingId in it.selected) it.selected - recordingId else it.selected + recordingId)
    }

    /** Selects every lecture in a subject (null = all lectures), or clears them if all are already selected. */
    fun toggleGroup(subjectId: Long?, all: Boolean) = _state.update { s ->
        val ids = s.lectures.filter { all || it.subjectId == subjectId }.map { it.recordingId }.toSet()
        s.copy(selected = if (s.selected.containsAll(ids)) s.selected - ids else s.selected + ids)
    }

    fun setCram(cram: Boolean) = _state.update { it.copy(cram = cram) }

    fun start() = viewModelScope.launch {
        val s = _state.value
        val ids = s.selected.toList()
        if (ids.isEmpty()) return@launch
        queue.clear()
        var limitReached = false
        if (s.cram) {
            queue.addAll(cards.allFor(ids).shuffled())
        } else {
            val all = cards.studyQueueFor(System.currentTimeMillis(), ids)
            val fresh = all.filter { it.isNew }
            limitReached = fresh.size > s.newAllowedToday
            queue.addAll(all.filter { !it.isNew } + fresh.take(s.newAllowedToday))
        }
        _state.update { it.copy(phase = StudyPhase.STUDY, sessionTotal = queue.size, reviewed = 0, again = 0, newLimitReached = limitReached) }
        advance()
    }

    fun backToPicker() = viewModelScope.launch { loadPicker(preselect = false) }

    fun reveal() = _state.update { it.copy(revealed = true) }

    fun rate(rating: Rating) = viewModelScope.launch {
        val card = _state.value.current ?: return@launch
        if (_state.value.cram) {
            // Revision doesn't touch the schedule; missed cards come round again this session.
            if (rating == Rating.AGAIN) queue.addLast(card)
        } else {
            val now = System.currentTimeMillis()
            val outcome = Scheduler.next(card.toState(), rating)
            val updated = card.copy(
                ease = outcome.state.ease,
                intervalDays = outcome.state.intervalDays,
                reps = outcome.state.reps,
                lapses = outcome.state.lapses,
                dueAt = now + outcome.dueInMs,
                firstReviewedAt = card.firstReviewedAt ?: now,
                lastReviewedAt = now,
            )
            cards.update(updated)
            // Cards due again within a few minutes come back later in this session.
            if (outcome.dueInMs < Scheduler.SAME_SESSION_MS) queue.addLast(updated)
        }
        _state.update { it.copy(reviewed = it.reviewed + 1, again = it.again + if (rating == Rating.AGAIN) 1 else 0) }
        advance()
    }

    private suspend fun advance() {
        val next = queue.removeFirstOrNull()
        val s = _state.value
        val nextDue = if (next == null && !s.cram) cards.nextDueFor(System.currentTimeMillis(), s.selected.toList()) else null
        _state.update {
            it.copy(
                current = next,
                revealed = false,
                remaining = queue.size + if (next != null) 1 else 0,
                intervals = if (next == null || it.cram) emptyMap()
                else Rating.entries.associateWith { r -> Scheduler.label(Scheduler.next(next.toState(), r).dueInMs) },
                nextDueAt = nextDue,
            )
        }
    }

    private fun ReviewCard.toState() = CardState(ease, intervalDays, reps, lapses)

    private fun startOfToday() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object {
        /** New cards introduced per day, so a long lecture doesn't swamp the student. */
        const val NEW_PER_DAY = 20
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(subjectId: Long?, recordingId: Long?, onBack: () -> Unit, onOpenLecture: (Long, Long?) -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val vm: StudyViewModel = viewModel(
        key = "study-$subjectId-$recordingId",
        factory = viewModelFactory { initializer { StudyViewModel(application, subjectId, recordingId) } },
    )
    val s by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (s.phase == StudyPhase.PICK) "Study flashcards" else "Flashcards")
                        val sub = when {
                            s.phase == StudyPhase.PICK -> "Choose lectures"
                            s.cram -> "Exam revision · schedule unchanged"
                            s.selected.size == 1 -> s.lectureTitles[s.selected.first()].orEmpty()
                            else -> "${s.selected.size} lectures"
                        }
                        Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (s.phase == StudyPhase.LOADING) return@Column
            if (s.phase == StudyPhase.PICK) {
                Picker(s, vm)
                return@Column
            }
            val card = s.current
            if (card == null) {
                Done(s, onBack, vm::backToPicker)
                return@Column
            }
            val done = (s.sessionTotal - s.remaining).coerceAtLeast(0)
            LinearProgressIndicator(
                progress = { if (s.sessionTotal == 0) 0f else done.toFloat() / s.sessionTotal },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${s.remaining} left" + if (card.isNew && !s.cram) " · new card" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().weight(1f).clickable(enabled = !s.revealed) { vm.reveal() },
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text(card.front, style = MaterialTheme.typography.headlineSmall)
                    AnimatedContent(s.revealed, label = "answer") { revealed ->
                        if (revealed) {
                            Column {
                                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                                Text(card.back, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
                            }
                        } else {
                            Text(
                                "Tap to show the answer",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                    }
                }
            }

            val lecture = s.lectureTitles[card.recordingId]
            if (lecture != null) {
                TextButton(onClick = { onOpenLecture(card.recordingId, card.atMs) }) {
                    Text("From “$lecture”" + (card.atMs?.let { " · ${formatTimestamp(it)}" } ?: ""), maxLines = 1)
                }
            }

            if (!s.revealed) {
                Button(onClick = vm::reveal, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Show answer") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (s.cram) {
                        // Revision: just "again later" or "got it"; the schedule isn't touched.
                        RateButton("Missed", "see again", Modifier.weight(1f), danger = true) { vm.rate(Rating.AGAIN) }
                        RateButton("Got it", null, Modifier.weight(1f), primary = true) { vm.rate(Rating.GOOD) }
                    } else {
                        RateButton("Again", s.intervals[Rating.AGAIN], Modifier.weight(1f), danger = true) { vm.rate(Rating.AGAIN) }
                        RateButton("Hard", s.intervals[Rating.HARD], Modifier.weight(1f)) { vm.rate(Rating.HARD) }
                        RateButton("Good", s.intervals[Rating.GOOD], Modifier.weight(1f), primary = true) { vm.rate(Rating.GOOD) }
                        RateButton("Easy", s.intervals[Rating.EASY], Modifier.weight(1f)) { vm.rate(Rating.EASY) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RateButton(label: String, interval: String?, modifier: Modifier, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label)
            if (interval != null) Text(interval, style = MaterialTheme.typography.labelSmall)
        }
    }
    when {
        primary -> Button(onClick = onClick, modifier = modifier.heightIn(min = 56.dp)) { content() }
        danger -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { content() }
        else -> FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 56.dp)) { content() }
    }
}

@Composable
private fun Done(s: StudyUiState, onBack: () -> Unit, onPickMore: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(
                if (s.reviewed > 0) "Session done" else "Nothing to review right now",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            val lines = buildList {
                if (s.reviewed > 0) add("${s.reviewed} reviews · ${s.again} marked Again")
                s.nextDueAt?.let { add("Next card due in ${Scheduler.label(it - System.currentTimeMillis())}") }
                if (s.newLimitReached) add("More new cards unlock tomorrow (${StudyViewModel.NEW_PER_DAY} a day).")
                if (s.sessionTotal == 0 && !s.cram) add("Try “All cards” to revise these lectures anyway.")
            }
            lines.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickMore) { Text("Choose lectures") }
                Button(onClick = onBack) { Text("Done") }
            }
        }
    }
}

/** Choose which lectures to study and how. */
@Composable
private fun ColumnScope.Picker(s: StudyUiState, vm: StudyViewModel) {
    if (s.lectures.isEmpty()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(
                "No flashcards yet. Open a lecture's Notes tab and generate notes to get flashcards.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Quick select: everything, or a whole subject.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val allIds = s.lectures.map { it.recordingId }.toSet()
        FilterChip(selected = s.selected == allIds, onClick = { vm.toggleGroup(null, all = true) }, label = { Text("All") })
        s.subjects.filter { sub -> s.lectures.any { it.subjectId == sub.id } }.forEach { sub ->
            val ids = s.lectures.filter { it.subjectId == sub.id }.map { it.recordingId }
            FilterChip(
                selected = s.selected.containsAll(ids),
                onClick = { vm.toggleGroup(sub.id, all = false) },
                leadingIcon = { SubjectDot(sub.colorIndex) },
                label = { Text(sub.name) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    val subjectsById = s.subjects.associateBy { it.id }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(s.lectures, key = { it.recordingId }) { l ->
            val checked = l.recordingId in s.selected
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth().clickable { vm.toggle(l.recordingId) },
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { vm.toggle(l.recordingId) })
                    Column(Modifier.weight(1f)) {
                        Text(l.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            subjectsById[l.subjectId]?.let {
                                SubjectDot(it.colorIndex)
                                Text(" ${it.name} · ", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                listOfNotNull(
                                    if (l.due > 0) "${l.due} due" else null,
                                    if (l.newCards > 0) "${l.newCards} new" else null,
                                    "${l.total} cards",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !s.cram, onClick = { vm.setCram(false) }, label = { Text("Due cards") })
        FilterChip(selected = s.cram, onClick = { vm.setCram(true) }, label = { Text("All cards (exam revision)") })
    }
    Text(
        if (s.cram) "Every card from these lectures, shuffled. Your review schedule isn't changed."
        else "Cards scheduled for today, plus up to ${s.newAllowedToday} new ones.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    val count = s.startCount
    Button(
        onClick = vm::start,
        enabled = count > 0,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Text(
            when {
                s.selected.isEmpty() -> "Select lectures to study"
                count == 0 -> "Nothing due. Try All cards"
                else -> "Study $count card${if (count == 1) "" else "s"}"
            },
        )
    }
}
