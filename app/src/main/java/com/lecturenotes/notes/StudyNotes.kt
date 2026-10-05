package com.lecturenotes.notes

import org.json.JSONArray
import org.json.JSONObject

enum class NoteStyle(val label: String, val description: String) {
    BRIEF("Brief", "Only the essentials. Quick revision."),
    STANDARD("Standard", "Exam-focused: definitions, formulas, key points."),
    DETAILED("Detailed", "Close to the full lecture, with explanations and examples."),
}

/**
 * Where a note comes from: [atMs] is a time in the recording, [page] a slide/page number.
 * Either can be null if the AI gave none or an invalid one.
 */
/** [bullets]: what the lecture said. [explain]: the AI's own additions to deepen understanding. */
data class NoteSection(
    val heading: String,
    val atMs: Long?,
    val bullets: List<String>,
    val explain: List<String> = emptyList(),
    val page: Int? = null,
)
data class KeyTerm(val term: String, val definition: String, val atMs: Long?, val page: Int? = null)
data class Flashcard(val front: String, val back: String, val atMs: Long?, val page: Int? = null)
data class QuizQuestion(
    val question: String,
    /** Empty for short-answer questions. */
    val options: List<String>,
    val answer: String,
    val explanation: String,
    val atMs: Long?,
    val page: Int? = null,
) {
    val isMultipleChoice: Boolean get() = options.size >= 2
}

data class StudyNotes(
    val title: String,
    val overview: String,
    val sections: List<NoteSection>,
    val keyTerms: List<KeyTerm>,
    val flashcards: List<Flashcard>,
    val quiz: List<QuizQuestion>,
) {
    fun toJson(): String = JSONObject().apply {
        put("title", title)
        put("overview", overview)
        put("sections", JSONArray(sections.map { s ->
            JSONObject().put("heading", s.heading).putTs(s.atMs, s.page).put("bullets", JSONArray(s.bullets)).put("explain", JSONArray(s.explain))
        }))
        put("keyTerms", JSONArray(keyTerms.map { JSONObject().put("term", it.term).put("definition", it.definition).putTs(it.atMs, it.page) }))
        put("flashcards", JSONArray(flashcards.map { JSONObject().put("front", it.front).put("back", it.back).putTs(it.atMs, it.page) }))
        put("quiz", JSONArray(quiz.map { q ->
            JSONObject().put("question", q.question).put("options", JSONArray(q.options)).put("answer", q.answer)
                .put("explanation", q.explanation).putTs(q.atMs, q.page)
        }))
    }.toString()

    /** Plain-text export for sharing or pasting into other apps. */
    fun toMarkdown(): String = buildString {
        appendLine("# $title")
        if (overview.isNotBlank()) appendLine().appendLine(overview)
        for (s in sections) {
            appendLine().appendLine("## ${s.heading}${refLabel(s.atMs, s.page)?.let { " ($it)" } ?: ""}")
            s.bullets.forEach { appendLine("- $it") }
            if (s.explain.isNotEmpty()) {
                appendLine().appendLine("*Going deeper (AI explanation, not from the lecture):*")
                s.explain.forEach { appendLine("> $it") }
            }
        }
        if (keyTerms.isNotEmpty()) {
            appendLine().appendLine("## Key terms")
            keyTerms.forEach { appendLine("- **${it.term}**: ${it.definition}") }
        }
        if (flashcards.isNotEmpty()) {
            appendLine().appendLine("## Flashcards")
            flashcards.forEach { appendLine("- Q: ${it.front}\n  A: ${it.back}") }
        }
        if (quiz.isNotEmpty()) {
            appendLine().appendLine("## Practice quiz")
            quiz.forEachIndexed { i, q ->
                appendLine("${i + 1}. ${q.question}")
                q.options.forEachIndexed { j, o -> appendLine("   ${'a' + j}) $o") }
                appendLine("   Answer: ${q.answer}")
            }
        }
    }

    companion object {
        /** Lenient parse of the AI's JSON (or our own stored JSON). [durationMs] bounds timestamps. */
        fun parse(json: JSONObject, durationMs: Long, fallbackTitle: String): StudyNotes {
            fun ts(o: JSONObject) = parseTimestamp(o.opt("at"), durationMs)
            fun pg(o: JSONObject) = parsePage(o.opt("at"))
            fun strings(a: JSONArray?) = a?.let { arr ->
                (0 until arr.length()).mapNotNull { stripInlineTimestamps(arr.optString(it)).ifBlank { null } }
            }.orEmpty()
            fun objects(key: String) = json.optJSONArray(key)?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }.orEmpty()
            return StudyNotes(
                title = json.optString("title").trim().ifBlank { fallbackTitle },
                overview = json.optString("overview").trim(),
                sections = objects("sections").mapNotNull { o ->
                    val heading = o.optString("heading").trim()
                    val bullets = strings(o.optJSONArray("bullets"))
                    val explain = strings(o.optJSONArray("explain"))
                    if (heading.isBlank() && bullets.isEmpty()) null else NoteSection(heading.ifBlank { "Notes" }, ts(o), bullets, explain, pg(o))
                },
                keyTerms = objects("keyTerms").mapNotNull { o ->
                    val term = o.optString("term").trim()
                    if (term.isBlank()) null else KeyTerm(term, stripInlineTimestamps(o.optString("definition")), ts(o), pg(o))
                },
                flashcards = objects("flashcards").mapNotNull { o ->
                    val front = o.optString("front").trim()
                    val back = o.optString("back").trim()
                    if (front.isBlank() || back.isBlank()) null else Flashcard(front, back, ts(o), pg(o))
                },
                quiz = objects("quiz").mapNotNull { o ->
                    val q = o.optString("question").trim()
                    val answer = o.optString("answer").trim()
                    if (q.isBlank() || answer.isBlank()) null
                    else QuizQuestion(q, strings(o.optJSONArray("options")), answer, o.optString("explanation").trim(), ts(o), pg(o))
                },
            )
        }
    }
}

