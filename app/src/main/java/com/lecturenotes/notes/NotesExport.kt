package com.lecturenotes.notes

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/** Saving notes outside the app: Markdown, a printable PDF, and Anki-importable flashcards. */
object NotesExport {

    fun markdown(notes: StudyNotes, out: OutputStream) = out.write(notes.toMarkdown().toByteArray(Charsets.UTF_8))

    /**
     * Anki's text import format: tab-separated front/back with header lines that preselect the
     * separator, note type and deck, so File → Import works without choosing options.
     */
    fun anki(notes: StudyNotes, deck: String, out: OutputStream) {
        fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()
        val text = buildString {
            appendLine("#separator:tab")
            appendLine("#html:false")
            appendLine("#notetype:Basic")
            appendLine("#deck:${clean(deck)}")
            notes.flashcards.forEach { appendLine("${clean(it.front)}\t${clean(it.back)}") }
        }
        out.write(text.toByteArray(Charsets.UTF_8))
    }

    /** A4 PDF with headings, bullets, explanation boxes, terms, Q&A, flashcards and the quiz (answers last). */
    fun pdf(notes: StudyNotes, out: OutputStream) {
        val doc = PdfDocument()
        val w = 595
        val h = 842
        val margin = 48f
        val width = (w - 2 * margin).toInt()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun paint(size: Float, bold: Boolean = false, italic: Boolean = false, color: Int = Color.BLACK) = TextPaint().apply {
            isAntiAlias = true
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.SERIF, when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            })
        }

        fun newPage() {
            page?.let(doc::finishPage)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            y = margin
        }

        fun block(text: String, p: TextPaint, indent: Float = 0f, before: Float = 0f, after: Float = 4f) {
            if (text.isBlank()) return
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, (width - indent).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
            if (page == null || y + before + layout.height > h - margin) newPage() else y += before
            val canvas = page!!.canvas
            canvas.save()
            canvas.translate(margin + indent, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + after
        }

        val title = paint(20f, bold = true)
        val h1 = paint(14f, bold = true)
        val body = paint(10.5f)
        val small = paint(9.5f, italic = true, color = Color.DKGRAY)
        val label = paint(10.5f, bold = true)

        block(notes.title, title, after = 8f)
        block(notes.overview, body, after = 10f)
        for (s in notes.sections) {
            block(s.heading + (refLabel(s.atMs, s.page)?.let { "  ($it)" } ?: ""), h1, before = 8f)
            s.bullets.forEach { block("•  $it", body, indent = 8f, after = 2f) }
            s.explain.forEach { block("Going deeper (AI explanation, not from the lecture): $it", small, indent = 16f, before = 2f) }
        }
        if (notes.keyTerms.isNotEmpty()) {
            block("Key terms", h1, before = 12f)
            notes.keyTerms.forEach { block("${it.term}: ${it.definition}", body, indent = 8f, after = 3f) }
        }
        if (notes.classQA.isNotEmpty()) {
            block("Class Q&A", h1, before = 12f)
            notes.classQA.forEach {
                block("Q (${it.askedBy}): ${it.question}", label, indent = 8f, after = 1f)
                block("A: ${it.answer}", body, indent = 8f, after = 5f)
            }
        }
        if (notes.flashcards.isNotEmpty()) {
            block("Flashcards", h1, before = 12f)
            notes.flashcards.forEach {
                block("Q: ${it.front}", label, indent = 8f, after = 1f)
                block("A: ${it.back}", body, indent = 8f, after = 5f)
            }
        }
        if (notes.quiz.isNotEmpty()) {
            block("Practice quiz", h1, before = 12f)
            notes.quiz.forEachIndexed { i, q ->
                block("${i + 1}. ${q.question}", label, indent = 8f, after = 1f)
                q.options.forEachIndexed { j, o -> block("${'a' + j})  $o", body, indent = 20f, after = 1f) }
                if (q.options.isEmpty()) block("(short answer)", small, indent = 20f)
                y += 4f
            }
            block("Answers", h1, before = 12f)
            notes.quiz.forEachIndexed { i, q ->
                block("${i + 1}. ${q.answer}" + if (q.explanation.isNotBlank()) ": ${q.explanation}" else "", body, indent = 8f, after = 3f)
            }
        }
        page?.let(doc::finishPage)
        doc.writeTo(out)
        doc.close()
    }
}
