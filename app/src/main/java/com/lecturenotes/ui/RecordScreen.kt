package com.lecturenotes.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.lecturenotes.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.lecturenotes.audio.RecorderService
import com.lecturenotes.audio.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(subjectId: Long?, onBack: () -> Unit, onFinished: (Long) -> Unit) {
    val context = LocalContext.current
    val state by RecorderService.state.collectAsState()
    var denied by remember { mutableStateOf(false) }
    var lastId by remember { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val app = context.app

    // Save the user's name for this lecture as they type (the date stays as the title if left blank).
    LaunchedEffect(name, state.recordingId) {
        val id = state.recordingId ?: return@LaunchedEffect
        if (name.isBlank()) return@LaunchedEffect
        delay(400)
        app.db.recordings().rename(id, name.trim())
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) RecorderService.start(context, subjectId) else denied = true
    }

    LaunchedEffect(Unit) {
        if (!state.active) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) RecorderService.start(context, subjectId) else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Remember which recording this is so we can open it once the service has saved it.
    LaunchedEffect(state.recordingId) { state.recordingId?.let { lastId = it } }
    LaunchedEffect(state.active) {
        val id = lastId
        if (!state.active && id != null) onFinished(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (denied) {
                Text(
                    "Microphone permission is needed to record. You can still import audio files from the home screen.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
                return@Column
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Lecture name (optional)") },
                placeholder = { Text("e.g. Thermodynamics, lecture 7") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))
            Icon(
                Icons.Filled.Mic, null, Modifier.size(56.dp),
                tint = if (state.active && !state.paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                formatDuration(state.elapsedMs),
                fontSize = 56.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                when {
                    !state.active -> "Starting…"
                    state.paused -> "Paused"
                    else -> "Recording. You can lock your phone."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            val level by animateFloatAsState(state.level, label = "level")
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth(0.7f).height(8.dp))
            Spacer(Modifier.height(48.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton(
                    onClick = { if (state.paused) RecorderService.resume(context) else RecorderService.pause(context) },
                    enabled = state.active,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (state.paused) "Resume" else "Pause",
                    )
                }
                FilledIconButton(
                    onClick = {
                        // Save the name right away in case the debounce above hasn't fired yet.
                        val id = state.recordingId
                        if (id != null && name.isNotBlank()) app.appScope.launch { app.db.recordings().rename(id, name.trim()) }
                        RecorderService.stop(context)
                    },
                    enabled = state.active,
                    modifier = Modifier.size(80.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop and save", modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}
