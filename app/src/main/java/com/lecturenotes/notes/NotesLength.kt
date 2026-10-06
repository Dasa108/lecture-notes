package com.lecturenotes.notes

/**
 * Length budgets for notes and the safety net that enforces them. Budgets cover the reading part
 * of the notes (overview, bullets, explanations, key terms, Class Q&A); flashcards and the quiz
 * are practice material and don't count.
 */
object NotesLength {
    /** Share of the lecture's words. Slides are terse, so notes expand them instead of condensing. */
    private fun share(style: NoteStyle, slides: Boolean) = when (style) {
        NoteStyle.BRIEF -> if (slides) 0.5 else 0.10
        NoteStyle.STANDARD -> if (slides) 1.0 else 0.20
        NoteStyle.DETAILED -> if (slides) 1.5 else 0.35
    }

    private const val MIN_WORDS = 150

    /** The default budget for a lecture with [sourceWords] words of transcript (or slide text). */
    fun budget(style: NoteStyle, sourceWords: Int, slides: Boolean): Int =
        (sourceWords * share(style, slides)).toInt().coerceAtLeast(MIN_WORDS)

    fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Words in the part of the notes the budget covers. */
    fun counted(n: StudyNotes): Int =
        words(n.overview) +
            n.sections.sumOf { s -> words(s.heading) + s.bullets.sumOf(::words) + s.explain.sumOf(::words) } +
            n.keyTerms.sumOf { words(it.term) + words(it.definition) } +
            n.classQA.sumOf { words(it.question) + words(it.answer) }

    /** Overshoot tolerated before trimming: the model can't count exactly. */
    const val TOLERANCE = 1.25

    /**
     * Safety net when the model overshoots by more than [TOLERANCE]: drop extra explanation boxes
     * (keep one per section), then the last bullets of the longest sections, then surplus key
     * terms, until the notes fit. Headings, timestamps, flashcards and the quiz are untouched.
     */
    fun enforce(n: StudyNotes, budget: Int): StudyNotes {
        val limit = (budget * TOLERANCE).toInt()
        var notes = n
        if (counted(notes) <= limit) return notes

        notes = notes.copy(sections = notes.sections.map { it.copy(explain = it.explain.take(1)) })
        if (counted(notes) <= limit) return notes

        // Trim bullets from whichever section is currently longest, keeping at least 2 per section.
        val sections = notes.sections.map { it.copy(bullets = it.bullets.toMutableList()) }.toMutableList()
        while (counted(notes.copy(sections = sections)) > limit) {
            val i = sections.indices.filter { sections[it].bullets.size > 2 }
                .maxByOrNull { sections[it].bullets.sumOf(::words) } ?: break
            sections[i] = sections[i].copy(bullets = sections[i].bullets.dropLast(1))
        }
        notes = notes.copy(sections = sections)

        var terms = notes.keyTerms
        while (counted(notes.copy(keyTerms = terms)) > limit && terms.size > 3) terms = terms.dropLast(1)
        notes = notes.copy(keyTerms = terms)

        // Last resort: drop the remaining explanations.
        if (counted(notes) > limit) notes = notes.copy(sections = notes.sections.map { it.copy(explain = emptyList()) })
        return notes
    }
}
