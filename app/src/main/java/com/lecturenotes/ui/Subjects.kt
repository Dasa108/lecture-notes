package com.lecturenotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lecturenotes.data.Library
import com.lecturenotes.data.Subject
import com.lecturenotes.data.SubjectWithCount

/** Mid-tone colours that read on both light and dark surfaces. */
private val SubjectPalette = listOf(
    Color(0xFF4A7BD0) to "Blue",
    Color(0xFF1F9A8A) to "Teal",
    Color(0xFF5B9E3C) to "Green",
    Color(0xFFD29A1E) to "Amber",
    Color(0xFFE0703A) to "Orange",
    Color(0xFFD9534F) to "Red",
    Color(0xFFC75B9B) to "Pink",
    Color(0xFF8A66D1) to "Purple",
).also { check(it.size == Library.SUBJECT_COLOR_COUNT) }

fun subjectColor(index: Int): Color = SubjectPalette[index.mod(SubjectPalette.size)].first

@Composable
fun SubjectDot(colorIndex: Int, size: Dp = 10.dp) {
    Box(Modifier.size(size).background(subjectColor(colorIndex), CircleShape))
}

/** Create or edit a subject. [onDelete] is shown only when editing. */
@Composable
fun SubjectEditDialog(
    initial: Subject?,
    suggestedColor: Int,
    onDismiss: () -> Unit,
    onSave: (name: String, colorIndex: Int) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableIntStateOf(initial?.colorIndex ?: suggestedColor) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${initial?.name}”?") },
            text = { Text("Its lectures won't be deleted. They'll move to Unfiled.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete subject") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New subject" else "Edit subject") },
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Physics, DSA, Economics") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubjectPalette.forEachIndexed { i, (c, label) ->
                        val selected = i == color
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(c, CircleShape)
                                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = i }
                                .semantics { contentDescription = label; this.selected = selected },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                }
                if (onDelete != null) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete subject", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), color) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Bottom sheet for filing lectures: pick a subject, "No subject", or create a new one.
 * [onPick] receives null for "No subject".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectPickerSheet(
    title: String,
    subjects: List<SubjectWithCount>,
    /** The lectures' current subject; ignored when [hasCurrent] is false (e.g. a mixed selection). */
    currentId: Long?,
    hasCurrent: Boolean = true,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
    onCreate: (name: String, colorIndex: Int) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            items(subjects, key = { it.subject.id }) { s ->
                PickerRow(
                    leading = { SubjectDot(s.subject.colorIndex, 14.dp) },
                    label = s.subject.name,
                    trailing = "${s.count}",
                    selected = hasCurrent && s.subject.id == currentId,
                ) { onPick(s.subject.id) }
            }
            item {
                PickerRow(
                    leading = { Icon(Icons.Outlined.Inbox, null, Modifier.size(18.dp)) },
                    label = "No subject (Unfiled)",
                    selected = hasCurrent && currentId == null,
                ) { onPick(null) }
            }
            item {
                PickerRow(
                    leading = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                    label = "New subject…",
                ) { creating = true }
            }
        }
    }
    if (creating) {
        SubjectEditDialog(
            initial = null,
            suggestedColor = subjects.size % Library.SUBJECT_COLOR_COUNT,
            onDismiss = { creating = false },
            onSave = { name, color ->
                creating = false
                onCreate(name, color)
            },
        )
    }
}

@Composable
private fun PickerRow(
    leading: @Composable () -> Unit,
    label: String,
    trailing: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) { leading() }
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        if (selected) Icon(Icons.Filled.Check, "Current", tint = MaterialTheme.colorScheme.primary)
    }
}
