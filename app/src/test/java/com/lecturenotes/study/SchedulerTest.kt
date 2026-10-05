package com.lecturenotes.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTest {
    private val day = 86_400_000L

    @Test
    fun newCardStepsThenGraduates() {
        val again = Scheduler.next(CardState(), Rating.AGAIN)
        assertEquals(60_000L, again.dueInMs)
        assertEquals(0, again.state.reps)

        val good = Scheduler.next(CardState(), Rating.GOOD)
        assertEquals(day, good.dueInMs)
        assertEquals(1, good.state.reps)

        val easy = Scheduler.next(CardState(), Rating.EASY)
        assertEquals(4 * day, easy.dueInMs)
    }

    @Test
    fun intervalsGrowWithGoodAnswers() {
        var s = CardState()
        val intervals = mutableListOf<Double>()
        repeat(5) {
            s = Scheduler.next(s, Rating.GOOD).state
            intervals += s.intervalDays
        }
        // 1, 2.5, 6.25, ... each step longer than the last
        assertEquals(1.0, intervals[0], 0.01)
        assertTrue(intervals.zipWithNext().all { (a, b) -> b > a })
        assertTrue(intervals.last() > 30)
    }

    @Test
    fun lapseResetsAndLowersEase() {
        var s = CardState()
        repeat(3) { s = Scheduler.next(s, Rating.GOOD).state }
        val lapse = Scheduler.next(s, Rating.AGAIN)
        assertEquals(0, lapse.state.reps)
        assertEquals(1, lapse.state.lapses)
        assertEquals(2.3, lapse.state.ease, 0.001)
        assertTrue(lapse.dueInMs < Scheduler.SAME_SESSION_MS) // shown again this session
    }

    @Test
    fun easeNeverDropsBelowFloorAndIntervalsAreCapped() {
        var s = CardState(reps = 3, intervalDays = 10.0)
        repeat(20) { s = Scheduler.next(s, Rating.HARD).state }
        assertEquals(1.3, s.ease, 0.001)
        var e = CardState(reps = 3, intervalDays = 200.0)
        e = Scheduler.next(e, Rating.EASY).state
        assertEquals(365.0, e.intervalDays, 0.001)
    }

    @Test
    fun labels() {
        assertEquals("1m", Scheduler.label(60_000))
        assertEquals("10m", Scheduler.label(600_000))
        assertEquals("1d", Scheduler.label(day))
        assertEquals("3w", Scheduler.label(21 * day))
        assertEquals("2mo", Scheduler.label(60 * day))
    }
}
