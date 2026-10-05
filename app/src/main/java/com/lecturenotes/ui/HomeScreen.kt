package com.lecturenotes.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lecturenotes.app
import com.lecturenotes.audio.Importer
import com.lecturenotes.audio.RecorderService
import com.lecturenotes.audio.formatDuration
import com.lecturenotes.data.Library
import com.lecturenotes.data.Recording
import com.lecturenotes.data.RecordingStatus
import com.lecturenotes.data.Subject
import com.lecturenotes.data.SubjectWithCount
import com.lecturenotes.data.TranscriptMatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Which lectures the home list shows. */
sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Unfiled : LibraryFilter
    data class InSubject(val id: Long) : LibraryFilter
}

data class LectureItem(val recording: Recording, val subject: Subject?, val match: TranscriptMatch?)
data class LectureSection(val title: String, val items: List<LectureItem>)

data class HomeContent(
    val loaded: Boolean = false,
    val totalCount: Int = 0,
    val unfiledCount: Int = 0,
    val sections: List<LectureSection> = emptyList(),
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app

    private val _filter = MutableStateFlow<LibraryFilter>(LibraryFilter.All)
    val filter = _filter.asStateFlow()
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection = _selection.asStateFlow()

    val subjects = app.db.subjects().observeWithCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val matches = _query.debounce(250).flatMapLatest { q ->
        if (q.isBlank()) flowOf(emptyList()) else app.db.segments().observeMatches(likePattern(q))
    }

    val content = combine(app.db.recordings().observeAll(), subjects, _filter, _query, matches) { recs, subs, filter, q, hits ->
        val byId = subs.associate { it.subject.id to it.subject }
        val hitById = hits.associateBy { it.recordingId }
        val searching = q.isNotBlank()
        val visible = recs.filter { r ->
            if (searching) {
                r.title.contains(q.trim(), ignoreCase = true) || r.id in hitById
            } else when (filter) {
                LibraryFilter.All -> true
                LibraryFilter.Unfiled -> r.subjectId == null || r.subjectId !in byId
                is LibraryFilter.InSubject -> r.subjectId == filter.id
            }
        }
        val items = visible.map { LectureItem(it, it.subjectId?.let(byId::get), if (searching) hitById[it.id] else null) }
        HomeContent(
            loaded = true,
            totalCount = recs.size,
            unfiledCount = recs.count { it.subjectId == null || it.subjectId !in byId },
            sections = groupByDate(items),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeContent())

    val hasModel = combine(app.models.changes, app.models.selectedId) { _, _ -> app.models.downloaded().isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** The subject new recordings and imports are filed under, from the active filter. */
    val targetSubjectId: Long? get() = (_filter.value as? LibraryFilter.InSubject)?.id

    fun setFilter(f: LibraryFilter) { _filter.value = f }
    fun setQuery(q: String) { _query.value = q }

    fun toggleSelected(id: Long) = _selection.update { if (id in it) it - id else it + id }
    fun selectAll(ids: Collection<Long>) = _selection.update { it + ids }
    fun clearSelection() { _selection.value = emptySet() }

    fun moveSelected(subjectId: Long?) = viewModelScope.launch {
        Library.moveToSubject(app, _selection.value, subjectId)
        clearSelection()
    }

    fun deleteSelected() = viewModelScope.launch {
        Library.deleteRecordings(app, _selection.value)
        clearSelection()
    }

    /** Creates a subject; if lectures are selected they're moved into it, otherwise it becomes the filter. */
    fun createSubject(name: String, color: Int) = viewModelScope.launch {
        val id = Library.createSubject(app, name, color)
        if (_selection.value.isNotEmpty()) moveSelected(id) else _filter.value = LibraryFilter.InSubject(id)
    }

    fun updateSubject(subject: Subject) = viewModelScope.launch { app.db.subjects().update(subject) }

    fun deleteSubject(subject: Subject) = viewModelScope.launch {
        Library.deleteSubject(app, subject)
        if (_filter.value == LibraryFilter.InSubject(subject.id)) _filter.value = LibraryFilter.All
    }

    private fun likePattern(q: String): String {
        val escaped = q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    private fun groupByDate(items: List<LectureItem>): List<LectureSection> {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 24 * 60 * 60 * 1000L
        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        return items
            .groupBy { item ->
                val t = item.recording.createdAt
                when {
                    t >= startOfToday -> "Today"
                    t >= startOfToday - day -> "Yesterday"
                    t >= startOfToday - 6 * day -> "Earlier this week"
                    else -> monthFormat.format(Date(t))
                }
            }
            .map { (title, list) -> LectureSection(title, list) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (id: Long, atMs: Long?) -> Unit,
    onRecord: (subjectId: Long?) -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    onEditSubject: (Long) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val content by vm.content.collectAsState()
    val subjects by vm.subjects.collectAsState()
    val filter by vm.filter.collectAsState()
    val query by vm.query.collectAsState()
    val selection by vm.selection.collectAsState()
    val hasModel by vm.hasModel.collectAsState()
    val recorder by RecorderService.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searching by rememberSaveable { mutableStateOf(false) }
    var editingSubject by remember { mutableStateOf<Subject?>(null) }
    var creatingSubject by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()

    BackHandler(enabled = selecting) { vm.clearSelection() }
    BackHandler(enabled = searching && !selecting) { searching = false; vm.setQuery("") }

    var importing by remember { mutableStateOf(false) }
    fun import(uri: Uri, title: String? = null, cleanup: () -> Unit = {}) = scope.launch {
        importing = true
        runCatching { Importer.import(context, uri, title, vm.targetSubjectId) }
            .onSuccess { onOpen(it, null) }
            .onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
        cleanup()
        importing = false
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) import(uri)
    }

    // The camera app writes the video to our cache; only its audio is kept, then the video is deleted.
    var pendingVideo by rememberSaveable { mutableStateOf<String?>(null) }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { saved ->
        val path = pendingVideo ?: return@rememberLauncherForActivityResult
        pendingVideo = null
        val file = File(path)
        if (saved && file.length() > 0) {
            val title = "Video " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())
            import(videoUri(context, file), title) { file.delete() }
        } else {
            file.delete()
        }
    }
    fun recordVideo() {
        val file = File(File(context.cacheDir, "videos").apply { mkdirs() }, "capture_${System.currentTimeMillis()}.mp4")
        pendingVideo = file.path
        runCatching { captureVideo.launch(videoUri(context, file)) }
            .onFailure { Toast.makeText(context, "No camera app found", Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = {
            when {
                selecting -> SelectionBar(
                    count = selection.size,
                    onClose = vm::clearSelection,
                    onSelectAll = { vm.selectAll(content.sections.flatMap { s -> s.items.map { it.recording.id } }) },
                    onMove = { moving = true },
                    onDelete = { confirmDelete = true },
                )
                searching -> SearchBar(
                    query = query,
                    onQuery = vm::setQuery,
                    onClose = { searching = false; vm.setQuery("") },
                )
                else -> TopAppBar(
                    title = { Text("Lecture Notes") },
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = "Search lectures and transcripts")
                        }
                        IconButton(onClick = ::recordVideo) {
                            Icon(Icons.Outlined.Videocam, contentDescription = "Record video")
                        }
                        IconButton(onClick = { pickFile.launch(arrayOf("audio/*", "video/*")) }) {
                            Icon(Icons.Outlined.FileUpload, contentDescription = "Import audio or video file")
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selecting && !searching) {
                ExtendedFloatingActionButton(
                    onClick = { onRecord(vm.targetSubjectId) },
                    icon = { Icon(if (recorder.active) Icons.Filled.FiberManualRecord else Icons.Filled.Mic, null) },
                    text = { Text(if (recorder.active) formatDuration(recorder.elapsedMs) else "Record") },
                    containerColor = if (recorder.active) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (!searching && content.totalCount > 0) {
                SubjectChips(
                    subjects = subjects,
                    filter = filter,
                    totalCount = content.totalCount,
                    unfiledCount = content.unfiledCount,
                    onFilter = { vm.setFilter(it); vm.clearSelection() },
                    onNew = { creatingSubject = true },
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (importing) item {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("Extracting audio…", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (!hasModel && !searching) item { NoModelCard(onModels) }

                val current = (filter as? LibraryFilter.InSubject)?.let { f -> subjects.firstOrNull { it.subject.id == f.id } }
                if (!searching && current != null) item {
                    SubjectHeader(current) { onEditSubject(current.subject.id) }
                }

                if (content.loaded && content.sections.isEmpty()) item {
                    when {
                        searching && query.isNotBlank() -> EmptyState(
                            Icons.Outlined.Search, "No matches",
                            "Nothing in your lecture titles or transcripts matches “${query.trim()}”.",
                        )
                        searching -> EmptyState(
                            Icons.Outlined.Search, "Search your lectures",
                            "Find any word said in any lecture, or a lecture by its title.",
                        )
                        content.totalCount == 0 -> EmptyState(
                            Icons.Filled.Mic, "No recordings yet",
                            "Record a lecture, film one with the camera button, or import an audio or video file.",
                        )
                        current != null -> EmptyState(
                            Icons.Outlined.Folder, "No lectures in ${current.subject.name} yet",
                            "Anything you record or import while this subject is selected is filed here. " +
                                "You can also long-press lectures elsewhere and move them.",
                        )
                        else -> EmptyState(Icons.Outlined.Inbox, "Everything is filed", "No unfiled lectures.")
                    }
                }

                content.sections.forEach { section ->
                    item(key = "h-${section.title}") {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(section.items, key = { it.recording.id }) { item ->
                        LectureCard(
                            item = item,
                            showSubject = filter !is LibraryFilter.InSubject || searching,
                            query = query.trim(),
                            selecting = selecting,
                            selected = item.recording.id in selection,
                            onClick = {
                                if (selecting) vm.toggleSelected(item.recording.id)
                                else onOpen(item.recording.id, item.match?.startMs)
                            },
                            onLongClick = { vm.toggleSelected(item.recording.id) },
                        )
                    }
                }
            }
        }
    }

    if (creatingSubject) {
        SubjectEditDialog(
            initial = null,
            suggestedColor = subjects.size % Library.SUBJECT_COLOR_COUNT,
            onDismiss = { creatingSubject = false },
            onSave = { name, color -> creatingSubject = false; vm.createSubject(name, color) },
        )
    }
    editingSubject?.let { s ->
        SubjectEditDialog(
            initial = s,
            suggestedColor = s.colorIndex,
            onDismiss = { editingSubject = null },
            onSave = { name, color -> editingSubject = null; vm.updateSubject(s.copy(name = name, colorIndex = color)) },
            onDelete = { editingSubject = null; vm.deleteSubject(s) },
        )
    }
    val selectedSubjects = content.sections.flatMap { it.items }
        .filter { it.recording.id in selection }
        .map { it.subject?.id }
        .distinct()
    if (moving) {
        SubjectPickerSheet(
            title = "Move ${selection.size} lecture${if (selection.size == 1) "" else "s"} to…",
            subjects = subjects,
            currentId = selectedSubjects.singleOrNull(),
            hasCurrent = selectedSubjects.size == 1,
            onDismiss = { moving = false },
            onPick = { moving = false; vm.moveSelected(it) },
            onCreate = { name, color -> moving = false; vm.createSubject(name, color) },
        )
    }
    if (confirmDelete) {
        val n = selection.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $n lecture${if (n == 1) "" else "s"}?") },
            text = { Text("The audio and transcripts will be permanently removed.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel selection") } },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, "Select all") }
            IconButton(onClick = onMove) { Icon(Icons.Outlined.Folder, "Move to subject") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search titles and transcripts") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "Clear search") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubjectChips(
    subjects: List<SubjectWithCount>,
    filter: LibraryFilter,
    totalCount: Int,
    unfiledCount: Int,
    onFilter: (LibraryFilter) -> Unit,
    onNew: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        item {
            FilterChip(
                selected = filter == LibraryFilter.All,
                onClick = { onFilter(LibraryFilter.All) },
                label = { Text("All  $totalCount") },
            )
        }
        items(subjects, key = { it.subject.id }) { s ->
            FilterChip(
                selected = filter == LibraryFilter.InSubject(s.subject.id),
                onClick = { onFilter(LibraryFilter.InSubject(s.subject.id)) },
                leadingIcon = { SubjectDot(s.subject.colorIndex) },
                label = { Text("${s.subject.name}  ${s.count}") },
            )
        }
        if (subjects.isNotEmpty() && unfiledCount > 0) item {
            FilterChip(
                selected = filter == LibraryFilter.Unfiled,
                onClick = { onFilter(LibraryFilter.Unfiled) },
                leadingIcon = { Icon(Icons.Outlined.Inbox, null, Modifier.size(16.dp)) },
                label = { Text("Unfiled  $unfiledCount") },
            )
        }
        item {
            FilterChip(
                selected = false,
                onClick = onNew,
                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) },
                label = { Text(if (subjects.isEmpty()) "Add a subject" else "New") },
            )
        }
    }
}

@Composable
private fun SubjectHeader(s: SubjectWithCount, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SubjectDot(s.subject.colorIndex, 14.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.subject.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${s.count} lecture${if (s.count == 1) "" else "s"} · new recordings are filed here" +
                    if (s.subject.promptInstructions != null || s.subject.customPrompt != null) " · custom notes AI" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit subject") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LectureCard(
    item: LectureItem,
    showSubject: Boolean,
    query: String,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val rec = item.recording
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Subject colour strip for scanning a mixed list at a glance.
            Box(
                Modifier.width(5.dp).fillMaxHeight()
                    .background(item.subject?.let { subjectColor(it.colorIndex) } ?: Color.Transparent),
            )
            if (selecting) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp).align(Alignment.CenterVertically),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        highlight(rec.title, query),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (rec.durationMs > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(formatDuration(rec.durationMs), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showSubject && item.subject != null) {
                        Text(
                            item.subject.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = subjectColor(item.subject.colorIndex),
                            maxLines = 1,
                        )
                        Text(" · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(rec.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.match?.let { m ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(formatDuration(m.startMs) + "  ") }
                            append(highlight(snippet(m.text, query), query))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (rec.status != RecordingStatus.TRANSCRIBED) {
                    Spacer(Modifier.height(6.dp))
                    StatusWithProgress(rec)
                }
            }
        }
    }
}

@Composable
private fun NoModelCard(onModels: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Download a speech model", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Transcription runs entirely on your phone. Pick a model once and it works offline.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onModels) { Text("Choose a model") }
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Box(Modifier.fillMaxWidth().padding(top = 64.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Bolds every case-insensitive occurrence of [query] in [text]. */
@Composable
private fun highlight(text: String, query: String): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.tertiaryContainer)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val hit = text.indexOf(query, i, ignoreCase = true)
            if (hit < 0) { append(text.substring(i)); break }
            append(text.substring(i, hit))
            withStyle(style) { append(text.substring(hit, hit + query.length)) }
            i = hit + query.length
        }
    }
}

/** Trims a long transcript line so the match is visible in two lines. */
private fun snippet(text: String, query: String): String {
    val hit = text.indexOf(query, ignoreCase = true)
    if (hit < 40) return text
    // Start at a word boundary about 30 characters before the match.
    val from = text.lastIndexOf(' ', hit - 30).let { if (it < 0) 0 else it + 1 }
    return "…" + text.substring(from)
}

private fun videoUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
