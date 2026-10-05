package com.lecturenotes.study

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class Rating { AGAIN, HARD, GOOD, EASY }

/** A card's spaced-repetition state. [reps] = successful reviews in a row; 0 means new or relearning. */
data class CardState(
    val ease: Double = Scheduler.START_EASE,
    val intervalDays: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
)

data class Outcome(val state: CardState, val dueInMs: Long)

/**
 * SM-2-style scheduling (the algorithm behind Anki), simplified:
 * new/relearning cards step through minutes, then review intervals grow by the card's ease.
 */
object Scheduler {
    const val START_EASE = 2.5
    private const val MIN_EASE = 1.3
    private const val MAX_INTERVAL_DAYS = 365.0
    private const val MINUTE = 60_000L
    private const val DAY = 86_400_000L

    /** Cards due again within this window are shown again in the same session. */
    const val SAME_SESSION_MS = 20 * MINUTE

    fun next(s: CardState, rating: Rating): Outcome {
        if (s.reps == 0) {
            return when (rating) {
                Rating.AGAIN -> Outcome(s.copy(intervalDays = 0.0), MINUTE)
                Rating.HARD -> Outcome(s.copy(intervalDays = 0.0), 6 * MINUTE)
                Rating.GOOD -> review(s.copy(reps = 1), 1.0)
                Rating.EASY -> review(s.copy(reps = 1, ease = s.ease + 0.15), 4.0)
            }
        }
        return when (rating) {
            Rating.AGAIN -> Outcome(
                s.copy(reps = 0, lapses = s.lapses + 1, ease = max(MIN_EASE, s.ease - 0.2), intervalDays = 0.0),
                10 * MINUTE,
            )
            Rating.HARD -> {
                val ease = max(MIN_EASE, s.ease - 0.15)
                review(s.copy(reps = s.reps + 1, ease = ease), max(s.intervalDays + 1, s.intervalDays * 1.2))
            }
            Rating.GOOD -> review(s.copy(reps = s.reps + 1), max(s.intervalDays + 1, s.intervalDays * s.ease))
            Rating.EASY -> {
                val ease = s.ease + 0.15
                review(s.copy(reps = s.reps + 1, ease = ease), max(s.intervalDays + 1, s.intervalDays * ease * 1.3))
            }
        }
    }

    private fun review(s: CardState, days: Double): Outcome {
        val d = min(MAX_INTERVAL_DAYS, days)
        return Outcome(s.copy(intervalDays = d), (d * DAY).roundToLong())
    }

    /** Short label for a delay, e.g. "1m", "10m", "1d", "3w", "2mo". */
    fun label(ms: Long): String {
        val minutes = ms / MINUTE
        val days = ms.toDouble() / DAY
        return when {
            minutes < 60 -> "${max(1, minutes)}m"
            days < 1 -> "${minutes / 60}h"
            days < 14 -> "${days.roundToLong()}d"
            days < 60 -> "${(days / 7).roundToLong()}w"
            days < 365 -> "${(days / 30).roundToLong()}mo"
            else -> "${"%.1f".format(days / 365)}y"
        }
    }
}
