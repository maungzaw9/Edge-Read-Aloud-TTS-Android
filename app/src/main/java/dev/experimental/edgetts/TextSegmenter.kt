package dev.experimental.edgetts

/**
 * Splits text by UTF-8 byte length without cutting multi-byte characters.
 * Prefers paragraph, line, Burmese sentence end, then space.
 *
 * Operational segment size is small so each Edge turn is short and
 * the wait for the first audio stays low.
 */
object TextSegmenter {

    const val MAX_SEGMENT_BYTES: Int = EdgeProtocolConstants.MAX_SEGMENT_BYTES

    /** 1200 bytes = about 400 Burmese characters or 1200 ASCII characters. */
    const val OPERATIONAL_SEGMENT_BYTES: Int = 1200

    fun segment(text: String): List<String> =
        segment(text, { false }, OPERATIONAL_SEGMENT_BYTES)

    fun segment(text: String, isCancelled: () -> Boolean): List<String> =
        segment(text, isCancelled, OPERATIONAL_SEGMENT_BYTES)

    fun segment(text: String, isCancelled: () -> Boolean, maxBytes: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val limit = maxBytes
            .coerceAtLeast(1)
            .coerceAtMost(OPERATIONAL_SEGMENT_BYTES)
            .coerceAtMost(MAX_SEGMENT_BYTES)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val result = ArrayList<String>()
        var offset = 0
        while (offset < bytes.size && !isCancelled()) {
            val cap = minOf(offset + limit, bytes.size)
            val end = if (cap == bytes.size) bytes.size else findSmartSplitPoint(bytes, offset, cap)
            check(end > offset) { "Segmentation did not advance: $offset -> $end" }
            result += String(bytes, offset, end - offset, Charsets.UTF_8)
            offset = end
        }
        return result
    }

    private fun findSmartSplitPoint(bytes: ByteArray, start: Int, end: Int): Int {
        for (i in end - 1 downTo start + 1) {
            if (bytes[i - 1] == '\n'.code.toByte() && bytes[i] == '\n'.code.toByte()) return i + 1
        }
        for (i in end - 1 downTo start) {
            if (bytes[i] == '\n'.code.toByte()) return i + 1
        }
        val sentenceEnd = findSentenceEnd(bytes, start, end)
        if (sentenceEnd > start) return sentenceEnd
        for (i in end - 1 downTo start) {
            val b = bytes[i]
            if (b == ' '.code.toByte() || b == '\t'.code.toByte()) return i + 1
        }
        return findUtf8SafeBoundary(bytes, start, end)
    }

    /**
     * Last sentence end inside [start, end):
     * Burmese "။" (E1 81 8B), Burmese "၊" (E1 81 8A), or ". " "! " "? ".
     * Returns -1 if none.
     */
    private fun findSentenceEnd(bytes: ByteArray, start: Int, end: Int): Int {
        var comma = -1
        var i = end - 3
        while (i >= start) {
            if ((bytes[i].toInt() and 0xFF) == 0xE1 &&
                (bytes[i + 1].toInt() and 0xFF) == 0x81
            ) {
                val third = bytes[i + 2].toInt() and 0xFF
                if (third == 0x8B) return i + 3
                if (third == 0x8A && comma < 0) comma = i + 3
            }
            i--
        }
        for (j in end - 2 downTo start) {
            val c = bytes[j]
            if ((c == '.'.code.toByte() || c == '!'.code.toByte() || c == '?'.code.toByte()) &&
                bytes[j + 1] == ' '.code.toByte()
            ) {
                return j + 2
            }
        }
        return comma
    }

    private fun findUtf8SafeBoundary(bytes: ByteArray, start: Int, end: Int): Int {
        val limit = minOf(end, bytes.size)
        var cursor = start
        while (cursor < limit) {
            val width = utf8Width(bytes[cursor])
            if (cursor + width > limit) break
            cursor += width
        }
        if (cursor == start) {
            val width = utf8Width(bytes[start]).coerceAtLeast(1)
            cursor = minOf(start + width, bytes.size)
        }
        return cursor
    }

    private fun utf8Width(lead: Byte): Int {
        val v = lead.toInt() and 0xFF
        return when {
            v < 0x80 -> 1
            v and 0xE0 == 0xC0 -> 2
            v and 0xF0 == 0xE0 -> 3
            v and 0xF8 == 0xF0 -> 4
            else -> 1
        }
    }
}
