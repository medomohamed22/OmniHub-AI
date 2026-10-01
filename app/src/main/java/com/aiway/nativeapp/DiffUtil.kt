package com.aiway.nativeapp

data class FileChange(val path: String, val status: Status, val added: Int, val removed: Int) {
    enum class Status { Added, Modified, Deleted }
}

data class DiffLine(val kind: Char, val text: String)

/** One agent run (or import): workspace before/after, used for review and undo. */
data class RunChanges(
    val before: Map<String, String>,
    val after: Map<String, String>,
    val changes: List<FileChange>
)

object DiffUtil {
    private fun lines(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split('\n')

    fun changes(before: Map<String, String>, after: Map<String, String>): List<FileChange> {
        val out = mutableListOf<FileChange>()
        (before.keys + after.keys).toSortedSet().forEach { p ->
            val b = before[p]
            val a = after[p]
            when {
                b == null && a != null -> out += FileChange(p, FileChange.Status.Added, lines(a).size, 0)
                b != null && a == null -> out += FileChange(p, FileChange.Status.Deleted, 0, lines(b).size)
                b != null && a != null && b != a -> {
                    val d = diff(b, a)
                    out += FileChange(p, FileChange.Status.Modified, d.count { it.kind == '+' }, d.count { it.kind == '-' })
                }
            }
        }
        return out
    }

    /** Line diff with up to 3 lines of context on each side of the changed region. */
    fun diff(old: String, new: String): List<DiffLine> {
        val a = lines(old)
        val b = lines(new)
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

        val res = mutableListOf<DiffLine>()
        for (i in maxOf(0, start - 3) until start) res += DiffLine(' ', a[i])

        val ma = a.subList(start, endA)
        val mb = b.subList(start, endB)
        val n = ma.size
        val m = mb.size
        if (n.toLong() * m.toLong() > 4_000_000L) {
            ma.forEach { res += DiffLine('-', it) }
            mb.forEach { res += DiffLine('+', it) }
        } else {
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                dp[i][j] = if (ma[i] == mb[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                if (ma[i] == mb[j]) { res += DiffLine(' ', ma[i]); i++; j++ }
                else if (dp[i + 1][j] >= dp[i][j + 1]) { res += DiffLine('-', ma[i]); i++ }
                else { res += DiffLine('+', mb[j]); j++ }
            }
            while (i < n) { res += DiffLine('-', ma[i]); i++ }
            while (j < m) { res += DiffLine('+', mb[j]); j++ }
        }

        for (i in endA until minOf(a.size, endA + 3)) res += DiffLine(' ', a[i])
        return res
    }
}