/** Timestamps the AI sometimes writes into the text, e.g. "(3:05‑3:13)" or "(41:03)"; the app shows its own. */
private val INLINE_TIMESTAMP = Regex("""\s*\((?:at\s*)?\d{1,2}:\d{2}(?::\d{2})?(?:\s*[-–‑—]\s*\d{1,2}:\d{2}(?::\d{2})?)?\)""")

internal fun stripInlineTimestamps(text: String): String = text.replace(INLINE_TIMESTAMP, "").trim()

private fun JSONObject.putTs(ms: Long?, page: Int? = null): JSONObject = when {
    ms != null -> put("at", formatTimestamp(ms))
    page != null -> put("at", "Slide $page")
    else -> this
}

/** "12:40" for a time, "Slide 7" for a page, or null. */
fun refLabel(atMs: Long?, page: Int?): String? = atMs?.let(::formatTimestamp) ?: page?.let { "Slide $it" }

/** Accepts "Slide 7", "slide7", "Page 7", "p. 7", "[Slide 7]"; null for anything else (e.g. a timestamp). */
fun parsePage(value: Any?): Int? {
    val s = (value as? String)?.trim()?.trim('[', ']') ?: return null
    return Regex("""^(?:slide|page|p\.?)\s*(\d+)$""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toIntOrNull()
}

fun formatTimestamp(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Accepts "12:40", "1:02:03", "[12:40]" or seconds; rejects anything outside the lecture. */
fun parseTimestamp(value: Any?, durationMs: Long): Long? {
    val ms = when (value) {
        is Number -> (value.toDouble() * 1000).toLong()
        is String -> {
            val parts = value.trim().trim('[', ']').split(':').map { it.trim().toDoubleOrNull() ?: return null }
            when (parts.size) {
                1 -> (parts[0] * 1000).toLong()
                2 -> ((parts[0] * 60 + parts[1]) * 1000).toLong()
                3 -> ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000).toLong()
                else -> return null
            }
        }
        else -> return null
    }
    return ms.takeIf { it in 0..(durationMs + 5_000) }
}
