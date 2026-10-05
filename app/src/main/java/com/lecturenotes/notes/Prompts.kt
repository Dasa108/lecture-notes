package com.lecturenotes.notes

/** Prompts for turning a timestamped transcript into study notes. */
object Prompts {
    /** Base rules every subject starts from. Editable per subject via the advanced prompt. */
    val BASE_RULES = """
        You are an expert tutor writing study notes from a lecture transcript. The notes have two jobs:
        (1) capture everything the lecture taught, and (2) help the student genuinely understand it.

        Coverage: what the lecture said goes in "bullets"
        - Work through the lecture in order. Capture every concept, definition, claim, argument, example, number,
          comparison and step of reasoning that teaches something. Do not reduce the lecture to a summary.
        - Make one section per substantial topic, in the order it was taught, with the timestamp where the topic
          starts. A section usually spans 3-6 minutes of lecture; put smaller points inside it as bullets rather
          than starting a new section for each.
        - Do not write timestamps inside bullet text; the "at" field already records them.
        - Keep the lecturer's own examples, analogies and stories when they illustrate a point; exams often use them.
        - Add "(emphasised)" to a bullet only when the lecturer explicitly signals importance in words, e.g.
          "remember", "important", "note this", "this will come in the exam". Do not mark points just because
          they seem important to you. Expect only a few per lecture.
        - Ignore small talk, attendance, administrative chatter and repetition.

        Concise: comprehensive means every idea is captured, not that the notes are long
        - Write in compressed note style, not prose. Fragments are fine. Cut filler such as "The lecturer explains
          that", "It is important to note", "In this section".
        - One idea per bullet, usually under 20 words. Merge bullets that say the same thing.
        - Never repeat a point across bullets, sections, key terms, explanations and flashcards.

        Understanding: your additions go in "explain"
        - Where it genuinely helps (not in every section), add what a good tutor would add so the student understands:
          the intuition behind the idea, a simple analogy, why it matters, how it connects to other topics in the
          lecture, a short worked example, or a common misconception.
        - Each "explain" item is 1-2 short sentences. Add only well-established, standard knowledge you are
          confident is correct. If unsure, leave it out.
        - Never put your additions in "bullets" and never present them as something the lecturer said, so the
          student can always tell the lecture apart from your explanations.
        - If the lecturer says something that looks wrong or outdated, keep it in "bullets" as said and gently
          give the standard view in "explain".

        Accuracy and format
        - The transcript comes from automatic speech recognition and may contain misheard words. When the context
          makes the intended term obvious, use the correct term (e.g. "scaling loss" said about compute and tokens
          means "scaling laws").
        - If a word or acronym is garbled and the context does not make the intended term clear, write it as heard
          followed by "[unclear]". A nearby sentence often reveals the word (e.g. "each call is an ASM ... that
          agent has limited autonomy" means "an agent").
        - If the lecturer uses an acronym without saying what it stands for, keep the acronym exactly as said.
          Never add an expansion in brackets: a guessed expansion is worse than none.
        - When the lecturer refers to something only visible ("as you can see here", a diagram, the board or a
          slide), write "(shown on board/slide)" instead of guessing what it showed.
        - Write formulas in plain text, e.g. P(A|B) = P(B|A)·P(A) / P(B), x^2, sqrt(n), Σ. Never use LaTeX.
        - Key terms: only technical terms a student would need to define in an exam, each defined in under 25 words.
          Definitions may be fuller than the lecturer's wording but must agree with it.
        - Flashcards: one fact or idea per card, with a short answer (a sentence or a formula).
        - Quiz: mix recall with understanding and application (why does X happen, what would change if...,
          apply the idea to a new case). Make wrong options plausible. Answers must agree with the lecture.
          Write options without letter or number prefixes, and make "answer" exactly match one option.
    """.trimIndent()

    /** Always appended: the app can only read this format. Not user-editable. */
    const val JSON_RULE = "Reply with a single JSON object and nothing else."

