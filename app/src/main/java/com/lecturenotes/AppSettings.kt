package com.lecturenotes

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small app-wide preferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    // Off by default: labelling an 80-minute lecture takes ~13 minutes of full CPU and heats the phone,
    // and most lectures are one person talking.
    private val _autoSpeakers = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SPEAKERS, false))

    /** Label speakers automatically after transcribing recordings of 2+ minutes. */
    val autoSpeakersFlow: StateFlow<Boolean> = _autoSpeakers.asStateFlow()
    val autoSpeakers: Boolean get() = _autoSpeakers.value

    private val _compressAudio = MutableStateFlow(prefs.getBoolean(KEY_COMPRESS_AUDIO, true))

    /** Compress recordings to AAC after transcription (~115 MB/hour -> ~14 MB/hour). */
    val compressAudioFlow: StateFlow<Boolean> = _compressAudio.asStateFlow()
    val compressAudio: Boolean get() = _compressAudio.value

    fun setCompressAudio(on: Boolean) {
        prefs.edit { putBoolean(KEY_COMPRESS_AUDIO, on) }
        _compressAudio.value = on
    }

    fun setAutoSpeakers(on: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_SPEAKERS, on) }
        _autoSpeakers.value = on
    }

    private companion object {
        const val KEY_AUTO_SPEAKERS = "auto_speakers"
        const val KEY_COMPRESS_AUDIO = "compress_audio"
    }
}
