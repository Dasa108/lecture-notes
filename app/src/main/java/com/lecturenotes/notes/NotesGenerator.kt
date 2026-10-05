package com.lecturenotes.notes

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

data class TranscriptLine(val startMs: Long, val text: String)

data class NotesInput(
    val title: String,
    val subject: String?,
    val style: NoteStyle,
    val lines: List<TranscriptLine>,
    val durationMs: Long,
    /** System message: rules, subject guidance and the JSON rule (see [Prompts.systemPrompt]). */
    val systemPrompt: String = Prompts.systemPrompt(null),
)

/**
 * Resumable progress of a split (map) run: the per-part token budget it was planned with,
 * and the parts finished so far (as JSON), so an interrupted run only redoes what's missing.
 */
data class PartialRun(val budgetTokens: Int, val parts: Map<Int, String>) {
    fun toJson(): String = JSONObject().put("budget", budgetTokens)
        .put("parts", JSONObject().apply { parts.forEach { (i, p) -> put(i.toString(), p) } }).toString()

    companion object {
        fun parse(json: String?): PartialRun? = runCatching {
            val o = JSONObject(json ?: return null)
            val parts = o.getJSONObject("parts")
            PartialRun(o.getInt("budget"), parts.keys().asSequence().associate { it.toInt() to parts.getString(it) })
        }.getOrNull()
    }
}

interface NotesProgress {
    fun status(message: String, fraction: Float?)
    /** Called after each finished part so it can be persisted. */
    suspend fun saved(run: PartialRun)
}

/**
 * Turns a transcript into [StudyNotes]. Short lectures (or large-context models like Claude) get one
 * request; otherwise the transcript is split into parts sized to the model's context and the provider's
 * rate limit (learned from its errors), notes are written per part, and the parts are merged.
 */