    /** Starting templates for kinds of subject. */
    enum class SubjectType(val label: String, val template: String) {
        GENERAL("General", ""),
        THEORY(
            "Theory",
            "This is a theory subject. Emphasise concepts, definitions, classifications, comparisons (X vs Y), " +
                "advantages and disadvantages, and the reasoning behind ideas. Quiz questions can include " +
                "'which statement is true' and short explain-why questions.",
        ),
        MATHS(
            "Maths / numericals",
            "This subject is mathematical. Capture every formula with what each symbol means, its units and the " +
                "conditions under which it applies, and keep the steps of each worked example in order. Make flashcards " +
                "for formulas, and make several quiz questions small numerical problems with the worked solution in the explanation.",
        ),
        PROGRAMMING(
            "Programming",
            "This is a programming subject. Write algorithms as numbered steps, include short code or pseudocode when " +
                "code was dictated or explained, and note time/space complexity, edge cases and common mistakes. Quiz " +
                "questions can be predict-the-output, choose-the-right-approach and complexity questions.",
        ),
        LAB(
            "Lab / practical",
            "This is a lab or practical session. Capture the aim, tools or apparatus, the procedure as numbered steps, " +
                "observations, precautions and results. Include viva-style questions an examiner could ask.",
        ),
        ;

        companion object {
            fun byName(name: String?) = entries.firstOrNull { it.name == name } ?: GENERAL
        }
    }

