package com.lecturenotes.notes

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountsTest {
    /** Always answers with 6 flashcards and 4 quiz questions per request, whatever was asked. */
    private class Generous(private val limit: Int? = null) : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        private var n = 0
        override suspend fun listModels() = emptyList<ModelInfo>()
        override suspend fun complete(request: LlmRequest): LlmReply {
            requests += request
            if (limit != null && NotesGenerator.estimateTokens(request.system + request.user) + request.maxTokens > limit) {
                throw LlmException.TooLarge("Limit $limit", limit)
            }
            if ("Below are the section headings" in request.user) return LlmReply("""{"title":"T","overview":"O"}""")
            val cards = JSONArray().apply { repeat(6) { put(JSONObject().put("front", "Q${n++}").put("back", "A")) } }
            val quiz = JSONArray().apply { repeat(4) { put(JSONObject().put("question", "Quiz${n++}?").put("options", JSONArray().put("a").put("b")).put("answer", "a")) } }
            return LlmReply(
                JSONObject().put("overview", "o").put("sections", JSONArray().put(JSONObject().put("heading", "h$n").put("bullets", JSONArray().put("b"))))
                    .put("keyTerms", JSONArray()).put("flashcards", cards).put("quiz", quiz).toString(),
            )
        }
    }

    private class NoProgress : NotesProgress {
        override fun status(message: String, fraction: Float?) {}
        override suspend fun saved(run: PartialRun) {}
    }

    private fun cfg() = ProviderConfig(ProviderPreset.GROQ, "x", "k", "m", 128_000)

    private fun lecture(minutes: Int, cards: Int?, quiz: Int?) = NotesInput(
        "L", null, NoteStyle.STANDARD,
        (0 until minutes * 6).map { TranscriptLine(it * 10_000L, "word ".repeat(25).trim()) },
        minutes * 60_000L, flashcardTarget = cards, quizTarget = quiz,
    )

    @Test
    fun splitLectureRespectsChosenCountsIncludingZero() = runBlocking {
        val client = Generous(limit = 6_000)
        val notes = NotesGenerator(client, cfg(), NoProgress()).generate(lecture(60, cards = 10, quiz = 0), null)
        val parts = client.requests.filter { "This is part" in it.user }
        assertTrue(parts.size >= 3)
        assertTrue("asks for no quiz", parts.all { "no quiz questions" in it.user })
        assertEquals(10, notes.flashcards.size) // model wrote 6 per part; capped to exactly 10, spread out
        assertTrue(notes.quiz.isEmpty())
        // Spread across the lecture, not just the first parts.
        assertTrue(notes.flashcards.last().front.removePrefix("Q").toInt() > notes.flashcards.first().front.removePrefix("Q").toInt() + 20)
    }

    @Test
    fun onePassCapsToChosenCounts() = runBlocking {
        val client = Generous()
        val notes = NotesGenerator(client, cfg(), NoProgress()).generate(lecture(5, cards = 3, quiz = 2), null)
        assertEquals(1, client.requests.size)
        assertTrue("about 3 flashcards" in client.requests.single().user)
        assertEquals(3, notes.flashcards.size)
        assertEquals(2, notes.quiz.size)
    }

    @Test
    fun autoKeepsTheOldBehaviour() = runBlocking {
        val client = Generous()
        val notes = NotesGenerator(client, cfg(), NoProgress()).generate(lecture(5, cards = null, quiz = null), null)
        // Auto counts for 5 min Standard: 3 cards (min), 2 quiz; single pass doesn't cap automatic counts.
        assertTrue("about 3 flashcards" in client.requests.single().user)
        assertEquals(6, notes.flashcards.size)
    }
}
