package com.lecturenotes.whisper

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class ModelRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _selectedId = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** Bumped whenever a model file appears or disappears so the UI can refresh. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun fileFor(model: WhisperModel) = File(modelsDir, model.fileName)
    fun partFileFor(model: WhisperModel) = File(modelsDir, model.fileName + ".part")

    fun isDownloaded(model: WhisperModel) = fileFor(model).exists()

    fun downloaded(): List<WhisperModel> = WhisperModels.all.filter(::isDownloaded)

    /** The model to transcribe with: the user's choice if downloaded, else the best downloaded one. */
    fun activeModel(): WhisperModel? =
        WhisperModels.byId(_selectedId.value)?.takeIf(::isDownloaded) ?: downloaded().lastOrNull()

    fun select(model: WhisperModel) {
        prefs.edit { putString(KEY_SELECTED, model.id) }
        _selectedId.value = model.id
    }

    fun delete(model: WhisperModel) {
        fileFor(model).delete()
        partFileFor(model).delete()
        notifyChanged()
    }

    private val _bench = MutableStateFlow(BenchResult.parse(prefs.getString(KEY_BENCH, null)))
    /** The last device speed check, if one has been run. */
    val bench: StateFlow<BenchResult?> = _bench.asStateFlow()

    fun saveBench(result: BenchResult) {
        prefs.edit { putString(KEY_BENCH, result.toJson()) }
        _bench.value = result
    }

    /** Whether the automatic first-visit speed test has already run (successfully or not). */
    var autoCheckDone: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CHECK, value) }

    fun notifyChanged() {
        _changes.value++
    }

    private companion object {
        const val KEY_SELECTED = "selected_model"
        const val KEY_BENCH = "bench"
        const val KEY_AUTO_CHECK = "auto_check_done"
    }
}
