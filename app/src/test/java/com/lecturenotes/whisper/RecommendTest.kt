package com.lecturenotes.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendTest {
    private val gb = 1_073_741_824L
    private fun phone(ramGb: Double, freeGb: Double = 40.0) =
        DeviceInfo((ramGb * gb).toLong(), "test", 8, 6, true, (freeGb * gb).toLong())
    private fun model(id: String) = WhisperModels.byId(id)!!

    /** Tiny on the vivo I2405 (Snapdragon 8s Gen 4): ~0.6 s encode, ~1.4 s decode per 30 s. */
    private val fastPhone = BenchResult(encodeMs = 600.0, decodeMs = 1400.0, threads = 6, measuredAt = 0)
    private val slowPhone = BenchResult(encodeMs = 1200.0, decodeMs = 0.0, threads = 4, measuredAt = 0)

    @Test
    fun estimatesMatchWhatWeMeasuredOnTheTestPhone() {
        // Measured: Small ≈ 8.6 s per 30 s ≈ 17 min per hour with the app open.
        val small = fastPhone.minutesPerHour(model("small.en-q5_1"))
        assertTrue("small ≈ 15-18 min, was $small", small in 13.0..19.0)
        val tiny = fastPhone.minutesPerHour(model("tiny.en-q5_1"))
        assertEquals(4.0, tiny, 0.5)
        // Larger models are always slower.
        val mins = WhisperModels.all.map { fastPhone.minutesPerHour(it) }
        assertEquals(mins.sorted(), mins)
    }

    @Test
    fun fastPhoneGetsSmallNotMedium() {
        assertEquals("small.en-q5_1", WhisperModels.recommend(phone(7.5), fastPhone).id)
    }

    @Test
    fun slowPhoneGetsAFasterModel() {
        assertEquals("base.en-q5_1", WhisperModels.recommend(phone(7.5), slowPhone).id)
    }

    @Test
    fun memoryLimitsTheChoice() {
        // 3 GB phone: Small/Medium don't fit even if fast enough.
        assertEquals("base.en-q5_1", WhisperModels.recommend(phone(3.0), fastPhone).id)
        // Nearly-full storage: only Tiny fits.
        assertEquals("tiny.en-q5_1", WhisperModels.recommend(phone(8.0, freeGb = 0.1), fastPhone).id)
    }

    @Test
    fun withoutABenchmarkNeverRecommendsMedium() {
        assertEquals("small.en-q5_1", WhisperModels.recommend(phone(12.0), null).id)
    }

    @Test
    fun benchResultRoundTrips() {
        val r = BenchResult(612.5, 1388.0, 6, 123L)
        assertEquals(r, BenchResult.parse(r.toJson()))
    }
}
