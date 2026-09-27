package com.financetracker.ai.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Raw text recovered from an uploaded statement, before any parsing. */
data class ScannedStatement(val text: String, val pageCount: Int = 1)

/**
 * Extracts text from a statement the user picked — an image, a PDF, or pasted text.
 *
 * Everything runs on-device via ML Kit's bundled Latin recogniser, so no statement ever leaves
 * the phone and the app works with no network. PDFs are rendered page-by-page through
 * [PdfRenderer] and recognised the same way as a photo.
 */
class StatementScanner(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun close() = recognizer.close()

    /** Recognises text already held in memory (a pasted statement). */
    suspend fun scanText(raw: String): ScannedStatement =
        ScannedStatement(raw, pageCount = 1)

    /** Recognises a still image the user chose, downscaling first to bound memory. */
    suspend fun scanImage(uri: Uri): ScannedStatement = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("Couldn't read that image.")
        }

        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            // Full-resolution statement photos are far larger than OCR needs, and are the
            // usual cause of an OutOfMemoryError during import.
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            })
        } ?: throw IllegalStateException("Couldn't open that image.")

        val text = recognize(bitmap)
        bitmap.recycle()
        ScannedStatement(text)
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_IMAGE_EDGE || height / sample > MAX_IMAGE_EDGE) {
            sample *= 2
        }
        return sample
    }

    /**
     * Renders each PDF page to a bitmap and recognises it. Page count is capped so a long
     * statement can't stall the UI or exhaust memory.
     */
    suspend fun scanPdf(uri: Uri, maxPages: Int = MAX_PDF_PAGES): ScannedStatement =
        withContext(Dispatchers.IO) {
            // PdfRenderer needs a seekable file descriptor, so the content URI is copied into
            // the cache first. The copy is deleted as soon as rendering finishes.
            val cacheFile = File.createTempFile("statement", ".pdf", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Couldn't open that PDF.")

                ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val pages = minOf(renderer.pageCount, maxPages)
                        val text = buildString {
                            for (i in 0 until pages) {
                                renderer.openPage(i).use { page ->
                                    val bitmap = Bitmap.createBitmap(
                                        page.width.coerceAtLeast(1),
                                        page.height.coerceAtLeast(1),
                                        Bitmap.Config.ARGB_8888
                                    )
                                    // A white backdrop; PDFs with transparency otherwise OCR as noise.
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(
                                        bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                    )
                                    appendLine(recognize(bitmap))
                                    bitmap.recycle()
                                }
                            }
                        }
                        ScannedStatement(text, pageCount = pages)
                    }
                }
            } finally {
                cacheFile.delete()
            }
        }

    private suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                cont.resume(result.text)
            }
            .addOnFailureListener { error ->
                cont.resumeWithException(error)
            }
    }

    private companion object {
        const val MAX_IMAGE_EDGE = 2048
        const val MAX_PDF_PAGES = 10
    }
}
