package com.lecturenotes.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lecturenotes.data.AttachmentKind
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.min

data class ExtractedPage(val number: Int, val text: String, val ocr: Boolean)

/**
 * Pulls text out of slides and documents, entirely on-device. Pages are delivered one at a time
 * through [onPage] so long PDFs show progress and partial results.
 */
object TextExtractor {

    fun kindFor(fileName: String, mime: String?): AttachmentKind? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            mime == "application/pdf" || ext == "pdf" -> AttachmentKind.PDF
            ext == "pptx" || mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> AttachmentKind.PPTX
            mime?.startsWith("image/") == true || ext in setOf("jpg", "jpeg", "png", "webp", "heic") -> AttachmentKind.IMAGE
            mime == "text/plain" || mime == "text/markdown" || ext in setOf("txt", "md") -> AttachmentKind.TEXT
            else -> null
        }
    }

    /** Extracts every page; [onPage] receives each page and the total page count. */
    fun extract(
        context: Context,
        kind: AttachmentKind,
        file: File,
        isCancelled: () -> Boolean,
        onPage: (ExtractedPage, total: Int) -> Unit,
    ) {
        when (kind) {
            AttachmentKind.PDF -> pdf(context, file, isCancelled, onPage)
            AttachmentKind.PPTX -> pptx(file, onPage)
            AttachmentKind.IMAGE -> onPage(ExtractedPage(1, ocr(InputImage.fromFilePath(context, Uri.fromFile(file))), ocr = true), 1)
            AttachmentKind.TEXT -> text(file, onPage)
        }
    }

    /** PDF: the text layer where there is one; pages without text (scans) are rendered and OCR'd. */
    private fun pdf(context: Context, file: File, isCancelled: () -> Boolean, onPage: (ExtractedPage, Int) -> Unit) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { doc ->
            val total = doc.numberOfPages
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (i in 0 until total) {
                        if (isCancelled()) throw InterruptedException("Cancelled")
                        stripper.startPage = i + 1
                        stripper.endPage = i + 1
                        val layer = runCatching { stripper.getText(doc) }.getOrDefault("").let(::clean)
                        val page = if (layer.length >= MIN_TEXT_CHARS) {
                            ExtractedPage(i + 1, layer, ocr = false)
                        } else {
                            val bitmap = render(renderer, i)
                            val text = try {
                                ocr(InputImage.fromBitmap(bitmap, 0))
                            } finally {
                                bitmap.recycle()
                            }
                            ExtractedPage(i + 1, text.ifBlank { layer }, ocr = text.isNotBlank())
                        }
                        onPage(page, total)
                    }
                }
            }
        }
    }

    /** Renders a PDF page to a bitmap, about 1600px wide: enough detail for OCR without huge memory use. */
    fun render(renderer: PdfRenderer, index: Int, targetWidth: Int = 1600): Bitmap {
        renderer.openPage(index).use { page ->
            val scale = targetWidth.toFloat() / page.width
            val w = targetWidth
            val h = min((page.height * scale).toInt(), 4000).coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE) // PDFs are transparent by default
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    /** PPTX: text runs of each slide in order, plus the slide's speaker notes. */
    private fun pptx(file: File, onPage: (ExtractedPage, Int) -> Unit) {
        ZipFile(file).use { zip ->
            val slideName = Regex("""ppt/slides/slide(\d+)\.xml""")
            val slides = zip.entries().asSequence()
                .mapNotNull { e -> slideName.matchEntire(e.name)?.groupValues?.get(1)?.toInt()?.let { it to e } }
                .sortedBy { it.first }
                .toList()
            slides.forEachIndexed { i, (n, entry) ->
                val text = zip.getInputStream(entry).use(::drawingText)
                val notes = zip.getEntry("ppt/notesSlides/notesSlide$n.xml")?.let { e -> zip.getInputStream(e).use(::drawingText) }
                    ?.lines()?.filterNot { it.trim().matches(Regex("\\d+")) }?.joinToString("\n")?.trim() // drop the slide-number placeholder
                val combined = if (notes.isNullOrBlank()) text else "$text\n\nSpeaker notes: $notes"
                onPage(ExtractedPage(i + 1, clean(combined), ocr = false), slides.size)
            }
        }
    }

    /** Concatenates DrawingML text runs (<a:t>), one line per paragraph (<a:p>). */
    private fun drawingText(input: InputStream): String {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, "UTF-8")
        val out = StringBuilder()
        val line = StringBuilder()
        var inText = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> if (parser.name == "t") inText = true
                XmlPullParser.TEXT -> if (inText) line.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "p" -> {
                        if (line.isNotBlank()) out.append(line.toString().trim()).append('\n')
                        line.clear()
                    }
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return out.toString().trim()
    }

    /** Plain text: split into ~250-word "pages" so notes can still point somewhere. */
    private fun text(file: File, onPage: (ExtractedPage, Int) -> Unit) {
        val words = file.readText().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val pages = words.chunked(250).map { it.joinToString(" ") }.ifEmpty { listOf("") }
        pages.forEachIndexed { i, t -> onPage(ExtractedPage(i + 1, t, ocr = false), pages.size) }
    }

    private fun ocr(image: InputImage): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            clean(Tasks.await(recognizer.process(image)).text)
        } finally {
            recognizer.close()
        }
    }

    /** Collapses runs of blank lines and trailing spaces. */
    private fun clean(text: String): String =
        text.lines().map { it.trimEnd() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    private const val MIN_TEXT_CHARS = 20
}
