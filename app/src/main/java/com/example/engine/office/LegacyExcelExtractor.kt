package com.example.engine.office

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

/**
 * Cell text of an Excel 97–2003 `.xls` workbook (BIFF8, [MS-XLS]), one line per row and one block per sheet:
 *
 * ```
 * Sheet: Expenses
 * Date | Item | Amount
 * 45292 | Rent | 1200
 * ```
 *
 * Strings come from the shared string table (SST, which may be split over CONTINUE records), numbers from
 * NUMBER / RK / MULRK records and formula results from FORMULA / STRING records. Dates stay as Excel serial
 * numbers. Encrypted workbooks return "".
 */
object LegacyExcelExtractor {

    private const val BOF = 0x0809
    private const val EOF = 0x000A
    private const val BOUNDSHEET = 0x0085
    private const val SST = 0x00FC
    private const val CONTINUE = 0x003C
    private const val LABELSST = 0x00FD
    private const val LABEL = 0x0204
    private const val NUMBER = 0x0203
    private const val RK = 0x027E
    private const val MULRK = 0x00BD
    private const val FORMULA = 0x0006
    private const val STRING = 0x0207
    private const val FILEPASS = 0x002F
    private const val SUBSTREAM_WORKSHEET = 0x0010

    fun extract(file: CompoundFile, maxChars: Int = 2_000_000): String {
        val book = file.stream("Workbook") ?: return "" // "Book" is BIFF5 (Excel 95), not supported
        val records = parseRecords(book)

        val sheetNames = ArrayList<String>()
        var sst: List<String> = emptyList()
        var i = 0
        // Workbook globals: everything up to the first EOF.
        while (i < records.size) {
            val r = records[i]
            when (r.type) {
                FILEPASS -> return ""
                BOUNDSHEET -> if (r.data.size >= 8) {
                    val dt = r.data[5].toInt() and 0xFF
                    val name = readShortString(r.data, 6)
                    sheetNames.add(if (dt == 0) name else "")
                }
                SST -> {
                    val segments = arrayListOf(r.data)
                    var j = i + 1
                    while (j < records.size && records[j].type == CONTINUE) segments.add(records[j++].data)
                    sst = readSst(segments)
                    i = j - 1
                }
            }
            i++
            if (r.type == EOF) break
        }

        val out = StringBuilder()
        var sheetIndex = -1
        while (i < records.size && out.length < maxChars) {
            val r = records[i]
            if (r.type == BOF && r.data.size >= 4) {
                sheetIndex++
                val dt = (r.data[2].toInt() and 0xFF) or ((r.data[3].toInt() and 0xFF) shl 8)
                val end = indexOfEof(records, i + 1)
                if (dt == SUBSTREAM_WORKSHEET) {
                    val cells = readCells(records, i + 1, end, sst)
                    if (cells.isNotEmpty()) {
                        val name = sheetNames.getOrNull(sheetIndex)?.takeIf { it.isNotBlank() } ?: "Sheet ${sheetIndex + 1}"
                        appendSheet(out, name, cells)
                    }
                }
                i = end + 1
                continue
            }
            i++
        }
        return out.toString().trim()
    }

    internal fun appendSheet(out: StringBuilder, name: String, cells: TreeMap<Int, TreeMap<Int, String>>) {
        out.append("Sheet: ").append(name).append('\n')
        for ((_, row) in cells) {
            val line = row.values.filter { it.isNotBlank() }.joinToString(" | ")
            if (line.isNotEmpty()) out.append(line).append('\n')
        }
        out.append('\n')
    }

    private class Record(val type: Int, val data: ByteArray)

    private fun parseRecords(stream: ByteArray): List<Record> {
        val list = ArrayList<Record>()
        var p = 0
        while (p + 4 <= stream.size) {
            val type = (stream[p].toInt() and 0xFF) or ((stream[p + 1].toInt() and 0xFF) shl 8)
            val len = (stream[p + 2].toInt() and 0xFF) or ((stream[p + 3].toInt() and 0xFF) shl 8)
            if (p + 4 + len > stream.size) break
            list.add(Record(type, stream.copyOfRange(p + 4, p + 4 + len)))
            p += 4 + len
        }
        return list
    }

    private fun indexOfEof(records: List<Record>, from: Int): Int {
        var depth = 0
        for (k in from until records.size) {
            when (records[k].type) {
                BOF -> depth++
                EOF -> if (depth == 0) return k else depth--
            }
        }
        return records.size - 1
    }

