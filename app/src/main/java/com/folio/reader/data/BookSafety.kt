package com.folio.reader.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

enum class BookFormat(val extension: String) { Epub("epub"), Pdf("pdf") }

/**
 * Checks files before they join the library. A book is a document, not an app, so the
 * risks are files that aren't what they claim to be and archives built to exhaust memory
 * or storage; scripts inside books are stopped by the reader itself (see ReaderContent).
 */
object BookSafety {
    /** Largest single file inside an EPUB once uncompressed. */
    const val MAX_ENTRY_BYTES = 200L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 20_000
    /** Real books compress text maybe 10:1; far beyond that is a "zip bomb". */
    private const val MAX_RATIO = 200

    /** What [file] really is, judged by its contents rather than its name; null if neither. */
    fun sniff(file: File): BookFormat? {
        val head = ByteArray(1024)
        val n = file.inputStream().use { it.read(head) }.coerceAtLeast(0)
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()) {
            return BookFormat.Epub
        }
        // The PDF header may follow a little junk, which readers accept.
        if (String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")) return BookFormat.Pdf
        return null
    }

    /** Throws if the EPUB archive at [file] is malformed or would unpack to something absurd. */
    fun checkEpub(file: File) {
        val zip = try {
            ZipFile(file)
        } catch (e: IOException) {
            throw IOException("This file is damaged or isn't really an EPUB.")
        }
        zip.use {
            var count = 0
            var total = 0L
            for (entry in it.entries()) {
                if (++count > MAX_ENTRIES) throw IOException("This EPUB has too many files inside to be a real book.")
                val size = entry.size
                if (size > MAX_ENTRY_BYTES) throw IOException("This EPUB contains a file too large to open safely.")
                if (size > 0) total += size
                if (total > MAX_TOTAL_BYTES) throw IOException("This EPUB unpacks to more than 2 GB, so it isn't a normal book.")
                val packed = entry.compressedSize
                if (size > 1024 * 1024 && packed > 0 && size / packed > MAX_RATIO) {
                    throw IOException("This EPUB is built to fill up memory (a \"zip bomb\"), so it wasn't added.")
                }
            }
        }
    }

    /** Copies at most [limit] bytes, failing rather than writing more (the size in a zip can lie). */
    fun copyLimited(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            written += n
            if (written > limit) throw IOException("A file inside is larger than it claims.")
            output.write(buffer, 0, n)
        }
    }

    fun readLimited(input: InputStream, limit: Long = MAX_ENTRY_BYTES): ByteArray =
        java.io.ByteArrayOutputStream().also { copyLimited(input, it, limit) }.toByteArray()
}
