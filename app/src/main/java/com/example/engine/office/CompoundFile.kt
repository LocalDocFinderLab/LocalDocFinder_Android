package com.example.engine.office

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for OLE2 / Compound File Binary files, the container behind the pre-2007 Office formats
 * (.doc, .xls, .ppt). It only reads named streams; nothing is written or validated beyond what is needed
 * to read them safely. Plain Kotlin, no Android dependencies.
 *
 * Layout ([MS-CFB]): a 512-byte header, then fixed-size sectors. The FAT chains sectors into streams; the
 * directory lists streams by name; streams smaller than the mini-stream cutoff live in 64-byte mini sectors
 * inside the root entry's stream.
 */
class CompoundFile private constructor(private val data: ByteArray) {

    private val buf: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val entries: List<Entry>
    private val miniFat: IntArray
    private val miniStream: ByteArray

    private class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        val sectorShift = u16(0x1E)
        val miniShift = u16(0x20)
        if (sectorShift !in 7..16 || miniShift !in 2..sectorShift) throw IllegalArgumentException("Bad sector size")
        sectorSize = 1 shl sectorShift
        miniSectorSize = 1 shl miniShift
        miniCutoff = u32(0x38).toLong() and 0xFFFFFFFFL

        // FAT sector numbers: 109 in the header, the rest in the DIFAT chain.
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) {
            val s = buf.getInt(0x4C + i * 4)
            if (s >= 0) fatSectors.add(s)
        }
        var difat = buf.getInt(0x44)
        var guard = 0
        val perDifat = sectorSize / 4 - 1
        while (difat >= 0 && guard++ < MAX_SECTORS) {
            val off = offsetOf(difat)
            if (off + sectorSize > data.size) break
            for (i in 0 until perDifat) {
                val s = buf.getInt(off + i * 4)
                if (s >= 0) fatSectors.add(s)
            }
            difat = buf.getInt(off + perDifat * 4)
        }
        val perSector = sectorSize / 4
        val fatList = IntArray(fatSectors.size * perSector)
        var n = 0
        for (s in fatSectors) {
            val off = offsetOf(s)
            if (off + sectorSize > data.size) break
            for (i in 0 until perSector) fatList[n++] = buf.getInt(off + i * 4)
        }
        fat = fatList.copyOf(n)

        val dir = readChain(buf.getInt(0x30), fat, Long.MAX_VALUE)
        val dirBuf = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        val list = ArrayList<Entry>()
        var p = 0
        while (p + 128 <= dir.size) {
            val type = dir[p + 66].toInt() and 0xFF
            val nameLen = (dirBuf.getShort(p + 64).toInt() and 0xFFFF).coerceIn(0, 64)
            if (type != 0 && nameLen >= 2) {
                val name = String(dir, p, nameLen - 2, Charsets.UTF_16LE)
                val size = if (sectorSize == 512) dirBuf.getInt(p + 120).toLong() and 0xFFFFFFFFL else dirBuf.getLong(p + 120)
                list.add(Entry(name, type, dirBuf.getInt(p + 116), size))
            }
            p += 128
        }
        entries = list

        val root = list.firstOrNull { it.type == TYPE_ROOT }
        miniStream = if (root != null && root.start >= 0) readChain(root.start, fat, root.size) else ByteArray(0)
        val miniFatStart = buf.getInt(0x3C)
        miniFat = if (miniFatStart >= 0) toInts(readChain(miniFatStart, fat, Long.MAX_VALUE)) else IntArray(0)
    }

    fun hasStream(name: String): Boolean = entries.any { it.type == TYPE_STREAM && it.name.equals(name, ignoreCase = true) }

    /** Contents of the first stream called [name] (any storage), or null when there is none. */
    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == TYPE_STREAM && it.name.equals(name, ignoreCase = true) } ?: return null
        if (e.size <= 0L) return ByteArray(0)
        if (e.size > MAX_STREAM_BYTES) throw IllegalArgumentException("Stream $name too large")
        return if (e.size < miniCutoff) readMiniChain(e.start, e.size) else readChain(e.start, fat, e.size)
    }

    private fun offsetOf(sector: Int): Int = (sector + 1) * sectorSize

    private fun readChain(start: Int, table: IntArray, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && guard++ < MAX_SECTORS && out.size() < size) {
            val off = offsetOf(s)
            if (off < 0 || off >= data.size) break
            out.write(data, off, minOf(sectorSize, data.size - off))
            if (s >= table.size) break
            s = table[s]
        }
        val bytes = out.toByteArray()
        return if (size < bytes.size) bytes.copyOf(size.toInt()) else bytes
    }

    private fun readMiniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && guard++ < MAX_SECTORS && out.size() < size) {
            val off = s * miniSectorSize
            if (off >= miniStream.size) break
            out.write(miniStream, off, minOf(miniSectorSize, miniStream.size - off))
            if (s >= miniFat.size) break
            s = miniFat[s]
        }
        val bytes = out.toByteArray()
        return if (size < bytes.size) bytes.copyOf(size.toInt()) else bytes
    }

    private fun toInts(bytes: ByteArray): IntArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(bytes.size / 4) { b.getInt(it * 4) }
    }

    private fun u16(off: Int): Int = buf.getShort(off).toInt() and 0xFFFF
    private fun u32(off: Int): Int = buf.getInt(off)

    companion object {
        private const val TYPE_STREAM = 2
        private const val TYPE_ROOT = 5
        private const val MAX_SECTORS = 4_000_000
        private const val MAX_STREAM_BYTES = 200L * 1024 * 1024

        private val SIGNATURE = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )

        fun isCompoundFile(bytes: ByteArray): Boolean =
            bytes.size >= 512 && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

        /** Parses [bytes]; null when they are not a readable compound file. */
        fun open(bytes: ByteArray): CompoundFile? =
            if (!isCompoundFile(bytes)) null else try {
                CompoundFile(bytes)
            } catch (_: Exception) {
                null
            }
    }
}
