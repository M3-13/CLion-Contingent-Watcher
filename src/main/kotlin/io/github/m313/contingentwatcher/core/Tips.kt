package io.github.m313.contingentwatcher.core

/**
 * A tip and the situations it is relevant for: the pace status ids plus
 * "surplus", "earlyMonth", "lateMonth" and "general" (always useful).
 */
data class Tip(
    val id: String,
    val `when`: List<String>,
    /** English source string; translated via L10n at render time. */
    val text: String,
)

object Tips {
    data class SituationInput(val status: PaceStatus, val cycleElapsed: Double, val surplus: Boolean)

    /** Derives the active situations (most specific first). */
    fun situationsFor(i: SituationInput): List<String> {
        val out = ArrayList<String>()
        if (i.surplus) out.add("surplus")
        out.add(i.status.id)
        if (i.cycleElapsed < 0.25) out.add("earlyMonth") else if (i.cycleElapsed > 0.75) out.add("lateMonth")
        return out
    }

    private fun <T> rotate(list: List<T>, seed: Double): List<T> {
        if (list.isEmpty()) return list
        val k = (Math.abs(Math.floor(seed)) % list.size).toInt()
        return list.drop(k) + list.take(k)
    }

    /**
     * The tips relevant right now: specific ones for the active situations first
     * (rotating daily via seed), filled up with general tips. Between `min` and `max`.
     */
    fun relevant(situations: List<String>, seed: Double, max: Int = 5, min: Int = 3, tips: List<Tip> = TIPS): List<Tip> {
        val picked = ArrayList<Tip>()
        fun add(t: Tip) {
            if (picked.size < max && picked.none { it.id == t.id }) picked.add(t)
        }
        // Most specific situation first, at most 2 tips per situation so several show up.
        val perSituation = situations.map { s -> rotate(tips.filter { s in it.`when` }, seed) }
        repeat(2) {
            for (list in perSituation) {
                list.firstOrNull { t -> picked.none { it.id == t.id } }?.let { add(it) }
            }
        }
        for (t in rotate(tips.filter { "general" in it.`when` }, seed)) {
            if (picked.size >= min) break
            add(t)
        }
        return picked
    }

    /** One tip for compact places (status bar tooltip). */
    fun pick(situations: List<String>, seed: Double): Tip = relevant(situations, seed, 1, 1).firstOrNull() ?: TIPS[0]
}
