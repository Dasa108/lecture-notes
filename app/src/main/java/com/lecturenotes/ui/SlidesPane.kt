package com.lecturenotes.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lecturenotes.data.Attachment
import com.lecturenotes.data.AttachmentKind
import com.lecturenotes.data.AttachmentStatus
import com.lecturenotes.data.SlidePage
import com.lecturenotes.docs.TextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** MIME types accepted when adding slides/documents. */
val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "image/*",
    "text/plain",
    "text/markdown",
)

/** The Slides tab: attached files, their pages (rendered where possible) and extracted text. */
fun LazyListScope.slidesPane(
    attachments: List<Attachment>,
    slides: List<SlidePage>,
    onAdd: () -> Unit,
    onRemove: (Attachment) -> Unit,
) {
    item {
        Column(Modifier.padding(vertical = 8.dp)) {
            if (attachments.isEmpty()) {
                Text(
                    "Attach the lecture's slides, a PDF, or photos of the board. They make the notes more accurate " +
                        "(correct terms, formulas, what was shown), and notes can be made from slides alone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
            FilledTonalButton(onClick = onAdd) {
                Icon(Icons.Outlined.NoteAdd, null)
                Spacer(Modifier.width(8.dp))
                Text("Add slides / PDF / photo")
            }
        }
    }
    items(attachments, key = { "att-${it.id}" }) { a -> AttachmentRow(a, onRemove) }
    items(slides.size, key = { "slide-${slides[it].page.id}" }) { i -> SlideCard(i + 1, slides[i]) }
}

@Composable
private fun AttachmentRow(a: Attachment, onRemove: (Attachment) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (a.status) {
                AttachmentStatus.PENDING, AttachmentStatus.EXTRACTING -> {
                    Text(
                        if (a.pageCount > 0) "Reading… ${(a.progress * a.pageCount).toInt()} of ${a.pageCount}" else "Reading…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(progress = { a.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                AttachmentStatus.DONE -> Text(
                    "${a.pageCount} ${if (a.kind == AttachmentKind.PPTX) "slides" else "pages"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AttachmentStatus.FAILED -> Text(a.error ?: "Couldn't read this file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        IconButton(onClick = { onRemove(a) }) { Icon(Icons.Outlined.Delete, "Remove ${a.fileName}") }
    }
}

@Composable
private fun SlideCard(number: Int, s: SlidePage) {
    var showText by rememberSaveable(s.page.id) { mutableStateOf(s.kind == AttachmentKind.PPTX || s.kind == AttachmentKind.TEXT) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Slide $number", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (s.page.ocr) {
                    Text("  · read from image", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (s.kind == AttachmentKind.PDF || s.kind == AttachmentKind.IMAGE) {
                    TextButton(onClick = { showText = !showText }) { Text(if (showText) "Hide text" else "Show text") }
                }
            }
            if (s.kind == AttachmentKind.PDF || s.kind == AttachmentKind.IMAGE) {
                PageImage(s)
            }
            if (showText) {
                Text(
                    s.page.text.ifBlank { "(no text found on this page)" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp).clickable { showText = false },
                )
            }
        }
    }
}

/** Renders a PDF page or loads an image off the main thread. */
@Composable
private fun PageImage(s: SlidePage) {
    val bitmap by produceState<Bitmap?>(null, s.path, s.page.number) {
        value = withContext(Dispatchers.IO) { runCatching { load(s) }.getOrNull() }
    }
    val b = bitmap
    if (b == null) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Gray.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
        )
    } else {
        Image(
            b.asImageBitmap(), contentDescription = "Slide ${s.page.number}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

private val pdfLock = Mutex()

private suspend fun load(s: SlidePage): Bitmap? = when (s.kind) {
    AttachmentKind.PDF -> pdfLock.withLock {
        ParcelFileDescriptor.open(File(s.path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> TextExtractor.render(r, s.page.number - 1, targetWidth = 1000) }
        }
    }
    AttachmentKind.IMAGE -> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(s.path, opts)
        var sample = 1
        while (opts.outWidth / sample > 1600) sample *= 2
        BitmapFactory.decodeFile(s.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    else -> null
}
