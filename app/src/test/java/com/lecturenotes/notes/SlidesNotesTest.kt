package com.lecturenotes.notes

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlidesNotesTest {
    private class Capture(private val requestLimit: Int? = null) : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        override suspend fun listModels() = emptyList<ModelInfo>()
        override suspend fun complete(request: LlmRequest): LlmReply {
            requests += request
            val size = NotesGenerator.estimateTokens(request.system + request.user) + request.maxTokens
            if (requestLimit != null && size > requestLimit) throw LlmException.TooLarge("Limit $requestLimit, Requested $size", requestLimit)
            if ("Below are the section headings" in request.user) return LlmReply("""{"title":"T","overview":"O"}""")
            // Cite the first slide in the request, as a model would.
            val firstSlide = Regex("""\[Slide (\d+)]""").find(request.user.substringAfterLast("Slides ("))?.groupValues?.get(1) ?: "1"
            return LlmReply(
                JSONObject().put("title", "T").put("overview", "O")
                    .put("sections", JSONArray().put(JSONObject().put("heading", "H$firstSlide").put("at", "Slide $firstSlide").put("bullets", JSONArray().put("b"))))
                    .put("keyTerms", JSONArray()).put("flashcards", JSONArray().put(JSONObject().put("front", "Q$firstSlide").put("back", "A").put("at", "slide $firstSlide")))
                    .put("quiz", JSONArray()).toString(),
            )
        }
    }

    private class NoProgress : NotesProgress {
        override fun status(message: String, fraction: Float?) {}
        override suspend fun saved(run: PartialRun) {}
    }

    private fun cfg(ctx: Int) = ProviderConfig(ProviderPreset.GROQ, "https://x", "k", "m", ctx)

    private fun slides(n: Int, words: Int = 40) = (1..n).map { i ->
        TranscriptLine(0, "Slide about topic$i " + "detail$i ".repeat(words), page = i)
    }

    @Test
    fun slidesOnlyNotesCiteSlides() = runBlocking {
        val client = Capture()
        val input = NotesInput("Deck", "aids", NoteStyle.STANDARD, slides(5), durationMs = 0)
        assertEquals(Prompts.Source.SLIDES, input.source)
        val notes = NotesGenerator(client, cfg(128_000), NoProgress()).generate(input, null)
        val user = client.requests.single().user
        assertTrue("explains there's no recording", "no recording" in user)
        assertTrue(user.contains("[Slide 3] Slide about topic3"))
        assertFalse("no transcript header", "Transcript (each line" in user)
        assertEquals(1, notes.sections.single().page)
        assertNull(notes.sections.single().atMs)
        assertEquals(1, notes.flashcards.single().page)
    }

    @Test
    fun bigDeckIsSplitBySlidesWithinLimit() = runBlocking {
        val limit = 6_000
        val client = Capture(requestLimit = limit)
        val input = NotesInput("Deck", null, NoteStyle.STANDARD, slides(80, words = 60), durationMs = 0)
        val notes = NotesGenerator(client, cfg(128_000), NoProgress()).generate(input, null)
        client.requests.drop(1).forEach { r ->
            assertTrue(NotesGenerator.estimateTokens(r.system + r.user) + r.maxTokens <= limit)
        }
        assertTrue(client.requests.count { "This is part" in it.user } >= 3)
        assertTrue(client.requests.any { "(slides 1–" in it.user })
        assertEquals(notes.sections.map { it.page }, notes.sections.map { it.page }.sortedBy { it })
    }

    @Test
    fun recordingWithSlidesSendsRelevantSlidesAsReference() = runBlocking {
        val client = Capture()
        val transcript = (0 until 30).map { TranscriptLine(it * 10_000L, "today we discuss backpropagation and gradient descent in neural networks") }
        val deck = listOf(
            TranscriptLine(0, "Course logistics and grading", page = 1),
            TranscriptLine(0, "Backpropagation: chain rule applied layer by layer", page = 2),
            TranscriptLine(0, "Gradient descent update rule w = w - lr * grad", page = 3),
            TranscriptLine(0, "Recommended textbooks", page = 4),
        )
        val input = NotesInput("L7", null, NoteStyle.STANDARD, transcript, 300_000, slides = deck)
        assertEquals(Prompts.Source.BOTH, input.source)
        NotesGenerator(client, cfg(128_000), NoProgress()).generate(input, null)
        val user = client.requests.single().user
        assertTrue("reference block present", "Slides for reference" in user)
        assertTrue("[Slide 2] Backpropagation" in user)
        assertTrue("[Slide 3] Gradient descent" in user)
        assertFalse("irrelevant slide left out", "Recommended textbooks" in user)
        assertTrue("transcript still the main source", "Transcript (each line starts with its [mm:ss]" in user)
    }

    @Test
    fun slidePickerRespectsBudgetAndOrder() {
        val deck = (1..20).map { TranscriptLine(0, "entropy " + "word$it ".repeat(50), page = it) }
        val picked = SlidePicker.pick("entropy word3 word7 word12", deck, budgetTokens = 300)
        assertTrue(picked.isNotEmpty())
        assertTrue(picked.sumOf { NotesGenerator.estimateTokens(it.text) + 6 } <= 300)
        assertEquals(picked.map { it.page }, picked.map { it.page }.sortedBy { it })
        assertTrue(picked.all { it.page in setOf(3, 7, 12) })
    }

    @Test
    fun parsesSlideReferences() {
        assertEquals(7, parsePage("Slide 7"))
        assertEquals(7, parsePage("[slide 7]"))
        assertEquals(12, parsePage("Page 12"))
        assertEquals(3, parsePage("p. 3"))
        assertNull(parsePage("12:40"))
        assertEquals("Slide 4", refLabel(null, 4))
        assertEquals("1:05", refLabel(65_000, 4))
    }
}
