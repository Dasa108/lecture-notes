package com.lecturenotes.ui

import android.app.Application
import android.media.MediaPlayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import com.lecturenotes.data.Notes
import com.lecturenotes.data.NotesStatus
import com.lecturenotes.notes.NoteStyle
import com.lecturenotes.notes.StudyNotes
import kotlinx.coroutines.flow.map
import androidx.compose.ui.platform.LocalView
import com.lecturenotes.app
import com.lecturenotes.audio.formatDuration
import com.lecturenotes.data.Library
import com.lecturenotes.data.Recording
import com.lecturenotes.data.RecordingStatus
import com.lecturenotes.data.Segment
import com.lecturenotes.transcribe.Jobs
import com.lecturenotes.transcribe.ProcessWorker
import com.lecturenotes.whisper.WhisperModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecordingViewModel(application: Application, private val id: Long) : AndroidViewModel(application) {
    private val app = application.app
    val recording = app.db.recordings().observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val segments = app.db.segments().observeFor(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val subjects = app.db.subjects().observeWithCounts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val notes = app.db.notes().observeFor(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val studyNotes = notes.map { n ->
        n?.json?.let { json ->
            runCatching { StudyNotes.parse(org.json.JSONObject(json), Long.MAX_VALUE / 2, recording.value?.title ?: "") }.getOrNull()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val aiConfig = app.aiSettings.config
    val cardCounts = app.db.reviewCards().observeCounts(System.currentTimeMillis(), null, id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val attempts = app.db.quizAttempts().observeFor(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var player: MediaPlayer? = null
    private var playerPath: String? = null
    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing = _playing.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                player?.let { if (it.isPlaying) _positionMs.value = it.currentPosition.toLong() }
                delay(200)
            }
        }
    }

    private fun ensurePlayer(): MediaPlayer? {
        val rec = recording.value ?: return null
        // Audio can't be played while it's still being captured or decoded.
        if (rec.status == RecordingStatus.RECORDING || rec.sourcePath != null) return null
        if (player != null && playerPath == rec.audioPath) return player
        release()
        if (!File(rec.audioPath).exists()) return null
        return MediaPlayer().apply {
            setDataSource(rec.audioPath)
            prepare()
            setOnCompletionListener { _playing.value = false }
            player = this
            playerPath = rec.audioPath
        }
    }

    fun togglePlay() {
        val p = ensurePlayer() ?: return
        if (p.isPlaying) p.pause() else p.start()
        _playing.value = p.isPlaying
    }

    fun seekTo(ms: Long, play: Boolean = false) {
        val p = ensurePlayer() ?: return
        p.seekTo(ms.toInt())
        _positionMs.value = ms
        if (play && !p.isPlaying) {
            p.start()
            _playing.value = true
        }
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        _playing.value = false
    }

    /** Starts (or resumes, if the last attempt failed with the same settings) notes generation. */
    fun generateNotes(style: NoteStyle) = viewModelScope.launch {
        val ai = aiConfig.value
        val model = ai.model ?: return@launch
        val previous = app.db.notes().getFor(id)
        val resumable = previous?.takeIf {
            it.status == NotesStatus.FAILED && it.style == style.name && it.provider == ai.preset.id && it.model == model
        }?.partialJson
        app.db.notes().upsert(
            Notes(
                recordingId = id, status = NotesStatus.QUEUED, style = style.name,
                provider = ai.preset.id, model = model, partialJson = resumable, message = "Waiting for the network…",
                // Keep the previous notes until the new ones are finished, in case this run fails.
                json = previous?.json,
            ),
        )
        Jobs.generateNotes(app, id)
    }

    fun cancelNotes() = viewModelScope.launch {
        Jobs.cancelNotes(app, id)
        app.db.notes().setStatus(id, NotesStatus.FAILED, "Cancelled.")
    }

    fun rename(title: String) = viewModelScope.launch { app.db.recordings().rename(id, title.trim()) }

    fun editSegment(segment: Segment, text: String) =
        viewModelScope.launch { app.db.segments().updateText(segment.id, text.trim()) }

    fun transcribe() = Jobs.process(app, id)

    fun cancel() = viewModelScope.launch { Jobs.cancelProcessing(app, id) }

    /** Throws away the transcript and starts again, e.g. after switching to a better model. */
    fun retranscribe() = viewModelScope.launch {
        Jobs.cancelProcessing(app, id)
        val rec = app.db.recordings().get(id) ?: return@launch
        app.db.segments().deleteFrom(id, 0)
        app.db.recordings().update(rec.copy(transcribedMs = 0, progress = 0f, status = RecordingStatus.READY, error = null))
        Jobs.process(app, id)
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        release()
        Library.deleteRecordings(app, listOf(id))
        onDone()
    }

    fun setSubject(subjectId: Long?) = viewModelScope.launch { Library.moveToSubject(app, listOf(id), subjectId) }

    fun createSubject(name: String, color: Int) = viewModelScope.launch {
        Library.moveToSubject(app, listOf(id), Library.createSubject(app, name, color))
    }

    private fun release() {
        player?.release()
        player = null
        playerPath = null
        _playing.value = false
    }

    override fun onCleared() = release()
}

/** Items above the transcript lines in the Transcript tab (subject chip, status header). */
private const val HEADER_ITEMS = 2

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordingScreen(
    recordingId: Long,
    startAtMs: Long?,
    onBack: () -> Unit,
    onModels: () -> Unit,
    onNotesAi: () -> Unit,
    onEditSubject: (Long) -> Unit,
    onStudyCards: (Long) -> Unit,
    onQuiz: (Long) -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val vm: RecordingViewModel = viewModel(
        key = "recording-$recordingId",
        factory = viewModelFactory { initializer { RecordingViewModel(application, recordingId) } },
    )
    val rec by vm.recording.collectAsState()
    val segments by vm.segments.collectAsState()
    val position by vm.positionMs.collectAsState()
    val playing by vm.playing.collectAsState()
    val subjects by vm.subjects.collectAsState()
    var pickingSubject by remember { mutableStateOf(false) }
    var jumped by rememberSaveable { mutableStateOf(false) }
    val notes by vm.notes.collectAsState()
    val study by vm.studyNotes.collectAsState()
    val ai by vm.aiConfig.collectAsState()
    val cardCounts by vm.cardCounts.collectAsState()
    val attempts by vm.attempts.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    val context = LocalContext.current

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Segment?>(null) }

    DisposableEffect(Unit) { onDispose { vm.pause() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Tap the title to rename the lecture.
                    Text(
                        rec?.title ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClickLabel = "Rename lecture") { renaming = true },
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = true })
                        DropdownMenuItem(
                            text = { Text("Transcribe again") },
                            enabled = rec?.let { !it.isBusy && it.status != RecordingStatus.RECORDING } == true,
                            onClick = { menuOpen = false; vm.retranscribe() },
                        )
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; confirmDelete = true })
                    }
                },
            )
        },
        bottomBar = {
            rec?.let { r ->
                if (r.durationMs > 0 && r.sourcePath == null && r.status != RecordingStatus.RECORDING) {
                    PlayerBar(position, r.durationMs, playing, vm::togglePlay) { vm.seekTo(it) }
                }
            }
        },
    ) { padding ->
        val r = rec ?: return@Scaffold
        val listState = rememberLazyListState()
        val activeIndex by remember(segments) {
            derivedStateOf { segments.indexOfLast { it.startMs <= position } }
        }
        // Opened from a search result: cue the audio at the matching line and show it.
        LaunchedEffect(segments.isNotEmpty()) {
            if (!jumped && startAtMs != null && segments.isNotEmpty()) {
                jumped = true
                vm.seekTo(startAtMs)
                val index = segments.indexOfFirst { it.startMs >= startAtMs }.coerceAtLeast(0)
                listState.scrollToItem(index + HEADER_ITEMS)
            }
        }
        // Keep the line being played in view.
        LaunchedEffect(activeIndex, playing) {
            if (tab == 0 && playing && activeIndex >= 0) listState.animateScrollToItem(activeIndex + HEADER_ITEMS)
        }

        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Transcript") })
            Tab(
                selected = tab == 1, onClick = { tab = 1 },
                text = { Text(if (notes?.status == NotesStatus.GENERATING || notes?.status == NotesStatus.QUEUED) "Notes…" else "Notes") },
            )
        }
        if (tab == 1) {
            val model = NotesPaneModel(
                notes = notes,
                study = study,
                ai = ai,
                hasTranscript = segments.isNotEmpty() && r.status == RecordingStatus.TRANSCRIBED,
                transcriptChars = segments.sumOf { it.text.length + 8 },
                onGenerate = vm::generateNotes,
                onCancel = { vm.cancelNotes() },
                onSeek = { vm.seekTo(it, play = true) },
                onSetUpAi = onNotesAi,
                subject = subjects.firstOrNull { it.subject.id == r.subjectId }?.subject,
                onEditSubject = onEditSubject,
                cardCounts = cardCounts,
                attempts = attempts,
                onStudyCards = { onStudyCards(recordingId) },
                onQuiz = { onQuiz(recordingId) },
                onShare = {
                    study?.let { n ->
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, n.title).putExtra(Intent.EXTRA_TEXT, n.toMarkdown()),
                                "Share notes",
                            ),
                        )
                    }
                },
            )
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) { notesPane(model) }
            return@Column
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 8.dp, bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                val subject = subjects.firstOrNull { it.subject.id == r.subjectId }?.subject
                AssistChip(
                    onClick = { pickingSubject = true },
                    leadingIcon = {
                        if (subject != null) SubjectDot(subject.colorIndex)
                        else Icon(Icons.Outlined.Inbox, null, Modifier.size(16.dp))
                    },
                    label = { Text(subject?.name ?: "Add to a subject") },
                )
            }
            item { StatusHeader(r, segments.isEmpty(), vm::transcribe, vm::cancel, onModels) }
            itemsIndexed(segments, key = { _, s -> s.id }) { index, seg ->
                SegmentRow(
                    seg,
                    active = index == activeIndex && (playing || position > 0),
                    onClick = { vm.seekTo(seg.startMs, play = true) },
                    onLongClick = { editing = seg },
                )
            }
        }
        }
    }

    if (pickingSubject) {
        SubjectPickerSheet(
            title = "File this lecture under…",
            subjects = subjects,
            currentId = rec?.subjectId,
            onDismiss = { pickingSubject = false },
            onPick = { pickingSubject = false; vm.setSubject(it) },
            onCreate = { name, color -> pickingSubject = false; vm.createSubject(name, color) },
        )
    }
    if (renaming) {
        TextInputDialog("Rename", rec?.title ?: "", onDismiss = { renaming = false }) {
            vm.rename(it)
            renaming = false
        }
    }
    editing?.let { seg ->
        TextInputDialog("Edit transcript", seg.text, singleLine = false, onDismiss = { editing = null }) {
            vm.editSegment(seg, it)
            editing = null
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete recording?") },
            text = { Text("The audio and transcript will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusHeader(
    rec: Recording,
    noSegments: Boolean,
    onTranscribe: () -> Unit,
    onCancel: () -> Unit,
    onModels: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        val model = WhisperModels.byId(rec.modelId)
        val sub = buildString {
            if (rec.durationMs > 0) append(formatDuration(rec.durationMs))
            if (model != null) append("  ·  ${model.displayName} model")
        }
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        if (rec.status != RecordingStatus.TRANSCRIBED) StatusWithProgress(rec)
        if (rec.isBusy) {
            // Many phones confine background apps to their slower cores; while this page is
            // open the app gets all of them, so keep the screen on.
            val view = LocalView.current
            DisposableEffect(Unit) {
                view.keepScreenOn = true
                onDispose { view.keepScreenOn = false }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Tip: keep this page open to transcribe faster. The screen stays on until it's done. " +
                    "In the background your phone may limit the app to its slower cores.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            rec.isBusy -> {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel) {
                    Text(if (rec.status == RecordingStatus.IMPORTING) "Cancel import" else "Cancel transcription")
                }
            }
            rec.status == RecordingStatus.READY && rec.error == ProcessWorker.NO_MODEL -> {
                Spacer(Modifier.height(8.dp))
                Text(rec.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onModels) { Text("Choose a model") }
            }
            rec.status == RecordingStatus.READY || rec.status == RecordingStatus.FAILED -> {
                rec.error?.takeIf { rec.status == RecordingStatus.FAILED }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onTranscribe) {
                    Text(if (rec.transcribedMs > 0) "Continue transcribing" else "Transcribe")
                }
            }
            rec.status == RecordingStatus.TRANSCRIBED && noSegments -> {
                Text("No speech was detected in this recording.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentRow(seg: Segment, active: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            formatDuration(seg.startMs),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(64.dp).padding(top = 2.dp),
        )
        Text(seg.text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PlayerBar(positionMs: Long, durationMs: Long, playing: Boolean, onToggle: () -> Unit, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(onClick = onToggle) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play")
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Slider(
                    value = if (dragging) dragValue else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = {
                        dragging = false
                        onSeek((dragValue * durationMs).toLong())
                    },
                )
                Row {
                    Text(formatDuration(positionMs), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = singleLine, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