    /** Guidance built from the base rules, the subject's template and the student's own instructions. */
    fun defaultGuidance(type: SubjectType, instructions: String?): String = buildString {
        append(BASE_RULES)
        if (type.template.isNotBlank()) append("\n\n").append(type.template)
        instructions?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("\n\nAbout this subject, from the student (follow these preferences):\n").append(it)
        }
    }

    /**
     * The system message for a subject: its full custom prompt if the student wrote one (advanced),
     * otherwise base rules + template + instructions. The JSON rule is always appended.
     */
    fun systemPrompt(subject: com.lecturenotes.data.Subject?): String {
        val guidance = subject?.customPrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultGuidance(SubjectType.byName(subject?.promptType), subject?.promptInstructions)
        return guidance + "\n\n" + JSON_RULE
    }

    private val SCHEMA = """
        {
          "title": "short descriptive title of the lecture",
          "overview": "2-4 sentences: what this lecture covered and why it matters",
          "sections": [ { "heading": "topic", "at": "mm:ss",
                          "bullets": ["what the lecture said", "..."],
                          "explain": ["your addition that deepens understanding", "..."] } ],
          "keyTerms": [ { "term": "...", "definition": "one or two sentences", "at": "mm:ss" } ],
          "flashcards": [ { "front": "question or prompt", "back": "concise answer", "at": "mm:ss" } ],
          "quiz": [ { "question": "...", "options": ["A", "B", "C", "D"], "answer": "exact text of the correct option",
                      "explanation": "why", "at": "mm:ss" } ]
        }
    """.trimIndent()

    private fun styleRules(style: NoteStyle) = when (style) {
        NoteStyle.BRIEF -> "Style: BRIEF. Only the essentials a student must remember: 2-4 short bullets per section, " +
            "fewer sections, and an \"explain\" item only where a point would otherwise be unclear."
        NoteStyle.STANDARD -> "Style: THOROUGH AND EXAM-FOCUSED. Cover every topic taught in 3-6 tight bullets per section " +
            "(definitions, formulas, arguments, method steps, examples). 0-2 \"explain\" items per section, only where they add understanding."
        NoteStyle.DETAILED -> "Style: COMPREHENSIVE. Capture every point, example and line of reasoning, still as tight bullets. " +
            "1-3 \"explain\" items per section, including a worked example or analogy where it helps."
    }

    data class Counts(val flashcards: Int, val quiz: Int, val keyTerms: Int)

    /** Scales flashcard and quiz counts with the length of the audio covered. */
    fun counts(style: NoteStyle, durationMs: Long, whole: Boolean): Counts {
        val tenMin = (durationMs / 600_000.0).coerceAtLeast(0.5)
        // Per 10 minutes of lecture: flashcards, quiz questions, key terms.
        val (f, q, k) = when (style) {
            NoteStyle.BRIEF -> Triple(2.0, 1.0, 2.0)
            NoteStyle.STANDARD -> Triple(4.0, 2.0, 4.0)
            NoteStyle.DETAILED -> Triple(6.0, 3.0, 6.0)
        }
        val min = if (whole) 3 else 1
        return Counts(
            (f * tenMin).toInt().coerceIn(min, 40),
            (q * tenMin).toInt().coerceIn(if (whole) 2 else 1, 20),
            (k * tenMin).toInt().coerceIn(min, 40),
        )
    }

    /** What the notes are made from. */
    enum class Source { TRANSCRIPT, SLIDES, BOTH }

    fun request(
        lectureTitle: String,
        subject: String?,
        style: NoteStyle,
        transcript: String,
        counts: Counts,
        part: Pair<Int, Int>?,
        range: String,
        source: Source = Source.TRANSCRIPT,
        /** For [Source.BOTH]: slide text relevant to this transcript, as reference. */
        slideContext: String? = null,
    ): String = buildString {
        appendLine("Lecture: $lectureTitle")
        if (subject != null) appendLine("Subject: $subject")
        when (source) {
            Source.SLIDES -> {
                appendLine("There is no recording of this lecture: the notes come from its slides/document only.")
                appendLine("Slides are terse. Turn fragments into clear notes and keep every point, but don't pad.")
            }
            Source.BOTH -> {
                appendLine("You have the lecture transcript (the main source) and text from the lecture's slides (reference).")
                appendLine("Use the slides for correct spelling of terms, formulas and diagram labels, and to fill in what the")
                appendLine("lecturer pointed at (\"as you can see here\"). Include substantive slide points the lecturer skipped.")
            }
            Source.TRANSCRIPT -> Unit
        }
        if (part != null) {
            val unit = if (source == Source.SLIDES) "slides" else "audio"
            appendLine("This is part ${part.first} of ${part.second} of the lecture ($unit $range). Write notes for this part only;")
            appendLine("the parts will be combined later. Keep \"title\" empty and make \"overview\" 1-2 sentences about this part.")
        }
        appendLine(styleRules(style))
        appendLine("Write at most ${counts.keyTerms} key terms, about ${counts.flashcards} flashcards and ${counts.quiz} quiz questions")
        appendLine("(quiz mostly multiple choice with 4 options;")
        appendLine("use an empty options list for a short-answer question). Flashcards and questions must test understanding of")
        appendLine("what was actually taught, not trivia about the lecture itself.")
        when (source) {
            Source.TRANSCRIPT -> appendLine("Every \"at\" value must be a timestamp copied from the transcript line where that idea is discussed.")
            Source.SLIDES -> appendLine("Every \"at\" value must be \"Slide N\", copied from the slide the idea comes from.")
            Source.BOTH -> appendLine(
                "Every \"at\" value must be a timestamp from the transcript where the idea is discussed, or \"Slide N\" " +
                    "for a point that appears only on the slides.",
            )
        }
        appendLine()
        appendLine("Return JSON in exactly this shape:")
        appendLine(SCHEMA)
        appendLine()
        if (source == Source.BOTH && !slideContext.isNullOrBlank()) {
            appendLine("Slides for reference (each starts with [Slide N]):")
            appendLine(slideContext)
            appendLine()
        }
        if (source == Source.SLIDES) {
            appendLine("Slides (each starts with [Slide N]):")
        } else {
            appendLine("Transcript (each line starts with its [mm:ss] timestamp):")
        }
        append(transcript)
    }

    fun overviewRequest(lectureTitle: String, partSummaries: String): String = """
        Lecture: $lectureTitle
        Below are the section headings and short summaries of each part of a lecture, in order.
        Return JSON: {"title": "short descriptive title of the whole lecture", "overview": "2-4 sentences summarising the whole lecture"}

        $partSummaries
    """.trimIndent()

    const val FIX_JSON = "\n\nYour previous reply was not valid JSON. Reply with only the JSON object, no other text."
}
