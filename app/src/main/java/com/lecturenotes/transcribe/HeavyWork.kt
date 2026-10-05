package com.lecturenotes.transcribe

import kotlinx.coroutines.sync.Mutex

/**
 * One CPU-heavy on-device job at a time (transcription, speaker labelling): running two at once
 * would split the phone's few fast cores and can exhaust RAM.
 */
object HeavyWork {
    val lock = Mutex()
}
