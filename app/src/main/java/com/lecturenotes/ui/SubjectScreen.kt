package com.lecturenotes.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lecturenotes.app
import com.lecturenotes.data.Library
import com.lecturenotes.data.Subject
import com.lecturenotes.notes.NoteStyle
import com.lecturenotes.notes.Prompts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubjectEdit(
    val original: Subject,
    val name: String,
    val colorIndex: Int,
    val type: Prompts.SubjectType,
    val instructions: String,
    val defaultStyle: NoteStyle?,
    /** Non-null while the advanced full-prompt editor is in use. */
    val customPrompt: String?,
) {
    val subject: Subject
        get() = original.copy(
            name = name.trim(),
            colorIndex = colorIndex,
            promptType = type.name,
            promptInstructions = instructions.trim().ifBlank { null },
            customPrompt = customPrompt?.trim()?.ifBlank { null },
            defaultStyle = defaultStyle?.name,
        )
}

class SubjectViewModel(application: Application, private val id: Long) : AndroidViewModel(application) {
    private val app = application.app
    private val _edit = MutableStateFlow<SubjectEdit?>(null)
    val edit = _edit.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            val s = app.db.subjects().get(id) ?: return@launch
            _edit.value = SubjectEdit(
                original = s,
                name = s.name,
                colorIndex = s.colorIndex,
                type = Prompts.SubjectType.byName(s.promptType),
                instructions = s.promptInstructions.orEmpty(),
                defaultStyle = s.defaultStyle?.let { runCatching { NoteStyle.valueOf(it) }.getOrNull() },
                customPrompt = s.customPrompt,
            )
        }
    }

    fun update(f: (SubjectEdit) -> SubjectEdit) {
        _edit.update { it?.let(f) }
        _saved.value = false
    }

    /** Opens the advanced editor, prefilled with what the simple settings currently produce. */
    fun startAdvanced() = update {
        it.copy(customPrompt = it.customPrompt ?: Prompts.defaultGuidance(it.type, it.instructions))
    }

    /** Drops the advanced prompt and goes back to template + instructions. */
    fun resetToTemplate() = update { it.copy(customPrompt = null) }

    fun save() = viewModelScope.launch {
        val e = _edit.value ?: return@launch
        if (e.name.isBlank()) return@launch
        app.db.subjects().update(e.subject)
        _saved.value = true
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        _edit.value?.let { Library.deleteSubject(app, it.original) }
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectScreen(subjectId: Long, onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val vm: SubjectViewModel = viewModel(
        key = "subject-$subjectId",
        factory = viewModelFactory { initializer { SubjectViewModel(application, subjectId) } },
    )
    val e by vm.edit.collectAsState()
    val saved by vm.saved.collectAsState()
    var previewing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(e?.name?.ifBlank { "Subject" } ?: "Subject") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = vm::save, enabled = e?.name?.isNotBlank() == true && !saved) {
                        Text(if (saved) "Saved" else "Save")
                    }
                },
            )
        },
    ) { padding ->
        val edit = e ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                edit.name, { v -> vm.update { it.copy(name = v) } },
                label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until Library.SUBJECT_COLOR_COUNT).forEach { i ->
                    val selected = i == edit.colorIndex
                    Box(
                        Modifier.size(28.dp).background(subjectColor(i), CircleShape)
                            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { vm.update { it.copy(colorIndex = i) } },
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(Icons.Filled.Check, "Selected colour", Modifier.size(16.dp), tint = Color.White) }
                }
            }

            HorizontalDivider()
            Text("Notes AI for this subject", style = MaterialTheme.typography.titleMedium)
            Text(
                "These instructions are added to the prompt for every lecture filed under ${edit.name.ifBlank { "this subject" }}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val advanced = edit.customPrompt != null
            if (!advanced) {
                Text("Type of subject", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Prompts.SubjectType.entries.forEach { t ->
                        FilterChip(selected = t == edit.type, onClick = { vm.update { it.copy(type = t) } }, label = { Text(t.label) })
                    }
                }
                if (edit.type.template.isNotBlank()) {
                    Text(
                        edit.type.template,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    edit.instructions, { v -> vm.update { it.copy(instructions = v) } },
                    label = { Text("Your instructions") },
                    placeholder = {
                        Text(
                            "e.g. 3rd-year B.Tech AI & Data Science. Exams are theory + numericals. " +
                                "Terms the lecturer uses: Bayes theorem, perceptron, k-means, ReLU, backpropagation.",
                        )
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Useful things to add: your course and year, exam pattern, how detailed or simple to be, " +
                        "and technical terms or names the speech recognition might mishear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Default note style", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = edit.defaultStyle == null, onClick = { vm.update { it.copy(defaultStyle = null) } }, label = { Text("Ask") })
                NoteStyle.entries.forEach { st ->
                    FilterChip(selected = edit.defaultStyle == st, onClick = { vm.update { it.copy(defaultStyle = st) } }, label = { Text(st.label) })
                }
            }

            // Advanced: edit the whole guidance part of the prompt.
            Row(
                Modifier.fillMaxWidth().clickable { if (advanced) Unit else vm.startAdvanced() }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced: edit full prompt", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
            }
            if (advanced) {
                Text(
                    "You're editing the whole prompt (it replaces the type and instructions above). " +
                        "The app always adds the note format it needs, the style and the transcript.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    edit.customPrompt.orEmpty(), { v -> vm.update { it.copy(customPrompt = v) } },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = vm::resetToTemplate) { Text("Reset to template + instructions") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { previewing = true }) { Text("Preview prompt") }
                Button(onClick = vm::save, enabled = edit.name.isNotBlank() && !saved) { Text(if (saved) "Saved" else "Save") }
            }

            HorizontalDivider()
            TextButton(onClick = { confirmDelete = true }) {
                Text("Delete subject", color = MaterialTheme.colorScheme.error)
            }
        }

        if (previewing) PromptPreviewDialog(edit) { previewing = false }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete “${edit.original.name}”?") },
                text = { Text("Its lectures won't be deleted. They'll move to Unfiled.") },
                confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete subject") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
    }
}

/** Shows exactly what would be sent for a lecture in this subject (with a placeholder transcript). */
@Composable
private fun PromptPreviewDialog(edit: SubjectEdit, onDismiss: () -> Unit) {
    val style = edit.defaultStyle ?: NoteStyle.STANDARD
    val system = Prompts.systemPrompt(edit.subject)
    val user = Prompts.request(
        lectureTitle = "<lecture title>",
        subject = edit.name,
        style = style,
        transcript = "[0:00] <transcript lines…>\n[0:07] …",
        counts = Prompts.counts(style, 60 * 60_000L, whole = true),
        part = null,
        range = "",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prompt preview") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("System message", style = MaterialTheme.typography.labelLarge)
                Text(system, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                Spacer(Modifier.height(12.dp))
                Text("Request (for a 1-hour lecture, ${style.label} style)", style = MaterialTheme.typography.labelLarge)
                Text(user, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
