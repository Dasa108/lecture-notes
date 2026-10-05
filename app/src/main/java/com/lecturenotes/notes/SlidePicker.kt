package com.lecturenotes.notes

import kotlin.math.ln

/**
 * Chooses which slides to show the AI alongside a stretch of transcript: slides that share the most
 * distinctive words with it (TF-IDF-style overlap), within a token budget, returned in slide order.
 */
object SlidePicker {
    fun pick(transcript: String, slides: List<TranscriptLine>, budgetTokens: Int): List<TranscriptLine> {
        if (slides.isEmpty() || budgetTokens <= 0) return emptyList()
        val slideWords = slides.map { words(it.text) }
        // Words on few slides are distinctive; words on every slide (course name, "agenda") are not.
        val df = HashMap<String, Int>()
        slideWords.forEach { ws -> ws.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = slides.size.toDouble()
        val spoken = words(transcript)
        val scored = slides.indices.map { i ->
            val score = slideWords[i].filter { it in spoken }.sumOf { ln(1 + n / (df[it] ?: 1)) }
            i to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }

        val chosen = mutableListOf<Int>()
        var used = 0
        for ((i, _) in scored) {
            val cost = NotesGenerator.estimateTokens(slides[i].text) + 6
            if (used + cost > budgetTokens) continue
            chosen += i
            used += cost
        }
        return chosen.sorted().map { slides[it] }
    }

    private fun words(text: String): Set<String> =
        Regex("[A-Za-z][A-Za-z0-9-]{3,}").findAll(text.lowercase()).map { it.value }.filter { it !in STOP }.toSet()

    private val STOP = setOf(
        "this", "that", "with", "from", "have", "what", "when", "where", "which", "will", "would", "there", "their",
        "they", "them", "then", "than", "into", "your", "about", "also", "some", "like", "just", "very", "more",
        "much", "here", "these", "those", "been", "were", "being", "does", "doing", "because", "only", "other",
        "each", "such", "over", "most", "many", "make", "made", "used", "using", "slide", "lecture", "speaker", "notes",
    )
}
