package com.lecturenotes.transcribe

/** Find-and-replace over transcript lines, e.g. fixing a term speech recognition always mishears. */
object TranscriptReplace {
    /** null if [find] is blank. */
    fun pattern(find: String, wholeWord: Boolean, matchCase: Boolean): Regex? {
        val f = find.trim().ifEmpty { return null }
        val body = Regex.escape(f)
        // Word boundaries only where the search term itself starts/ends with a word character.
        val start = if (wholeWord && f.first().isLetterOrDigit()) "(?<![\\p{L}\\p{N}])" else ""
        val end = if (wholeWord && f.last().isLetterOrDigit()) "(?![\\p{L}\\p{N}])" else ""
        return Regex(start + body + end, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }

    fun count(lines: List<String>, regex: Regex): Pair<Int, Int> {
        var matches = 0
        var hitLines = 0
        lines.forEach { l -> regex.findAll(l).count().let { if (it > 0) { matches += it; hitLines++ } } }
        return matches to hitLines
    }

    /** The new text for every line that changes. */
    fun <K> apply(lines: Map<K, String>, regex: Regex, replacement: String): Map<K, String> =
        lines.mapValues { (_, t) -> regex.replace(t, Regex.escapeReplacement(replacement)) }.filter { (k, t) -> t != lines[k] }
}
