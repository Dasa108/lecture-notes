package com.lecturenotes.transcribe

import android.content.Context
import android.os.PowerManager

/**
 * Pauses heavy on-device work when the phone gets very hot. Long transcription and speaker
 * labelling runs drove a test phone to THERMAL_STATUS_SEVERE; continuing there only makes the
 * phone throttle harder and get hotter.
 */
object Thermal {
    const val PAUSED_NOTE = "Paused: letting the phone cool down"

    private fun status(context: Context): Int =
        context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE

    fun isHot(context: Context) = status(context) >= PowerManager.THERMAL_STATUS_SEVERE

    /**
     * Blocks while the phone is severely hot, until it has cooled to "light" (hysteresis, so work
     * doesn't flip on and off). [onPause]/[onResume] are called once each if a pause happens.
     */
    fun waitWhileHot(context: Context, isCancelled: () -> Boolean, onPause: () -> Unit, onResume: () -> Unit) {
        if (!isHot(context)) return
        onPause()
        while (!isCancelled() && status(context) > PowerManager.THERMAL_STATUS_LIGHT) Thread.sleep(15_000)
        onResume()
    }
}
