package org.openlist.mobile.data.upload

object ChunkRanges {
    /** OpenList v4.2.5 returns inclusive [first, last] ranges. */
    fun missing(totalChunks: Int, received: List<List<Int>>): List<Int> {
        require(totalChunks >= 0) { "totalChunks must not be negative" }
        if (totalChunks == 0) return emptyList()

        val intervals = received.mapNotNull { range ->
            if (range.size < 2 || range[0] > range[1]) return@mapNotNull null
            val first = range[0].coerceAtLeast(0)
            val last = range[1].coerceAtMost(totalChunks - 1)
            if (first > last) null else first..last
        }.sortedBy(IntRange::first)

        val missing = ArrayList<Int>()
        var next = 0
        for (interval in intervals) {
            if (interval.last < next) continue
            while (next < interval.first) missing += next++
            next = maxOf(next, interval.last + 1)
            if (next >= totalChunks) break
        }
        while (next < totalChunks) missing += next++
        return missing
    }

    fun contains(received: List<List<Int>>, index: Int): Boolean = received.any { range ->
        range.size >= 2 && range[0] <= index && index <= range[1]
    }
}
