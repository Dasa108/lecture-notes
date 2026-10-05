package com.lecturenotes.whisper

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

data class DeviceInfo(
    val totalRamBytes: Long,
    val soc: String,
    val cores: Int,
    val bigCores: Int,
    val is64Bit: Boolean,
    val freeStorageBytes: Long,
) {
    val totalRamGb: Double get() = totalRamBytes / 1_073_741_824.0

    /** Threads for whisper: the fast cores only, since mixing in little cores slows every step down. */
    val recommendedThreads: Int get() = bigCores.coerceIn(2, 8)

    companion object {
        fun read(context: Context): DeviceInfo {
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val soc = if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != Build.UNKNOWN) {
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
            } else {
                Build.HARDWARE
            }
            val cores = Runtime.getRuntime().availableProcessors()
            return DeviceInfo(
                totalRamBytes = mem.totalMem,
                soc = soc,
                cores = cores,
                bigCores = countBigCores(cores),
                is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
                freeStorageBytes = StatFs(context.filesDir.path).availableBytes,
            )
        }

        /** Counts cores whose max frequency is above the slowest cluster's. */
        private fun countBigCores(cores: Int): Int = countFast((0 until cores).toList()) ?: (cores / 2).coerceAtLeast(1)

        /**
         * Whisper threads for work running *now* on this thread: the fast cores among those the
         * OS lets us use. Oversubscribing (more threads than usable cores) stalls every step,
         * because ggml's threads wait for each other.
         */
        fun threadsForCurrentThread(): Int {
            val allowed = runCatching { Whisper.allowedCpus().toList() }.getOrDefault(emptyList())
            if (allowed.isEmpty()) return (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)
            val fast = countFast(allowed) ?: allowed.size
            return fast.coerceIn(2, 8).coerceAtMost(allowed.size)
        }

        private fun countFast(cpus: List<Int>): Int? {
            val freqs = cpus.map { i ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }.getOrNull() ?: return null
            }
            val slowest = freqs.min()
            val fast = freqs.count { it > slowest }
            return if (fast == 0) cpus.size else fast
        }
    }
}
