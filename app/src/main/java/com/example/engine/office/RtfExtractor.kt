package com.example.engine.office

/**
 * Plain text of an RTF document. Many files named `.doc` are really RTF (WordPad, older exporters), so the
 * `.doc` path falls back to this when the file isn't a Word binary.
 *
 * Handles groups, control words, `\'hh` code-page escapes (Windows-1252), `\uN` Unicode escapes with their
 * fallback characters, and skips destinations that aren't body text (font tables, styles, pictures, ...).
 */
object RtfExtractor {

    private val SKIP_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "headerl", "headerr",
        "footerl", "footerr", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata",
        "colorschememapping", "latentstyles", "datastore", "fldinst", "filetbl", "revtbl", "pgdsctbl"
    )
    private val CP1252 = java.nio.charset.Charset.forName("windows-1252")

    fun isRtf(bytes: ByteArray): Boolean =
        bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "{\\rtf"

    fun extract(bytes: ByteArray, maxChars: Int = 2_000_000): String {
        val s = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder()
        // Per group: whether its text is skipped, and how many fallback chars follow a \u escape.
        val skipStack = ArrayDeque<Boolean>()
        val ucStack = ArrayDeque<Int>()
        var skip = false
        var uc = 1
        var pendingFallback = 0
        var i = 0
        while (i < s.length && out.length < maxChars) {
            val c = s[i]
            when (c) {
                '{' -> {
                    skipStack.addLast(skip); ucStack.addLast(uc); i++
                    if (s.startsWith("\\*", i)) { skip = true; i += 2 }
                }
                '}' -> {
                    skip = skipStack.removeLastOrNull() ?: false
                    uc = ucStack.removeLastOrNull() ?: 1
                    i++
                }
                '\\' -> {
                    if (i + 1 >= s.length) break
                    val n = s[i + 1]
                    when {
                        n == '\'' && i + 3 < s.length -> {
                            val hex = s.substring(i + 2, i + 4).toIntOrNull(16)
                            if (pendingFallback > 0) pendingFallback-- else if (!skip && hex != null) {
                                out.append(String(byteArrayOf(hex.toByte()), CP1252))
                            }
                            i += 4
                        }
                        n.isLetter() -> {
                            var j = i + 1
                            while (j < s.length && s[j].isLetter()) j++
                            val word = s.substring(i + 1, j)
                            var k = j
                            if (k < s.length && (s[k] == '-' || s[k].isDigit())) {
                                k++
                                while (k < s.length && s[k].isDigit()) k++
                            }
                            val param = s.substring(j, k).toIntOrNull()
                            if (k < s.length && s[k] == ' ') k++
                            i = k
                            when (word) {
                                in SKIP_DESTINATIONS -> skip = true
                                "uc" -> uc = param ?: 1
                                "u" -> if (param != null) {
                                    if (!skip) out.append((if (param < 0) param + 65536 else param).toChar())
                                    pendingFallback = uc
                                }
                                "par", "line", "row", "sect", "page" -> if (!skip) out.append('\n')
                                "cell", "tab" -> if (!skip) out.append(' ')
                                "bin" -> i += (param ?: 0).coerceAtLeast(0)
                            }
                        }
                        else -> {
                            // Escaped symbol: \\ \{ \} and \~ (non-breaking space); others are ignored.
                            if (!skip) when (n) {
                                '\\', '{', '}' -> out.append(n)
                                '~' -> out.append(' ')
                                '\n', '\r' -> out.append('\n')
                            }
                            i += 2
                        }
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (pendingFallback > 0) pendingFallback-- else if (!skip) out.append(c)
                    i++
                }
            }
        }
        return out.lines().map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
