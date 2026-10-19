package com.example.engine

/** A set of indexed files that share (almost) all of their content. */
data class DuplicateGroup(
    val fileUris: List<String>,
    /** True when every file has exactly the same set of content fingerprints. */
    val exact: Boolean,
    /** Lowest pairwise overlap (Jaccard, 0..1) that linked the files of this group. */
    val similarity: Float
)

/**
 * Finds duplicate and near-duplicate files from per-chunk content fingerprints, without comparing every pair of
 * files: an inverted index (fingerprint -> files) only visits pairs that actually share a chunk.
 */
object DuplicateFinder {

    const val DEFAULT_THRESHOLD = 0.8f

    /** Chunks shared by more files than this (headers, licence text, boilerplate) say nothing about duplication. */
    private const val MAX_FILES_PER_FINGERPRINT = 50

    /** 64-bit FNV-1a over [bytes]; stable across runs and wide enough that collisions are negligible. */
    fun fingerprint(bytes: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in bytes) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h
    }

    /**
     * @param chunks (fileUri, fingerprint) for every indexed chunk; order does not matter.
     * @param threshold minimum Jaccard overlap of two files' fingerprint sets to call them duplicates.
     */
    fun find(chunks: Iterable<Pair<String, Long>>, threshold: Float = DEFAULT_THRESHOLD): List<DuplicateGroup> {
        val fileIndex = HashMap<String, Int>()
        val files = ArrayList<String>()
        val prints = ArrayList<HashSet<Long>>()
        for ((uri, fp) in chunks) {
            val idx = fileIndex.getOrPut(uri) { files.add(uri); prints.add(HashSet()); files.size - 1 }
            prints[idx].add(fp)
        }
        if (files.size < 2) return emptyList()

        val filesByPrint = HashMap<Long, MutableList<Int>>()
        for (i in prints.indices) for (fp in prints[i]) filesByPrint.getOrPut(fp) { ArrayList(2) }.add(i)

        val shared = HashMap<Long, Int>() // (a shl 32 | b) with a < b -> shared fingerprint count
        for (list in filesByPrint.values) {
            if (list.size < 2 || list.size > MAX_FILES_PER_FINGERPRINT) continue
            for (x in 0 until list.size - 1) for (y in x + 1 until list.size) {
                val a = minOf(list[x], list[y])
                val b = maxOf(list[x], list[y])
                val key = (a.toLong() shl 32) or b.toLong()
                shared[key] = (shared[key] ?: 0) + 1
            }
        }

        val parent = IntArray(files.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var c = i
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        val edgeSimilarity = ArrayList<Triple<Int, Int, Float>>()
        for ((key, count) in shared) {
            val a = (key ushr 32).toInt()
            val b = (key and 0xffffffffL).toInt()
            val jaccard = count.toFloat() / (prints[a].size + prints[b].size - count)
            if (jaccard >= threshold) {
                edgeSimilarity.add(Triple(a, b, jaccard))
                parent[root(a)] = root(b)
            }
        }

        val members = HashMap<Int, MutableList<Int>>()
        val groupSimilarity = HashMap<Int, Float>()
        for ((a, b, sim) in edgeSimilarity) {
            val r = root(a)
            groupSimilarity[r] = minOf(groupSimilarity[r] ?: 1f, sim)
            members.getOrPut(r) { ArrayList() }.apply {
                if (!contains(a)) add(a)
                if (!contains(b)) add(b)
            }
        }
        return members.map { (r, ids) ->
            val sorted = ids.map { files[it] }.sorted()
            val first = prints[ids[0]]
            DuplicateGroup(
                fileUris = sorted,
                exact = ids.all { prints[it] == first },
                similarity = groupSimilarity[r] ?: 1f
            )
        }.sortedWith(compareByDescending<DuplicateGroup> { it.exact }.thenByDescending { it.fileUris.size }.thenBy { it.fileUris.first() })
    }
}
