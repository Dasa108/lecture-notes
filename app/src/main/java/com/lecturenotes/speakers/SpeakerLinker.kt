package com.lecturenotes.speakers

import kotlin.math.sqrt

/** A stretch of audio attributed to one speaker; [speaker] is a global, 0-based id. */
data class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

/**
 * Long recordings are diarized in pieces, and each piece numbers its speakers from 0. This links
 * them into global speakers by comparing voice embeddings (cosine similarity to each global
 * speaker's running mean), so "speaker 0" in piece 3 is recognised as, say, global speaker 1.
 */
class SpeakerLinker(private val threshold: Float = DEFAULT_THRESHOLD) {
    private val centroids = mutableListOf<FloatArray>()
    private val counts = mutableListOf<Int>()

    val speakerCount: Int get() = centroids.size

    /**
     * Maps each local speaker of a piece (by index in [embeddings]) to a global id. Two local speakers
     * of the same piece never map to the same global speaker (they were already told apart).
     */
    fun link(embeddings: List<FloatArray>): List<Int> {
        val normed = embeddings.map(::normalize)
        // Greedy best-first assignment over all (local, global) pairs above the threshold.
        val pairs = normed.indices.flatMap { l -> centroids.indices.map { g -> Triple(l, g, cosine(normed[l], centroids[g])) } }
            .filter { it.third >= threshold }
            .sortedByDescending { it.third }
        val result = IntArray(normed.size) { -1 }
        val usedGlobal = HashSet<Int>()
        for ((l, g, _) in pairs) {
            if (result[l] != -1 || g in usedGlobal) continue
            result[l] = g
            usedGlobal += g
        }
        for (l in normed.indices) {
            if (result[l] == -1) {
                centroids += normed[l].copyOf()
                counts += 0
                result[l] = centroids.lastIndex
            }
            update(result[l], normed[l])
        }
        return result.toList()
    }

    private fun update(g: Int, e: FloatArray) {
        val n = counts[g]
        val c = centroids[g]
        if (n > 0) for (i in c.indices) c[i] = (c[i] * n + e[i]) / (n + 1)
        counts[g] = n + 1
        centroids[g] = normalize(c)
    }

    companion object {
        /** CAM++ cosine: same speaker is typically > 0.6, different speakers < 0.3. */
        const val DEFAULT_THRESHOLD = 0.5f

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot // inputs are unit length
        }

        fun normalize(v: FloatArray): FloatArray {
            var s = 0f
            for (x in v) s += x * x
            val n = sqrt(s).takeIf { it > 0f } ?: return v.copyOf()
            return FloatArray(v.size) { v[it] / n }
        }

        /**
         * The speaker of each transcript segment: whoever talks most during it. Returns null for a
         * segment no turn overlaps (e.g. Whisper text over silence).
         */
        fun assign(segments: List<LongRange>, turns: List<Turn>): List<Int?> {
            val sorted = turns.sortedBy { it.startMs }
            return segments.map { seg ->
                val overlap = HashMap<Int, Long>()
                for (t in sorted) {
                    if (t.startMs >= seg.last) break
                    val o = minOf(seg.last, t.endMs) - maxOf(seg.first, t.startMs)
                    if (o > 0) overlap[t.speaker] = (overlap[t.speaker] ?: 0) + o
                }
                overlap.maxByOrNull { it.value }?.key
            }
        }

        /** Joins back-to-back turns by the same speaker (e.g. split at a piece boundary). */
        fun mergeAdjacent(turns: List<Turn>, gapMs: Long = 1_000): List<Turn> {
            val out = mutableListOf<Turn>()
            for (t in turns.sortedBy { it.startMs }) {
                val last = out.lastOrNull()
                if (last != null && last.speaker == t.speaker && t.startMs - last.endMs <= gapMs) {
                    out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, t.endMs))
                } else {
                    out += t
                }
            }
            return out
        }

        /** Total talk time per global speaker. */
        fun talkTime(turns: List<Turn>): Map<Int, Long> =
            turns.groupBy { it.speaker }.mapValues { (_, ts) -> ts.sumOf { it.endMs - it.startMs } }
    }
}