    private fun readCells(records: List<Record>, from: Int, to: Int, sst: List<String>): TreeMap<Int, TreeMap<Int, String>> {
        val cells = TreeMap<Int, TreeMap<Int, String>>()
        var count = 0
        fun put(row: Int, col: Int, value: String) {
            if (value.isBlank() || count >= MAX_CELLS) return
            cells.getOrPut(row) { TreeMap() }[col] = value.trim()
            count++
        }
        var pendingFormula: Pair<Int, Int>? = null
        for (k in from until to) {
            val d = records[k].data
            val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            when (records[k].type) {
                LABELSST -> if (d.size >= 10) {
                    sst.getOrNull(b.getInt(6))?.let { put(u16(b, 0), u16(b, 2), it) }
                }
                LABEL -> if (d.size >= 9) put(u16(b, 0), u16(b, 2), readUnicodeString(d, 6))
                NUMBER -> if (d.size >= 14) put(u16(b, 0), u16(b, 2), formatNumber(b.getDouble(6)))
                RK -> if (d.size >= 10) put(u16(b, 0), u16(b, 2), formatNumber(rkValue(b.getInt(6))))
                MULRK -> if (d.size >= 6) {
                    val row = u16(b, 0)
                    var col = u16(b, 2)
                    var p = 4
                    while (p + 6 <= d.size - 2) {
                        put(row, col++, formatNumber(rkValue(b.getInt(p + 2))))
                        p += 6
                    }
                }
                FORMULA -> if (d.size >= 14) {
                    val row = u16(b, 0)
                    val col = u16(b, 2)
                    val isSpecial = (d[12].toInt() and 0xFF) == 0xFF && (d[13].toInt() and 0xFF) == 0xFF
                    if (!isSpecial) {
                        put(row, col, formatNumber(b.getDouble(6)))
                    } else if (d[6].toInt() == 0) {
                        pendingFormula = row to col // string result follows in a STRING record
                    }
                }
                STRING -> pendingFormula?.let { (row, col) ->
                    if (d.size >= 3) put(row, col, readUnicodeString(d, 0))
                    pendingFormula = null
                }
            }
        }
        return cells
    }

    /** RK: a compressed number, either a 30-bit integer or the top 30 bits of a double, optionally divided by 100. */
    internal fun rkValue(rk: Int): Double {
        val div100 = rk and 0x01 != 0
        val isInt = rk and 0x02 != 0
        val v = if (isInt) (rk shr 2).toDouble() else java.lang.Double.longBitsToDouble((rk.toLong() and 0xFFFFFFFCL) shl 32)
        return if (div100) v / 100.0 else v
    }

    internal fun formatNumber(v: Double): String = when {
        v.isNaN() || v.isInfinite() -> ""
        v == Math.rint(v) && kotlin.math.abs(v) < 1e15 -> v.toLong().toString()
        else -> java.math.BigDecimal(v).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private fun u16(b: ByteBuffer, off: Int): Int = b.getShort(off).toInt() and 0xFFFF

    /** ShortXLUnicodeString: 8-bit length, flags, characters. */
    private fun readShortString(d: ByteArray, off: Int): String {
        if (off + 2 > d.size) return ""
        val cch = d[off].toInt() and 0xFF
        val high = d[off + 1].toInt() and 0x01 != 0
        return decodeChars(d, off + 2, cch, high)
    }

    /** XLUnicodeString: 16-bit length, flags, characters. */
    private fun readUnicodeString(d: ByteArray, off: Int): String {
        if (off + 3 > d.size) return ""
        val cch = (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)
        val high = d[off + 2].toInt() and 0x01 != 0
        return decodeChars(d, off + 3, cch, high)
    }

    private fun decodeChars(d: ByteArray, off: Int, cch: Int, high: Boolean): String {
        val bytes = if (high) cch * 2 else cch
        val len = minOf(bytes, d.size - off)
        if (len <= 0) return ""
        return if (high) String(d, off, len and 1.inv(), Charsets.UTF_16LE) else String(d, off, len, Charsets.ISO_8859_1)
    }

    /** Reads the shared string table; strings may be split across CONTINUE records. */
    private fun readSst(segments: List<ByteArray>): List<String> {
        val r = SegmentReader(segments)
        r.skip(4)
        val unique = r.u32()
        val strings = ArrayList<String>(minOf(unique.coerceAtLeast(0), 1_000_000))
        var k = 0
        while (k < unique && r.hasMore()) {
            val cch = r.u16()
            val flags = r.u8()
            val rich = flags and 0x08 != 0
            val ext = flags and 0x04 != 0
            val runs = if (rich) r.u16() else 0
            val extLen = if (ext) r.u32() else 0
            strings.add(r.chars(cch, flags and 0x01 != 0))
            r.skip(runs * 4)
            r.skip(extLen)
            k++
        }
        return strings
    }

    /** Sequential reader over the SST record and its CONTINUE records. */
    private class SegmentReader(private val segments: List<ByteArray>) {
        private var seg = 0
        private var pos = 0

        fun hasMore(): Boolean {
            while (seg < segments.size && pos >= segments[seg].size) next()
            return seg < segments.size
        }

        private fun next() {
            seg++
            pos = 0
        }

        fun u8(): Int {
            if (!hasMore()) return 0
            return segments[seg][pos++].toInt() and 0xFF
        }

        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Int = u16() or (u16() shl 16)

        fun skip(n: Int) {
            var left = n
            while (left > 0 && hasMore()) {
                val step = minOf(left, segments[seg].size - pos)
                pos += step
                left -= step
            }
        }

        /** Characters may continue in the next segment, which then starts with a new 1-byte flags field. */
        fun chars(count: Int, highByte: Boolean): String {
            val sb = StringBuilder(count)
            var high = highByte
            var left = count
            while (left > 0 && seg < segments.size) {
                if (pos >= segments[seg].size) {
                    next()
                    if (seg >= segments.size) break
                    high = segments[seg][pos++].toInt() and 0x01 != 0
                    continue
                }
                val data = segments[seg]
                if (high) {
                    val n = minOf(left, (data.size - pos) / 2)
                    if (n <= 0) { pos = data.size; continue }
                    sb.append(String(data, pos, n * 2, Charsets.UTF_16LE))
                    pos += n * 2
                    left -= n
                } else {
                    val n = minOf(left, data.size - pos)
                    sb.append(String(data, pos, n, Charsets.ISO_8859_1))
                    pos += n
                    left -= n
                }
            }
            return sb.toString()
        }
    }

    private const val MAX_CELLS = 500_000
}
