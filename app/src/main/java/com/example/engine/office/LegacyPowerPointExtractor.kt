package com.example.engine.office

/**
 * Text of a PowerPoint 97–2003 `.ppt` file ([MS-PPT]): slide text, text boxes, tables and speaker notes.
 *
 * The `PowerPoint Document` stream is a tree of records. Containers (version nibble 0xF) hold other
 * records; text sits in TextCharsAtom (UTF-16) and TextBytesAtom (8-bit) atoms. The tree is walked in file
 * order and the default master-slide prompts ("Click to edit Master title style", ...) are dropped.
 */
object LegacyPowerPointExtractor {

    private const val TEXT_CHARS_ATOM = 0x0FA0
    private const val TEXT_BYTES_ATOM = 0x0FA8
    private const val MAIN_MASTER = 0x03F8
    private const val HANDOUT = 0x0FC9
    private const val MAX_DEPTH = 64

    private val MASTER_PROMPTS = setOf(
        "click to edit master title style",
        "click to edit master text styles",
        "click to edit master subtitle style",
        "second level",
        "third level",
        "fourth level",
        "fifth level",
        "click to edit the title text format",
        "click to edit the outline text format",
        "click to edit the notes format",
        "*"
    ) + listOf("second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth").map { "$it outline level" }

    fun extract(file: CompoundFile, maxChars: Int = 2_000_000): String {
        if (file.hasStream("EncryptedSummary")) return ""
        val doc = file.stream("PowerPoint Document") ?: return ""
        val texts = ArrayList<String>()
        walk(doc, 0, doc.size, 0, texts, maxChars)
        val out = StringBuilder()
        var previous: String? = null
        for (t in texts) {
            val clean = t.replace('\r', '\n').replace('\u000B', '\n')
                .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (clean.isEmpty() || clean == previous) continue
            if (clean.lines().all { it.lowercase() in MASTER_PROMPTS }) continue
            out.append(clean).append("\n\n")
            previous = clean
            if (out.length >= maxChars) break
        }
        return out.toString().trim()
    }

    private fun walk(d: ByteArray, from: Int, to: Int, depth: Int, out: MutableList<String>, budget: Int) {
        var p = from
        var used = 0
        while (p + 8 <= to && used < budget) {
            val verInst = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)
            val type = (d[p + 2].toInt() and 0xFF) or ((d[p + 3].toInt() and 0xFF) shl 8)
            val len = (d[p + 4].toInt() and 0xFF) or ((d[p + 5].toInt() and 0xFF) shl 8) or
                ((d[p + 6].toInt() and 0xFF) shl 16) or ((d[p + 7].toInt() and 0xFF) shl 24)
            val body = p + 8
            if (len < 0 || body + len > to) break
            if (verInst and 0x0F == 0x0F) {
                val isMaster = type == MAIN_MASTER || type == HANDOUT
                if (!isMaster && depth < MAX_DEPTH) walk(d, body, body + len, depth + 1, out, budget - used)
            } else when (type) {
                TEXT_CHARS_ATOM -> {
                    val s = String(d, body, len and 1.inv(), Charsets.UTF_16LE)
                    out.add(s)
                    used += s.length
                }
                TEXT_BYTES_ATOM -> {
                    val s = String(d, body, len, Charsets.ISO_8859_1)
                    out.add(s)
                    used += s.length
                }
            }
            p = body + len
        }
    }
}
