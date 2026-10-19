package com.example.engine.search

/**
 * What the user typed, split into the parts the ranker cares about.
 *
 * Plain words are searched everywhere (file name, text, text inside images). A few optional operators
 * narrow things down:
 *  - `"exact phrase"`   the words must appear together, in this order
 *  - `-word`            leave out files containing this word
 *  - `name:word`        the word must be in the file name (`file:` and `title:` work too)
 *  - `ext:pdf`          only this file type (`type:` works too; `type:image` means any picture)
 */
data class ParsedQuery(
    val raw: String,
    /** Normalized words searched in name and text, including the words of quoted phrases. */
    val terms: List<String>,
    /** Quoted phrases, each as its list of normalized words. */
    val phrases: List<List<String>>,
    val excluded: List<String>,
    /** Words that must appear in the file name. */
    val nameTerms: List<String>,
    /** Allowed file extensions (lowercase, no dot); empty means any. */
    val extensions: Set<String>,
    /** The last word may still be half typed, so it also matches as a prefix ("invo" -> "invoice"). */
    val lastTermIsPrefix: Boolean
) {
    /** Every positive word, in order. */
    val allTerms: List<String> get() = (terms + nameTerms).distinct()

    /** Words that decide coverage: stop words only count when the query has nothing else. */
    val requiredTerms: List<String>
        get() {
            val all = allTerms
            val meaningful = all.filter { it !in TextMatch.STOPWORDS }
            return meaningful.ifEmpty { all }
        }

    val hasPositiveTerms: Boolean get() = allTerms.isNotEmpty()

    fun isPrefixTerm(term: String): Boolean = lastTermIsPrefix && term == terms.lastOrNull()
}

object QueryParser {

    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "dng", "bmp", "tiff", "tif")
    private val IMAGE_ALIASES = setOf("image", "images", "photo", "photos", "picture", "pictures", "img")
    private val NAME_KEYS = setOf("name", "file", "filename", "title")
    private val TYPE_KEYS = setOf("ext", "type", "filetype")
    private val PHRASE = Regex(""""([^"]*)"?""")

    fun parse(raw: String): ParsedQuery {
        val phrases = ArrayList<List<String>>()
        val terms = ArrayList<String>()
        val excluded = ArrayList<String>()
        val nameTerms = ArrayList<String>()
        val extensions = LinkedHashSet<String>()

        // Pull out quoted phrases first; an unterminated quote runs to the end of the input.
        val rest = StringBuilder()
        var last = 0
        for (m in PHRASE.findAll(raw)) {
            rest.append(raw, last, m.range.first).append(' ')
            val words = TextMatch.words(m.groupValues[1])
            if (words.size > 1) phrases.add(words)
            terms.addAll(words)
            last = m.range.last + 1
        }
        if (last < raw.length) rest.append(raw, last, raw.length)
        val endsInsidePhrase = raw.count { it == '"' } % 2 == 1

        var lastWasBareTerm = false
        for (token in rest.split(Regex("""\s+""")).filter { it.isNotBlank() }) {
            lastWasBareTerm = false
            val colon = token.indexOf(':')
            val key = if (colon > 0) token.substring(0, colon).lowercase() else ""
            val value = if (colon > 0) token.substring(colon + 1) else token
            when {
                token.startsWith("-") && token.length > 1 -> excluded.addAll(TextMatch.words(token.substring(1)))
                key in NAME_KEYS && value.isNotBlank() -> nameTerms.addAll(TextMatch.words(value))
                key in TYPE_KEYS && value.isNotBlank() -> {
                    val ext = value.lowercase().removePrefix(".")
                    if (ext in IMAGE_ALIASES) extensions.addAll(IMAGE_EXTENSIONS) else extensions.add(ext)
                }
                else -> {
                    val words = TextMatch.words(token).filter { it.length > 1 || it[0].isDigit() }
                    if (words.isNotEmpty()) {
                        terms.addAll(words)
                        lastWasBareTerm = true
                    }
                }
            }
        }

        val prefix = lastWasBareTerm && !endsInsidePhrase && raw.isNotEmpty() && !raw.last().isWhitespace()
        return ParsedQuery(
            raw = raw,
            terms = terms.distinct(),
            phrases = phrases,
            excluded = excluded.distinct(),
            nameTerms = nameTerms.distinct(),
            extensions = extensions,
            lastTermIsPrefix = prefix
        )
    }
}
