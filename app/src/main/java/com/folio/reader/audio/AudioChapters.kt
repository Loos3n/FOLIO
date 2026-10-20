package com.folio.reader.audio

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/** A chapter marked inside one audio file: its title and where it starts, in milliseconds. */
data class FileChapter(val title: String, val startMs: Long)

/**
 * Reads the chapter marks audiobook files carry: Nero (`chpl`) and QuickTime chapter tracks
 * in M4B/M4A/MP4 files, and ID3v2 `CHAP` frames in MP3s. Media players don't expose these,
 * so they're read straight from the file. Returns an empty list when there are none.
 */
object AudioChapters {

    fun read(channel: FileChannel): List<FileChapter> = runCatching {
        val head = channel.readAt(0, 12)
        val chapters = when {
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> Mp4(channel).chapters()
            head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> id3(channel)
            else -> emptyList()
        }
        chapters
            .filter { it.startMs >= 0 }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
    }.getOrDefault(emptyList())

    // ---- MP4 / M4B

    private class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
        val body get() = start + headerSize
        val end get() = start + size
    }

    private class Track(
        var id: Int = 0,
        var timescale: Long = 0,
        var handler: String = "",
        var chapterRefs: List<Int> = emptyList(),
        var stts: List<Pair<Long, Long>> = emptyList(), // (sample count, duration)
        var sampleSizes: List<Long> = emptyList(),
        var chunkOffsets: List<Long> = emptyList(),
        var stsc: List<Triple<Long, Long, Long>> = emptyList(), // (first chunk, samples per chunk, description)
    )

    private class Mp4(private val ch: FileChannel) {
        private val size = ch.size()

        fun chapters(): List<FileChapter> {
            val moov = children(0, size).firstOrNull { it.type == "moov" } ?: return emptyList()
            val inMoov = children(moov.body, moov.end)
            // Nero chapters, written by most audiobook tools.
            inMoov.firstOrNull { it.type == "udta" }?.let { udta ->
                children(udta.body, udta.end).firstOrNull { it.type == "chpl" }?.let { chpl ->
                    val list = chpl(chpl)
                    if (list.isNotEmpty()) return list
                }
            }
            // QuickTime chapters: a text track that another track points at with a 'chap' reference.
            val tracks = inMoov.filter { it.type == "trak" }.map { track(it) }
            val refs = tracks.flatMap { it.chapterRefs }.toSet()
            val text = tracks.firstOrNull { it.id in refs } ?: return emptyList()
            return quickTime(text)
        }

        private fun chpl(box: Box): List<FileChapter> {
            val b = ByteBuffer.wrap(ch.readAt(box.body, (box.end - box.body).coerceAtMost(1 shl 20).toInt()))
            val version = b.get().toInt()
            b.position(4)
            if (version != 0) b.int // reserved
            val count = b.get().toInt() and 0xFF
            val out = ArrayList<FileChapter>()
            repeat(count) {
                if (b.remaining() < 9) return out
                val start = b.long // 100-nanosecond units
                val len = b.get().toInt() and 0xFF
                if (b.remaining() < len) return out
                val bytes = ByteArray(len).also { b.get(it) }
                out += FileChapter(String(bytes, Charsets.UTF_8).trim(), start / 10_000)
            }
            return out
        }

        private fun track(trak: Box): Track {
            val t = Track()
            for (c in children(trak.body, trak.end)) when (c.type) {
                "tkhd" -> {
                    val b = ByteBuffer.wrap(ch.readAt(c.body, 32))
                    val v = b.get().toInt()
                    b.position(if (v == 1) 4 + 16 else 4 + 8)
                    t.id = b.int
                }
                "tref" -> children(c.body, c.end).firstOrNull { it.type == "chap" }?.let { chap ->
                    val b = ByteBuffer.wrap(ch.readAt(chap.body, (chap.end - chap.body).toInt().coerceAtMost(256)))
                    t.chapterRefs = List(b.remaining() / 4) { b.int }
                }
                "mdia" -> for (m in children(c.body, c.end)) when (m.type) {
                    "mdhd" -> {
                        val b = ByteBuffer.wrap(ch.readAt(m.body, 32))
                        val v = b.get().toInt()
                        b.position(if (v == 1) 4 + 16 else 4 + 8)
                        t.timescale = b.int.toLong() and 0xFFFFFFFFL
                    }
                    "hdlr" -> t.handler = String(ch.readAt(m.body + 8, 4), Charsets.ISO_8859_1)
                    "minf" -> children(m.body, m.end).firstOrNull { it.type == "stbl" }?.let { stbl -> sampleTable(stbl, t) }
                }
            }
            return t
        }

        private fun sampleTable(stbl: Box, t: Track) {
            for (s in children(stbl.body, stbl.end)) {
                val len = (s.end - s.body).coerceAtMost(4L shl 20).toInt()
                when (s.type) {
                    "stts" -> {
                        val b = ByteBuffer.wrap(ch.readAt(s.body, len)).apply { position(4) }
                        val n = b.int
                        t.stts = List(minOf(n, b.remaining() / 8)) { u32(b.int) to u32(b.int) }
                    }
                    "stsz" -> {
                        val b = ByteBuffer.wrap(ch.readAt(s.body, len)).apply { position(4) }
                        val fixed = u32(b.int)
                        val n = b.int
                        t.sampleSizes = if (fixed != 0L) List(n.coerceAtMost(100_000)) { fixed } else List(minOf(n, b.remaining() / 4)) { u32(b.int) }
                    }
                    "stco" -> {
                        val b = ByteBuffer.wrap(ch.readAt(s.body, len)).apply { position(4) }
                        val n = b.int
                        t.chunkOffsets = List(minOf(n, b.remaining() / 4)) { u32(b.int) }
                    }
                    "co64" -> {
                        val b = ByteBuffer.wrap(ch.readAt(s.body, len)).apply { position(4) }
                        val n = b.int
                        t.chunkOffsets = List(minOf(n, b.remaining() / 8)) { b.long }
                    }
                    "stsc" -> {
                        val b = ByteBuffer.wrap(ch.readAt(s.body, len)).apply { position(4) }
                        val n = b.int
                        t.stsc = List(minOf(n, b.remaining() / 12)) { Triple(u32(b.int), u32(b.int), u32(b.int)) }
                    }
                }
            }
        }

        private fun quickTime(t: Track): List<FileChapter> {
            if (t.timescale <= 0 || t.sampleSizes.isEmpty() || t.chunkOffsets.isEmpty()) return emptyList()
            // Where each sample starts in the file, from the chunk table.
            val offsets = ArrayList<Long>()
            var sample = 0
            for ((chunkIndex, chunkStart) in t.chunkOffsets.withIndex()) {
                val chunk = chunkIndex + 1L
                val perChunk = t.stsc.lastOrNull { it.first <= chunk }?.second ?: 1L
                var at = chunkStart
                for (k in 0 until perChunk) {
                    if (sample >= t.sampleSizes.size) break
                    offsets += at
                    at += t.sampleSizes[sample]
                    sample++
                }
            }
            // When each sample starts, from the time-to-sample table.
            val starts = ArrayList<Long>()
            var time = 0L
            for ((count, duration) in t.stts) for (k in 0 until count) {
                if (starts.size >= offsets.size) break
                starts += time
                time += duration
            }
            return offsets.indices.mapNotNull { i ->
                val start = starts.getOrNull(i) ?: return@mapNotNull null
                val raw = ch.readAt(offsets[i], t.sampleSizes[i].toInt().coerceAtMost(1024))
                if (raw.size < 2) return@mapNotNull null
                val len = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
                val bytes = raw.copyOfRange(2, (2 + len).coerceAtMost(raw.size))
                val title = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                FileChapter(title.trim(), start * 1000 / t.timescale)
            }
        }

        /** The boxes laid end to end between [from] and [to]. */
        private fun children(from: Long, to: Long): List<Box> {
            val out = ArrayList<Box>()
            var at = from
            while (at + 8 <= to) {
                val h = ByteBuffer.wrap(ch.readAt(at, 16))
                if (h.remaining() < 8) break
                var boxSize = u32(h.int)
                val type = String(ByteArray(4).also { h.get(it) }, Charsets.ISO_8859_1)
                var header = 8
                if (boxSize == 1L && h.remaining() >= 8) {
                    boxSize = h.long
                    header = 16
                } else if (boxSize == 0L) {
                    boxSize = to - at
                }
                if (boxSize < header || at + boxSize > to) break
                out += Box(type, at, header, boxSize)
                at += boxSize
            }
            return out
        }
    }

    private fun u32(i: Int) = i.toLong() and 0xFFFFFFFFL

    // ---- MP3 (ID3v2 CHAP frames)

    private fun id3(ch: FileChannel): List<FileChapter> {
        val header = ch.readAt(0, 10)
        val major = header[3].toInt()
        if (major < 3) return emptyList()
        val flags = header[5].toInt()
        val tagSize = syncsafe(header, 6)
        val tag = ch.readAt(10, tagSize)
        var at = 0
        if (flags and 0x40 != 0) {
            // Extended header: in v2.4 its size includes itself; in v2.3 it doesn't.
            at = if (major == 4) syncsafe(tag, 0) else int32(tag, 0) + 4
        }
        val out = ArrayList<FileChapter>()
        while (at + 10 <= tag.size) {
            val id = String(tag, at, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = if (major == 4) syncsafe(tag, at + 4) else int32(tag, at + 4)
            val body = at + 10
            if (size <= 0 || body + size > tag.size) break
            if (id == "CHAP") chapFrame(tag, body, body + size, major)?.let { out += it }
            at = body + size
        }
        return out
    }

    private fun chapFrame(tag: ByteArray, from: Int, to: Int, major: Int): FileChapter? {
        var at = from
        while (at < to && tag[at] != 0.toByte()) at++ // element id
        at++
        if (at + 16 > to) return null
        val startMs = int32(tag, at).toLong() and 0xFFFFFFFFL
        at += 16
        var title: String? = null
        while (at + 10 <= to) {
            val id = String(tag, at, 4, Charsets.ISO_8859_1)
            val size = if (major == 4) syncsafe(tag, at + 4) else int32(tag, at + 4)
            val body = at + 10
            if (size <= 0 || body + size > to) break
            if (id == "TIT2") title = text(tag, body, size)
            at = body + size
        }
        return FileChapter(title.orEmpty(), startMs)
    }

    private fun text(b: ByteArray, from: Int, size: Int): String {
        if (size < 1) return ""
        val charset: Charset = when (b[from].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(b, from + 1, size - 1, charset).trimEnd('\u0000').trim()
    }

    private fun syncsafe(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun int32(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /** Up to [n] bytes from [pos] (fewer at the end of the file). */
    private fun FileChannel.readAt(pos: Long, n: Int): ByteArray {
        if (n <= 0 || pos >= size()) return ByteArray(0)
        val buf = ByteBuffer.allocate(minOf(n.toLong(), size() - pos).toInt())
        var p = pos
        while (buf.hasRemaining()) {
            val r = read(buf, p)
            if (r <= 0) break
            p += r
        }
        return buf.array().copyOf(buf.position())
    }
}
