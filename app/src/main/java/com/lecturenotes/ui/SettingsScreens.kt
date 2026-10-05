package com.lecturenotes.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lecturenotes.app
import com.lecturenotes.notes.LlmClient
import com.lecturenotes.notes.LlmException
import com.lecturenotes.notes.ModelInfo
import com.lecturenotes.notes.ProviderConfig
import com.lecturenotes.notes.ProviderPreset
import com.lecturenotes.notes.ProviderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onSpeechModels: () -> Unit, onNotesAi: () -> Unit) {
    val context = LocalContext.current
    val ai by context.app.aiSettings.config.collectAsState()
    val selectedModel by context.app.models.selectedId.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsRow(
                Icons.Outlined.Memory, "Speech models",
                "On-device transcription · " + (com.lecturenotes.whisper.WhisperModels.byId(selectedModel)?.displayName ?: "none chosen"),
                onSpeechModels,
            )
            SettingsRow(
                Icons.Outlined.AutoAwesome, "Notes AI",
                if (ai.isReady) ai.displayName else "Not set up: needed to generate study notes",
                onNotesAi,
            )
        }
    }
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

data class NotesAiUiState(
    val preset: ProviderPreset,
    val baseUrl: String,
    val apiKey: String,
    val model: String?,
    val contextWindow: Int,
    val models: List<ModelInfo> = emptyList(),
    val freeOnly: Boolean = true,
    val testing: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val saved: Boolean = false,
)

class NotesAiViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.app.aiSettings
    private val _state = MutableStateFlow(stateFor(settings.config.value.preset))
    val state = _state.asStateFlow()

    private fun stateFor(preset: ProviderPreset): NotesAiUiState {
        val c = settings.configFor(preset)
        return NotesAiUiState(preset, c.baseUrl, c.apiKey.orEmpty(), c.model, c.contextWindow)
    }

    fun choose(preset: ProviderPreset) { _state.value = stateFor(preset) }
    fun setBaseUrl(v: String) = _state.update { it.copy(baseUrl = v, saved = false) }
    fun setKey(v: String) = _state.update { it.copy(apiKey = v.trim(), saved = false) }
    fun setFreeOnly(v: Boolean) = _state.update { it.copy(freeOnly = v) }

    fun pickModel(m: ModelInfo) = _state.update {
        it.copy(model = m.id, contextWindow = m.contextWindow ?: defaultContext(it.preset), saved = false)
    }

    /** Checks the key by listing the provider's models, and pre-selects a good default. */
    fun test() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(testing = true, message = null, error = false) }
        try {
            val models = LlmClient.create(config(s)).listModels().sortedWith(compareByDescending<ModelInfo> { it.free }.thenBy { it.id })
            if (models.isEmpty()) throw IllegalStateException("No chat models found.")
            val pick = models.firstOrNull { it.id == s.model } ?: recommend(models)
            _state.update {
                it.copy(
                    testing = false, models = models, model = pick.id,
                    contextWindow = pick.contextWindow ?: defaultContext(it.preset),
                    message = "Connected. ${models.size} models available.", error = false,
                )
            }
        } catch (e: Exception) {
            val msg = when (e) {
                is LlmException.Auth -> "That key was rejected. Check you copied all of it."
                is java.net.UnknownHostException -> "Can't reach ${s.preset.label}. Check your internet connection" +
                    (if (s.preset == ProviderPreset.OLLAMA) " and the computer's address." else ".")
                is java.net.ConnectException, is java.net.SocketTimeoutException ->
                    "Couldn't connect to ${s.baseUrl}." + (if (s.preset == ProviderPreset.OLLAMA) " Is Ollama running and set to accept network connections (OLLAMA_HOST=0.0.0.0)?" else "")
                else -> e.message ?: "Couldn't connect."
            }
            _state.update { it.copy(testing = false, message = msg, error = true) }
        }
    }

    fun save() {
        val s = _state.value
        settings.save(config(s))
        _state.update { it.copy(saved = true, message = "Saved. ${s.preset.label} will be used for notes.", error = false) }
    }

    private fun config(s: NotesAiUiState) = ProviderConfig(s.preset, s.baseUrl.trim().trimEnd('/'), s.apiKey.ifBlank { null }, s.model, s.contextWindow)

    private fun defaultContext(p: ProviderPreset) =
        if (p == ProviderPreset.CLAUDE) com.lecturenotes.notes.AnthropicClient.CLAUDE_CONTEXT else ProviderSettings.DEFAULT_CONTEXT

    /** A sensible default: strong general models first, favouring free ones. */
    private fun recommend(models: List<ModelInfo>): ModelInfo {
        val prefs = listOf("claude-sonnet", "gpt-oss-120b", "llama-3.3-70b", "qwen3", "llama-4", "deepseek", "gpt-oss-20b", "llama3", "qwen", "llama")
        for (p in prefs) {
            models.firstOrNull { it.free && p in it.id.lowercase() }?.let { return it }
            models.firstOrNull { p in it.id.lowercase() }?.let { return it }
        }
        return models.first()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesAiScreen(onBack: () -> Unit, vm: NotesAiViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    var showKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes AI") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    "Study notes are written by an AI service. Only the transcript text is sent, never your audio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ProviderPreset.entries, key = { it.id }) { p ->
                val selected = p == s.preset
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.fillMaxWidth().clickable { vm.choose(p) },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = { vm.choose(p) })
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.label, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (p.isFree) "FREE" else if (p == ProviderPreset.CLAUDE) "PAID" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (p.isFree) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                )
                            }
                            Text(p.blurb, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Set up ${s.preset.label}", style = MaterialTheme.typography.titleMedium)
            }
            if (s.preset == ProviderPreset.OLLAMA || s.preset == ProviderPreset.CUSTOM) item {
                OutlinedTextField(
                    s.baseUrl, vm::setBaseUrl,
                    label = { Text("Server address") },
                    placeholder = { Text("http://192.168.1.2:11434/v1") },
                    supportingText = {
                        if (s.preset == ProviderPreset.OLLAMA) Text("Your computer's Wi-Fi IP address, port 11434, ending in /v1.")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (s.preset.needsKey || s.preset == ProviderPreset.CUSTOM) item {
                OutlinedTextField(
                    s.apiKey, vm::setKey,
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "Hide key" else "Show key")
                        }
                    },
                    supportingText = { Text("Stored encrypted on this phone only.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                s.preset.keyUrl?.let { url ->
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
                        Text("Get a ${s.preset.label} key")
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = vm::test, enabled = !s.testing && s.baseUrl.isNotBlank()) {
                        Text(if (s.models.isEmpty()) "Test & load models" else "Reload models")
                    }
                    if (s.testing) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                s.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            if (s.models.isNotEmpty()) {
                val showFreeFilter = s.preset == ProviderPreset.OPENROUTER
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Model", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (showFreeFilter) FilterChip(selected = s.freeOnly, onClick = { vm.setFreeOnly(!s.freeOnly) }, label = { Text("Free only") })
                    }
                }
                val visible = s.models.filter { !showFreeFilter || !s.freeOnly || it.free || it.id == s.model }
                items(visible, key = { "m-" + it.id }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.pickModel(m) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m.id == s.model, onClick = { vm.pickModel(m) })
                        Column(Modifier.weight(1f)) {
                            Text(m.id, style = MaterialTheme.typography.bodyMedium)
                            val details = listOfNotNull(
                                m.contextWindow?.let { "${it / 1000}k context" },
                                if (m.free && s.preset == ProviderPreset.OPENROUTER) "free" else null,
                            ).joinToString(" · ")
                            if (details.isNotEmpty()) {
                                Text(details, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else if (s.model != null) item {
                Text("Model: ${s.model}", style = MaterialTheme.typography.bodyMedium)
            }

            if (s.preset.isFree && s.preset != ProviderPreset.OLLAMA) item {
                Text(
                    "Free tiers may keep or use what you send, per the provider's terms. " +
                        "For sensitive lectures, use Ollama on your own computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                Button(
                    onClick = vm::save,
                    enabled = !s.model.isNullOrBlank() && (!s.preset.needsKey || s.apiKey.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(if (s.saved) "Saved" else "Use ${s.preset.label} for notes") }
            }
        }
    }
}
