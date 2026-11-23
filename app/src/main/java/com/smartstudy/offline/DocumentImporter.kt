package com.smartstudy.offline

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

object DocumentImporter {

    fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = c.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    fun read(context: Context, uri: Uri, displayName: String): List<SourceChunk> {
        val mime = context.contentResolver.getType(uri).orEmpty()
        return when {
            mime.contains("pdf", true) || displayName.endsWith(".pdf", true) -> readPdf(context, uri, displayName)
            else -> readText(context, uri, displayName)
        }
    }

    private fun readText(context: Context, uri: Uri, name: String): List<SourceChunk> {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        require(text.isNotBlank()) { "الملف فارغ أو تعذر قراءة ترميزه." }
        return text.split(Regex("\n\s*\n|\r\n\s*\r\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { SourceChunk(name, null, it) }
    }

    private fun readPdf(context: Context, uri: Uri, name: String): List<SourceChunk> {
        PDFBoxResourceLoader.init(context)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("تعذر فتح ملف PDF")
        val doc = PDDocument.load(bytes)
        return try {
            val stripper = PDFTextStripper()
            (1..doc.numberOfPages).mapNotNull { page ->
                stripper.startPage = page
                stripper.endPage = page
                val pageText = stripper.getText(doc).trim()
                if (pageText.isBlank()) {
                    null
                } else {
                    // Split very long pages into overlapping readable sections while preserving page index
                    if (pageText.length <= 1800) {
                        listOf(SourceChunk(name, page, pageText))
                    } else {
                        pageText.chunked(1600).map { part -> SourceChunk(name, page, part.trim()) }
                    }
                }
            }.flatten()
        } finally {
            doc.close()
        }
    }
}
