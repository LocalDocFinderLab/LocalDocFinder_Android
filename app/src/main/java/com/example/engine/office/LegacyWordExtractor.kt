package com.example.engine.office

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Text of a Word 97–2003 `.doc` file ([MS-DOC]).
 *
 * The text lives in the `WordDocument` stream as "pieces", each either 8-bit (Windows-1252) or UTF-16.
 * The piece table (CLX) that says where each piece starts is in the `0Table` or `1Table` stream, located
 * through the File Information Block (FIB) at the start of `WordDocument`. Body text, headers, footnotes,
 * comments and text boxes are all returned. Field codes (the `HYPERLINK "…"` part of a link) are dropped,
 * their visible result is kept.
 */
object LegacyWordExtractor {

    private const val WORD_IDENT = 0xA5EC
    private const val FC_CLX_INDEX = 33
    private const val CELL_END = '\u0001'
    private val CP1252: Charset = Charset.forName("windows-1252")

    /** Extracted text, or "" for encrypted, pre-97 or unreadable files. */
    fun extract(file: CompoundFile, maxChars: Int = 2_000_000): String {
        val word = file.stream("WordDocument") ?: return ""
        if (word.size < 0x200) return ""
        val w = ByteBuffer.wrap(word).order(ByteOrder.LITTLE_ENDIAN)
        if ((w.getShort(0).toInt() and 0xFFFF) != WORD_IDENT) return ""
        val flags = w.getShort(0x0A).toInt() and 0xFFFF
        if (flags and 0x0100 != 0) return "" // fEncrypted
        val tableName = if (flags and 0x0200 != 0) "1Table" else "0Table"
        val table = file.stream(tableName) ?: return ""

        // FibBase (32 bytes), then three variable-length arrays; fcClx/lcbClx is pair #33 of FibRgFcLcb.
        var pos = 0x20
        val csw = w.getShort(pos).toInt() and 0xFFFF
        pos += 2 + csw * 2
        val cslw = w.getShort(pos).toInt() and 0xFFFF
        pos += 2 + cslw * 4
        val cbRgFcLcb = w.getShort(pos).toInt() and 0xFFFF
        pos += 2
        if (cbRgFcLcb <= FC_CLX_INDEX) return ""
        val fcClx = w.getInt(pos + FC_CLX_INDEX * 8)
        val lcbClx = w.getInt(pos + FC_CLX_INDEX * 8 + 4)
        if (fcClx < 0 || lcbClx <= 0 || fcClx.toLong() + lcbClx > table.size) return ""

        val raw = readPieces(table, fcClx, lcbClx, word, maxChars)
        return clean(raw)
    }

    private fun readPieces(table: ByteArray, fcClx: Int, lcbClx: Int, word: ByteArray, maxChars: Int): String {
        val t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        var p = fcClx
        val end = fcClx + lcbClx
        // Skip any Prc (formatting) blocks, then read the Pcdt (piece table).
        while (p < end && table[p].toInt() == 0x01) {
            val cb = t.getShort(p + 1).toInt()
            p += 3 + cb
        }
        if (p >= end || table[p].toInt() != 0x02) return ""
        val lcb = t.getInt(p + 1)
        val plc = p + 5
        if (lcb < 4 || plc + lcb > table.size) return ""
        val n = (lcb - 4) / 12
        val sb = StringBuilder()
        for (i in 0 until n) {
            val cpStart = t.getInt(plc + i * 4)
            val cpEnd = t.getInt(plc + (i + 1) * 4)
            val chars = cpEnd - cpStart
            if (chars <= 0) continue
            val pcd = plc + (n + 1) * 4 + i * 8
            val fcRaw = t.getInt(pcd + 2)
            val compressed = fcRaw and 0x40000000 != 0
            val fc = fcRaw and 0x3FFFFFFF
            if (compressed) {
                val off = fc / 2
                val len = minOf(chars, word.size - off)
                if (off < 0 || len <= 0) continue
                sb.append(String(word, off, len, CP1252))
            } else {
                val len = minOf(chars * 2, word.size - fc) and 1.inv()
                if (fc < 0 || len <= 0) continue
                sb.append(String(word, fc, len, Charsets.UTF_16LE))
            }
            if (sb.length >= maxChars) break
        }
        return sb.toString()
    }

    /** Turns Word's control characters into plain text and drops field codes. */
    internal fun clean(raw: String): String {
        val sb = StringBuilder(raw.length)
        // For each open field: true while still inside its code part (before the separator).
        val fields = ArrayDeque<Boolean>()
        for (ch in raw) {
            when (ch.code) {
                0x13 -> { fields.addLast(true); continue }
                0x14 -> { if (fields.isNotEmpty()) { fields.removeLast(); fields.addLast(false) }; continue }
                0x15 -> { if (fields.isNotEmpty()) fields.removeLast(); continue }
            }
            if (fields.any { it }) continue
            when (ch.code) {
                0x0D, 0x0B, 0x0C, 0x0E -> sb.append('\n')
                0x07 -> sb.append(CELL_END) // end of a table cell; two in a row end the table row
                0x09 -> sb.append('\t')
                0x1E -> sb.append('-')
                0x1F -> {}
                0xA0 -> sb.append(' ')
                else -> if (ch.code >= 0x20) sb.append(ch)
            }
        }
        return sb.toString()
            .replace("$CELL_END$CELL_END", "\n")
            .replace(CELL_END.toString(), " | ")
            .lines()
            .map { it.replace(Regex("""[ \t]+"""), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
