package com.lecturenotes.notes

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesLengthTest {
    @Test
    fun budgetsFollowStyleAndSource() {
        // The 80-minute lecture: 11,149 words of transcript.
        assertEquals(1114, NotesLength.budget(NoteStyle.BRIEF, 11_149, slides = false))
        assertEquals(2229, NotesLength.budget(NoteStyle.STANDARD, 11_149, slides = false))
        assertEquals(3902, NotesLength.budget(NoteStyle.DETAILED, 11_149, slides = false))
        // Slides are expanded rather than condensed, and tiny inputs get a floor.
        assertEquals(800, NotesLength.budget(NoteStyle.STANDARD, 800, slides = true))
        assertEquals(150, NotesLength.budget(NoteStyle.BRIEF, 100, slides = false))
    }

    private fun words(n: Int) = List(n) { "w" }.joinToString(" ")

    private fun bloated() = StudyNotes(
        title = "T", overview = words(20),
        sections = (1..5).map { NoteSection("Section $it", it * 60_000L, List(8) { words(15) }, List(3) { words(20) }) },
        keyTerms = (1..20).map { KeyTerm("term$it", words(15), null) },
        flashcards = (1..10).map { Flashcard("q$it", "a$it", null) },
        quiz = (1..5).map { QuizQuestion("q$it", listOf("a", "b"), "a", "", null) },
    )

    @Test
    fun withinToleranceIsLeftAlone() {
        val n = bloated()
        assertEquals(n, NotesLength.enforce(n, NotesLength.counted(n)))
    }

    @Test
    fun overshootIsTrimmedExplanationsFirstKeepingHeadingsCardsAndQuiz() {
        val n = bloated() // ~1,250 counted words
        val budget = 700
        val trimmed = NotesLength.enforce(n, budget)
        assertTrue(NotesLength.counted(trimmed) <= budget * NotesLength.TOLERANCE)
        assertEquals(n.sections.map { it.heading }, trimmed.sections.map { it.heading })
        assertEquals(n.sections.map { it.atMs }, trimmed.sections.map { it.atMs })
        assertTrue(trimmed.sections.all { it.explain.size <= 1 && it.bullets.size >= 2 })
        assertEquals(n.flashcards, trimmed.flashcards)
        assertEquals(n.quiz, trimmed.quiz)
    }

    private class Capture : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        override suspend fun listModels() = emptyList<ModelInfo>()
        override suspend fun complete(request: LlmRequest): LlmReply {
            requests += request
            if (NotesGenerator.estimateTokens(request.system + request.user) + request.maxTokens > 6_000) {
                throw LlmException.TooLarge("Limit 6000", 6_000)
            }
            if ("Below are the section headings" in request.user) return LlmReply("""{"title":"T","overview":"O"}""")
            return LlmReply(
                JSONObject().put("overview", "o").put("sections", JSONArray().put(JSONObject().put("heading", "h").put("bullets", JSONArray().put("b"))))
                    .put("keyTerms", JSONArray()).put("flashcards", JSONArray()).put("quiz", JSONArray()).toString(),
            )
        }
    }

    private class NoProgress : NotesProgress {
        override fun status(message: String, fraction: Float?) {}
        override suspend fun saved(run: PartialRun) {}
    }

    private fun lecture(budget: Int?) = NotesInput(
        "L", null, NoteStyle.STANDARD,
        (0 until 360).map { TranscriptLine(it * 10_000L, "word ".repeat(25).trim()) }, // 9,000 words over 60 min
        3_600_000, wordBudget = budget,
    )

    @Test
    fun eachPartGetsItsShareOfTheBudget() = runBlocking {
        val client = Capture()
        NotesGenerator(client, ProviderConfig(ProviderPreset.GROQ, "x", "k", "m", 128_000), NoProgress()).generate(lecture(1_800), null)
        val limits = client.requests.filter { "This is part" in it.user }
            .map { Regex("""at most about (\d+) words for this part""").find(it.user)!!.groupValues[1].toInt() }
        assertTrue(limits.size >= 3)
        assertTrue("shares add up to the budget, was ${limits.sum()}", limits.sum() in 1_700..1_850)
    }

    @Test
    fun noLimitSendsNoLengthInstruction() = runBlocking {
        val client = Capture()
        NotesGenerator(client, ProviderConfig(ProviderPreset.GROQ, "x", "k", "m", 128_000), NoProgress()).generate(lecture(null), null)
        assertTrue(client.requests.isNotEmpty())
        assertFalse(client.requests.any { "LENGTH LIMIT" in it.user })
    }
}
