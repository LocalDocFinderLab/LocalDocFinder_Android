package com.example.engine.office

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Cell text of an Excel 2007+ `.xlsx` workbook, in the same layout as [LegacyExcelExtractor]
 * ("Sheet: name", then one line per row with cells separated by " | ").
 *
 * Reads `xl/workbook.xml` (sheet names and order), its relationships (which file holds each sheet),
 * `xl/sharedStrings.xml` and the sheet files. The XML is scanned directly rather than through a DOM, so
 * large sheets don't build a huge tree in memory.
 */
object XlsxExtractor {

    private const val MAX_ENTRY_BYTES = 60 * 1024 * 1024

    private val SI = Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
    private val PHONETIC = Regex("""<rPh\b.*?</rPh>""", RegexOption.DOT_MATCHES_ALL)
    private val T = Regex("""<t\b[^>]*?(?:/>|>(.*?)</t>)""", RegexOption.DOT_MATCHES_ALL)
    private val SHEET = Regex("""<sheet\b([^>]*)/?>""")
    private val REL = Regex("""<Relationship\b([^>]*)/?>""")
    private val ROW = Regex("""<row\b[^>]*?(?:/>|>(.*?)</row>)""", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
    private val V = Regex("""<v\b[^>]*>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
    private val IS = Regex("""<is\b[^>]*>(.*?)</is>""", RegexOption.DOT_MATCHES_ALL)

    fun extract(input: InputStream, maxChars: Int = 2_000_000): String {
        val parts = HashMap<String, String>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.trimStart('/')
                val wanted = name == "xl/workbook.xml" || name == "xl/_rels/workbook.xml.rels" ||
                    name == "xl/sharedStrings.xml" || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))
                if (wanted && !entry.isDirectory) parts[name] = readCapped(zip).toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val shared = parts["xl/sharedStrings.xml"]?.let { xml ->
            SI.findAll(xml).map { m -> textOf(PHONETIC.replace(m.groupValues[1], "")) }.toList()
        } ?: emptyList()

        val targets = HashMap<String, String>()
        parts["xl/_rels/workbook.xml.rels"]?.let { xml ->
            for (m in REL.findAll(xml)) {
                val id = attr(m.groupValues[1], "Id") ?: continue
                val target = attr(m.groupValues[1], "Target") ?: continue
                targets[id] = if (target.startsWith("/")) target.trimStart('/') else "xl/" + target.removePrefix("./")
            }
        }
        val sheets = parts["xl/workbook.xml"]?.let { xml ->
            SHEET.findAll(xml).map { m ->
                val a = m.groupValues[1]
                val name = OfficeXml.unescape(attr(a, "name") ?: "")
                val rid = attr(a, "r:id") ?: Regex("""\b\w+:id="([^"]*)"""").find(a)?.groupValues?.get(1)
                name to (rid?.let { targets[it] })
            }.toList()
        } ?: emptyList()

        // Sheets named in the workbook first, in workbook order; any sheet file not listed there after them.
        val ordered = ArrayList<Pair<String, String>>()
        sheets.forEachIndexed { i, (name, path) ->
            if (path != null && parts.containsKey(path)) ordered.add(name.ifBlank { "Sheet ${i + 1}" } to path)
        }
        parts.keys.filter { it.startsWith("xl/worksheets/") && ordered.none { o -> o.second == it } }
            .sortedWith(compareBy({ it.length }, { it }))
            .forEach { ordered.add(it.substringAfterLast('/').substringBefore(".xml") to it) }

        val out = StringBuilder()
        for ((name, path) in ordered) {
            if (out.length >= maxChars) break
            val xml = parts[path] ?: continue
            val rows = StringBuilder()
            for (row in ROW.findAll(xml)) {
                val body = row.groupValues[1]
                if (body.isEmpty()) continue
                val cells = CELL.findAll(body).mapNotNull { c -> cellText(c.groupValues[1], c.groupValues[2], shared) }
                    .filter { it.isNotBlank() }.toList()
                if (cells.isNotEmpty()) rows.append(cells.joinToString(" | ")).append('\n')
                if (out.length + rows.length >= maxChars) break
            }
            if (rows.isNotEmpty()) out.append("Sheet: ").append(name).append('\n').append(rows).append('\n')
        }
        return out.toString().trim()
    }

    private fun cellText(attrs: String, body: String, shared: List<String>): String? {
        val type = attr(attrs, "t") ?: "n"
        return when (type) {
            "s" -> V.find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }
            "inlineStr" -> IS.find(body)?.let { textOf(it.groupValues[1]) }
            "b" -> when (V.find(body)?.groupValues?.get(1)?.trim()) { "1" -> "TRUE"; "0" -> "FALSE"; else -> null }
            "e" -> null
            "str", "d" -> V.find(body)?.let { OfficeXml.unescape(it.groupValues[1]).trim() }
            else -> V.find(body)?.groupValues?.get(1)?.trim()?.let { v ->
                v.toDoubleOrNull()?.let { LegacyExcelExtractor.formatNumber(it) } ?: OfficeXml.unescape(v)
            }
        }?.trim()
    }

    private fun textOf(xml: String): String =
        T.findAll(xml).joinToString("") { OfficeXml.unescape(it.groupValues[1]) }.trim()

    private fun attr(attrs: String, name: String): String? =
        Regex("""(?:^|\s)${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(attrs)?.groupValues?.get(1)

    private fun readCapped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (out.size() < MAX_ENTRY_BYTES) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

internal object OfficeXml {
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""")

    fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e == "amp" -> "&"
                e == "lt" -> "<"
                e == "gt" -> ">"
                e == "quot" -> "\""
                e == "apos" -> "'"
                e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                else -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }
}