class NotesGenerator(
    private val client: LlmClient,
    private val config: ProviderConfig,
    private val progress: NotesProgress,
) {
    /** Largest request (input + output tokens) the provider accepted/allows; learned from TooLarge errors. */
    private var requestLimit = config.contextWindow
    private var paceUntil = 0L
    /** Measured prompt size without the transcript; see [promptOverhead]. */
    private var overhead = 0

    suspend fun generate(input: NotesInput, resume: PartialRun?): StudyNotes {
        overhead = promptOverhead(input)
        val whole = render(input.lines)
        val single = singleOutputTokens(input.style)
        if (resume == null && estimateTokens(whole) + overhead + single <= requestLimit) {
            try {
                progress.status("Writing notes…", null)
                val counts = Prompts.counts(input.style, input.durationMs, whole = true)
                val json = callJson(input.systemPrompt, Prompts.request(input.title, input.subject, input.style, whole, counts, null, ""), single)
                return StudyNotes.parse(json, input.durationMs, input.title)
            } catch (e: LlmException.TooLarge) {
                learnLimit(e, estimateTokens(whole) + overhead + single)
            } catch (e: LlmException.Truncated) {
                // The whole lecture's notes didn't fit in one reply: split instead.
                requestLimit = minOf(requestLimit, estimateTokens(whole) + overhead + single - 1)
            }
        }
        return generateInParts(input, resume)
    }

    private suspend fun generateInParts(input: NotesInput, resume: PartialRun?): StudyNotes {
        if (overhead == 0) overhead = promptOverhead(input)
        var budget = resume?.budgetTokens ?: partBudget(input.style)
        val done = resume?.parts?.toMutableMap() ?: mutableMapOf()
        while (true) {
            val parts = split(input.lines, budget)
            try {
                for ((i, lines) in parts.withIndex()) {
                    if (i in done) continue
                    progress.status("Writing notes: part ${i + 1} of ${parts.size}…", i.toFloat() / (parts.size + 1))
                    done[i] = writePart(input, lines, i, parts.size, budget).toString()
                    progress.saved(PartialRun(budget, done))
                }
                return merge(input, parts.indices.map { JSONObject(done.getValue(it)) })
            } catch (e: LlmException.TooLarge) {
                learnLimit(e, budget + overhead + partOutputTokens(input.style))
                val newBudget = partBudget(input.style)
                if (newBudget >= budget) budget /= 2 else budget = newBudget
                if (budget < MIN_PART_TOKENS) throw IOException("This model's limits are too small for lecture notes. Try another model.")
                done.clear() // parts were planned with the old budget
                progress.saved(PartialRun(budget, done))
            }
        }
    }

    /** Notes for one part; if the reply gets cut off, the part is split in two. */
    private suspend fun writePart(input: NotesInput, lines: List<TranscriptLine>, index: Int, count: Int, budget: Int): JSONObject {
        val startMs = lines.first().startMs
        // Segments are rarely longer than ~15 s, so this is a close upper bound for the part's end.
        val endMs = (lines.last().startMs + 15_000).coerceAtMost(input.durationMs).coerceAtLeast(startMs)
        val counts = Prompts.counts(input.style, endMs - startMs, whole = false)
        val range = "${formatTimestamp(startMs)}–${formatTimestamp(endMs)}"
        return try {
            callJson(
                input.systemPrompt,
                Prompts.request(input.title, input.subject, input.style, render(lines), counts, index + 1 to count, range),
                partOutputTokens(input.style),
            )
        } catch (e: LlmException.Truncated) {
            if (lines.size < 4) throw e
            val half = lines.size / 2
            val a = writePart(input, lines.subList(0, half), index, count, budget)
            val b = writePart(input, lines.subList(half, lines.size), index, count, budget)
            concatParts(listOf(a, b))
        }
    }

    private suspend fun merge(input: NotesInput, parts: List<JSONObject>): StudyNotes {
        val merged = concatParts(parts)
        val parsed = StudyNotes.parse(merged, input.durationMs, input.title)
        val notes = parsed.copy(sections = mergeContinuations(parsed.sections))
        // Deduplicate terms and trim flashcards/quiz to the counts for the whole lecture, spread across parts.
        val counts = Prompts.counts(input.style, input.durationMs, whole = true)
        val terms = spread(notes.keyTerms.distinctBy { it.term.lowercase() }, counts.keyTerms * 3 / 2)
        val cards = spread(notes.flashcards.distinctBy { it.front.lowercase() }, counts.flashcards * 3 / 2)
        val quiz = spread(notes.quiz.distinctBy { it.question.lowercase() }, counts.quiz * 3 / 2)

        // A small final request for a title and overview of the whole lecture.
        progress.status("Finishing up…", parts.size.toFloat() / (parts.size + 1))
        val summaries = parts.mapIndexed { i, p ->
            val headings = p.optJSONArray("sections")?.let { a -> (0 until a.length()).joinToString("; ") { a.getJSONObject(it).optString("heading") } }
            "Part ${i + 1}: ${p.optString("overview")} (Topics: $headings)"
        }.joinToString("\n")
        val top = runCatching { callJson(input.systemPrompt, Prompts.overviewRequest(input.title, summaries), 400) }.getOrNull()
        return notes.copy(
            title = top?.optString("title")?.trim()?.ifBlank { null } ?: input.title,
            overview = top?.optString("overview")?.trim()?.ifBlank { null }
                ?: parts.joinToString(" ") { it.optString("overview") }.trim(),
            keyTerms = terms,
            flashcards = cards,
            quiz = quiz,
        )
    }

    /** Sends a request and parses JSON from the reply, retrying once if the JSON is broken. */
    private suspend fun callJson(system: String, user: String, maxTokens: Int): JSONObject {
        val reply = call(LlmRequest(system, user, maxTokens))
        return parseJson(reply.text) ?: parseJson(call(LlmRequest(system, user + Prompts.FIX_JSON, maxTokens)).text)
            ?: throw IOException("The AI didn't return usable notes. Try again or pick another model.")
    }

    /** Calls the model, waiting out rate limits and retrying transient failures. */
    private suspend fun call(request: LlmRequest): LlmReply {
        var transientFailures = 0
        var rateLimitWaits = 0
        while (true) {
            val wait = paceUntil - System.currentTimeMillis()
            if (wait > 0) {
                progress.status("Waiting for ${config.preset.label}'s rate limit (${(wait / 1000) + 1} s)…", null)
                delay(wait)
            }
            paceUntil = 0
            try {
                val reply = client.complete(request)
                // If the per-minute token budget is nearly used up, wait for it to refill before the next call.
                val remaining = reply.tokensRemaining
                val reset = reply.resetMs
                if (remaining != null && reset != null && remaining < estimateTokens(request.user) + request.maxTokens) {
                    paceUntil = System.currentTimeMillis() + reset + 500
                }
                return reply
            } catch (e: LlmException.RateLimited) {
                if (++rateLimitWaits > MAX_RATE_LIMIT_WAITS) throw IOException("${config.preset.label} kept rate-limiting. Try again later.")
                paceUntil = System.currentTimeMillis() + e.retryAfterMs.coerceIn(1_000, 120_000) + 500
            } catch (e: LlmException) {
                if (e is LlmException.Server && e.code >= 500 && ++transientFailures <= 3) {
                    delay(2_000L * transientFailures)
                    continue
                }
                throw e
            } catch (e: IOException) {
                if (++transientFailures > 3) throw IOException("Network problem: ${e.message}. Check your connection and try again.")
                delay(3_000L * transientFailures)
            }
        }
    }

    private fun learnLimit(e: LlmException.TooLarge, attempted: Int) {
        val limit = e.limitTokens ?: (attempted * 2 / 3)
        requestLimit = minOf(requestLimit, limit)
    }

    private fun partBudget(style: NoteStyle) =
        (requestLimit - overhead - partOutputTokens(style)).coerceIn(MIN_PART_TOKENS / 2, MAX_PART_TOKENS)

    /**
     * Tokens used by everything except the transcript: system prompt (including the subject's own
     * instructions, which vary in length) plus the request template, measured rather than guessed.
     */
    private fun promptOverhead(input: NotesInput): Int {
        val template = Prompts.request(
            input.title, input.subject, input.style, transcript = "",
            counts = Prompts.counts(input.style, input.durationMs, whole = true),
            part = 99 to 99, range = "0:00:00–0:00:00",
        )
        return estimateTokens(input.systemPrompt) + estimateTokens(template) + OVERHEAD_MARGIN
    }

    // Notes cover the lecture fully and add explanations, so replies are long.
    private fun singleOutputTokens(style: NoteStyle) = when (style) {
        NoteStyle.BRIEF -> 3_000
        NoteStyle.STANDARD -> 8_000
        NoteStyle.DETAILED -> 12_000
    }

    private fun partOutputTokens(style: NoteStyle) = when (style) {
        NoteStyle.BRIEF -> 1_500
        NoteStyle.STANDARD -> 3_000
        NoteStyle.DETAILED -> 4_000
    }

    companion object {
        /** Headroom for tokenizer differences and the JSON-repair reminder. */
        private const val OVERHEAD_MARGIN = 150
        private const val MIN_PART_TOKENS = 800
        /** Parts larger than ~10 min of speech give shallower notes, even if the model could take more. */
        private const val MAX_PART_TOKENS = 3_000
        private const val MAX_RATE_LIMIT_WAITS = 12

        /** ~4 characters per token for English; slightly pessimistic to leave headroom. */
        fun estimateTokens(text: String) = text.length * 2 / 7 + 1

        fun render(lines: List<TranscriptLine>) = lines.joinToString("\n") { "[${formatTimestamp(it.startMs)}] ${it.text}" }

        /** Splits lines into consecutive parts of at most [budget] estimated tokens. */
        fun split(lines: List<TranscriptLine>, budget: Int): List<List<TranscriptLine>> {
            val parts = mutableListOf<List<TranscriptLine>>()
            var current = mutableListOf<TranscriptLine>()
            var size = 0
            for (line in lines) {
                val t = estimateTokens(line.text) + 4
                if (current.isNotEmpty() && size + t > budget) {
                    parts += current
                    current = mutableListOf()
                    size = 0
                }
                current += line
                size += t
            }
            if (current.isNotEmpty()) parts += current
            return parts
        }

        fun parseJson(text: String): JSONObject? {
            val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try {
                JSONObject(cleaned.substring(start, end + 1))
            } catch (e: JSONException) {
                null
            }
        }

        private fun concatParts(parts: List<JSONObject>): JSONObject {
            val out = JSONObject()
            for (key in listOf("sections", "keyTerms", "flashcards", "quiz")) {
                val arr = JSONArray()
                parts.forEach { p -> p.optJSONArray(key)?.let { a -> for (i in 0 until a.length()) arr.put(a.get(i)) } }
                out.put(key, arr)
            }
            out.put("overview", parts.joinToString(" ") { it.optString("overview") }.trim())
            return out
        }

        /** A topic that runs across a part boundary comes back as two sections with the same heading; join them. */
        fun mergeContinuations(sections: List<NoteSection>): List<NoteSection> {
            val out = mutableListOf<NoteSection>()
            for (s in sections) {
                val prev = out.lastOrNull()
                if (prev != null && prev.heading.trim().equals(s.heading.trim(), ignoreCase = true)) {
                    out[out.lastIndex] = prev.copy(bullets = prev.bullets + s.bullets, explain = prev.explain + s.explain)
                } else {
                    out += s
                }
            }
            return out
        }

        /** Keeps at most [max] items, taken evenly across the list so every part of the lecture is covered. */
        private fun <T> spread(items: List<T>, max: Int): List<T> {
            if (items.size <= max || max <= 0) return items
            return (0 until max).map { items[it * items.size / max] }
        }
    }
}
