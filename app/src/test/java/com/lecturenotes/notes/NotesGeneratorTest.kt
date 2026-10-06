package com.lecturenotes.notes

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesGeneratorTest {

    /** Fake provider: records requests, enforces an optional per-request token limit, replays scripted behaviour. */
    private class FakeClient(
        private val requestLimit: Int? = null,
        private val script: MutableList<(LlmRequest) -> LlmReply> = mutableListOf(),
    ) : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        override suspend fun listModels() = emptyList<ModelInfo>()
        override suspend fun complete(request: LlmRequest): LlmReply {
            requests += request
            val size = NotesGenerator.estimateTokens(request.system + request.user) + request.maxTokens
            if (requestLimit != null && size > requestLimit) {
                throw LlmException.TooLarge("Request too large: Limit $requestLimit, Requested $size", requestLimit)
            }
            if (script.isNotEmpty()) return script.removeAt(0)(request)
            return LlmReply(notesFor(request))
        }

        /** Answers with one section whose timestamp is the first transcript line of the request. */
        fun notesFor(request: LlmRequest): String {
            if ("Below are the section headings" in request.user) {
                return """{"title":"Whole lecture","overview":"Overall summary."}"""
            }
            val firstTs = Regex("""\[(\d+:\d{2})]""").find(request.user.substringAfter("Transcript ("))?.groupValues?.get(1) ?: "0:00"
            return JSONObject()
                .put("title", "T").put("overview", "Part overview.")
                .put("sections", JSONArray().put(JSONObject().put("heading", "Topic at $firstTs").put("at", firstTs).put("bullets", JSONArray().put("point"))))
                .put("keyTerms", JSONArray().put(JSONObject().put("term", "Entropy").put("definition", "d").put("at", firstTs)))
                .put("flashcards", JSONArray().put(JSONObject().put("front", "Q $firstTs").put("back", "A").put("at", firstTs)))
                .put("quiz", JSONArray().put(JSONObject().put("question", "Q? $firstTs").put("options", JSONArray().put("a").put("b")).put("answer", "a").put("explanation", "e").put("at", firstTs)))
                .toString()
        }
    }

    private class RecordingProgress : NotesProgress {
        val saves = mutableListOf<PartialRun>()
        override fun status(message: String, fraction: Float?) {}
        override suspend fun saved(run: PartialRun) { saves += run }
    }

    private fun config(context: Int) = ProviderConfig(ProviderPreset.GROQ, "https://x", "k", "m", context)

    /** ~[minutes] of speech: one ~25-word line every 10 s. */
    private fun lecture(minutes: Int) = NotesInput(
        title = "Thermodynamics 7", subject = "Physics", style = NoteStyle.STANDARD,
        lines = (0 until minutes * 6).map { i ->
            TranscriptLine(i * 10_000L, "Sentence number $i about heat engines, entropy and the second law of thermodynamics in detail here.")
        },
        durationMs = minutes * 60_000L,
    )

    @Test
    fun shortLectureUsesOneRequest() = runBlocking {
        val client = FakeClient()
        val notes = NotesGenerator(client, config(128_000), RecordingProgress()).generate(lecture(10), null)
        assertEquals(1, client.requests.size)
        assertEquals("Topic at 0:00", notes.sections.single().heading)
        assertEquals(0L, notes.sections.single().atMs)
    }

    @Test
    fun tooLargeForRateLimitSplitsIntoPartsThatFit() = runBlocking {
        val limit = 6_000
        val client = FakeClient(requestLimit = limit)
        val progress = RecordingProgress()
        val input = lecture(60)
        val notes = NotesGenerator(client, config(128_000), progress).generate(input, null)

        val accepted = client.requests.drop(1) // first (whole-lecture) request was rejected
        accepted.forEach { r ->
            assertTrue("request fits limit", NotesGenerator.estimateTokens(r.system + r.user) + r.maxTokens <= limit)
        }
        val partCount = accepted.count { "This is part" in it.user }
        assertTrue("split into several parts", partCount >= 3)
        assertEquals(partCount, notes.sections.size)
        // Parts are in order and cover the whole lecture.
        val starts = notes.sections.map { it.atMs!! }
        assertEquals(starts.sorted(), starts)
        assertEquals(0L, starts.first())
        assertTrue(starts.last() > 40 * 60_000L)
        assertEquals("Whole lecture", notes.title)
        assertEquals(1, notes.keyTerms.size) // deduplicated across parts
        assertEquals(partCount, progress.saves.last().parts.size)
    }

    @Test
    fun longSubjectInstructionsStillFitTheLimit() = runBlocking {
        val limit = 6_000
        val client = FakeClient(requestLimit = limit)
        val instructions = "Explain everything in the context of my B.Tech AI course. ".repeat(40) // ~2,400 chars
        val input = lecture(60).copy(systemPrompt = Prompts.defaultGuidance(Prompts.SubjectType.THEORY, instructions) + "\n\n" + Prompts.JSON_RULE)
        NotesGenerator(client, config(128_000), RecordingProgress()).generate(input, null)
        client.requests.drop(1).forEach { r ->
            assertTrue("request fits limit", NotesGenerator.estimateTokens(r.system + r.user) + r.maxTokens <= limit)
        }
    }

    @Test
    fun resumesOnlyMissingParts() = runBlocking {
        val limit = 6_000
        // First run: fail on the 3rd part.
        var calls = 0
        val failing = object : LlmClient {
            val inner = FakeClient(requestLimit = limit)
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(request: LlmRequest): LlmReply {
                if ("This is part 3 " in request.user) throw java.io.IOException("offline")
                calls++
                return inner.complete(request)
            }
        }
        val progress = RecordingProgress()
        runCatching { NotesGenerator(failing, config(128_000), progress).generate(lecture(60), null) }
        val saved = progress.saves.last()
        assertEquals(setOf(0, 1), saved.parts.keys)

        // Second run resumes: parts 1-2 are not requested again.
        val client = FakeClient(requestLimit = limit)
        NotesGenerator(client, config(128_000), RecordingProgress()).generate(lecture(60), saved)
        assertTrue(client.requests.none { "This is part 1 " in it.user || "This is part 2 " in it.user })
        assertTrue(client.requests.any { "This is part 3 " in it.user })
    }

    @Test
    fun repairsInvalidJsonOnce() = runBlocking {
        val client = FakeClient(script = mutableListOf({ _ -> LlmReply("Sure! Here are your notes: {broken") }))
        val notes = NotesGenerator(client, config(128_000), RecordingProgress()).generate(lecture(5), null)
        assertEquals(2, client.requests.size)
        assertTrue(Prompts.FIX_JSON.trim() in client.requests[1].user)
        assertEquals(1, notes.sections.size)
    }

    @Test
    fun recoversWhenProviderRejectsJson() = runBlocking {
        // Groq-style: the first reply is rejected server-side but its raw text is repairable.
        val repairable = FakeClient(script = mutableListOf({ r -> throw LlmException.BadJson("Here you go: " + FakeClient().notesFor(r)) }))
        val a = NotesGenerator(repairable, config(128_000), RecordingProgress()).generate(lecture(5), null)
        assertEquals(1, repairable.requests.size)
        assertEquals(1, a.sections.size)

        // Rejected twice with nothing usable: the third try goes without JSON mode.
        val stubborn = FakeClient(script = mutableListOf({ _ -> throw LlmException.BadJson(null) }, { _ -> throw LlmException.BadJson("{broken") }))
        val b = NotesGenerator(stubborn, config(128_000), RecordingProgress()).generate(lecture(5), null)
        assertEquals(3, stubborn.requests.size)
        assertFalse(stubborn.requests[2].json)
        assertEquals(1, b.sections.size)
    }

    @Test
    fun waitsOutRateLimitThenSucceeds() = runBlocking {
        val client = FakeClient(script = mutableListOf({ _ -> throw LlmException.RateLimited(1_000) }))
        val start = System.currentTimeMillis()
        val notes = NotesGenerator(client, config(128_000), RecordingProgress()).generate(lecture(5), null)
        assertTrue(System.currentTimeMillis() - start >= 1_000)
        assertEquals(2, client.requests.size)
        assertEquals(1, notes.sections.size)
    }

    @Test
    fun joinsATopicSplitAcrossParts() {
        val merged = NotesGenerator.mergeContinuations(
            listOf(
                NoteSection("Scaling laws", 0, listOf("a"), listOf("x")),
                NoteSection("scaling laws ", 600_000, listOf("b")),
                NoteSection("Agents", 700_000, listOf("c")),
            ),
        )
        assertEquals(listOf("Scaling laws", "Agents"), merged.map { it.heading })
        assertEquals(listOf("a", "b"), merged[0].bullets)
        assertEquals(0L, merged[0].atMs)
    }

    @Test
    fun stripsTimestampsTheAiWritesIntoText() {
        assertEquals("Chinchilla scaling law relates compute and tokens.", stripInlineTimestamps("Chinchilla scaling law relates compute and tokens (3:05‑3:13)."))
        assertEquals("Each call is an agent.", stripInlineTimestamps("Each call is an agent (41:03)."))
        assertEquals("Ratio 3:1 matters", stripInlineTimestamps("Ratio 3:1 matters")) // not a timestamp in brackets
    }

    @Test
    fun rejectsTimestampsOutsideTheLecture() {
        assertEquals(754_000L, parseTimestamp("12:34", 3_600_000))
        assertEquals(3_723_000L, parseTimestamp("[1:02:03]", 4_000_000))
        assertEquals(null, parseTimestamp("59:00", 600_000))
        assertEquals(null, parseTimestamp("soon", 600_000))
    }
}
