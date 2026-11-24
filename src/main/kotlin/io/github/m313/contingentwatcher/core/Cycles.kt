package io.github.m313.contingentwatcher.core

data class PeakDay(val date: String, val used: Double)

/** Compact summary of one billing cycle, archived when a new cycle starts. */
data class CycleSummary(
    /** Reset date (ISO) that ended this cycle; identifies the cycle. */
    val resetDate: String,
    val used: Double,
    val entitlement: Double,
    val unit: String,
    val activeDays: Int,
    val avgPerActiveDay: Double,
    val peakDay: PeakDay? = null,
    /** Estimated need for the whole cycle at the observed pace. */
    val demand: Double? = null,
)

object Cycles {
    const val MAX_CYCLES = 12

    /** Summarises a cycle from its last snapshot and recorded history. */
    fun summarize(snap: QuotaSnapshot, history: List<HistoryPoint>, opts: ForecastOptions, now: Long): CycleSummary {
        val m = MetricsCalc.compute(snap, history, opts, now)
        val real = m.daily.filter { !it.estimated }
        val active = real.filter { it.used >= 0.5 }
        var peak: PeakDay? = null
        for (d in real) {
            if (d.used > (peak?.used ?: 0.0)) peak = PeakDay(d.date, d.used)
        }
        return CycleSummary(
            resetDate = snap.resetDate,
            used = snap.used,
            entitlement = snap.entitlement,
            unit = snap.unit.id,
            activeDays = active.size,
            avgPerActiveDay = if (active.isNotEmpty()) active.sumOf { it.used } / active.size else 0.0,
            peakDay = peak?.let { PeakDay(it.date, Math.round(it.used).toDouble()) },
            demand = m.demand?.let { Math.round(it.estimate).toDouble() },
        )
    }

    /** Adds a summary, replacing one for the same cycle, newest first, capped. */
    fun add(cycles: List<CycleSummary>, c: CycleSummary): List<CycleSummary> {
        val key = c.resetDate.take(10)
        val rest = cycles.filter { it.resetDate.take(10) != key }
        return (listOf(c) + rest).sortedByDescending { it.resetDate }.take(MAX_CYCLES)
    }
}

object HistoryCompaction {
    private const val MAX_POINTS = 2000

    /** Keeps full resolution for the last 48h, one point per hour before that. */
    fun compact(history: List<HistoryPoint>, now: Long): List<HistoryPoint> {
        val cutoff = now - 48 * Time.HOUR
        val out = ArrayList<HistoryPoint>()
        for (p in history) {
            val prev = out.lastOrNull()
            if (prev != null && p.t < cutoff && Math.floorDiv(prev.t, Time.HOUR) == Math.floorDiv(p.t, Time.HOUR)) {
                out[out.size - 1] = p
            } else {
                out.add(p)
            }
        }
        return if (out.size > MAX_POINTS) out.subList(out.size - MAX_POINTS, out.size).toList() else out
    }
}

object Thresholds {
    /**
     * Returns the highest threshold that was newly crossed, or null.
     * `alreadyNotified` holds thresholds already shown in this cycle.
     */
    fun newlyCrossed(usedPercent: Double, thresholds: List<Double>, alreadyNotified: List<Double>): Double? =
        thresholds.toSet().filter { it > 0 && usedPercent >= it && it !in alreadyNotified }.maxOrNull()

    /** Stable key identifying a billing cycle, used to reset notification state. */
    fun cycleKey(resetDate: String): String = resetDate.take(10)
}

object Surplus {
    /** Days before reset in which the "use it or lose it" hint may appear. */
    const val WINDOW_DAYS = 7

    /**
     * Expected unused allowance at reset, if it is worth mentioning: only in the
     * last week of the cycle and when at least 10 % (min. 10) would expire.
     */
    fun expected(snap: QuotaSnapshot, f: Forecast, now: Long = System.currentTimeMillis()): Double? {
        if (snap.unlimited || snap.entitlement <= 0) return null
        val untilReset = (Time.parse(snap.resetDate) ?: return null) - now
        if (untilReset <= 0 || untilReset > WINDOW_DAYS * Time.DAY) return null
        val unused = snap.entitlement - f.projectedUsed
        val threshold = maxOf(10.0, snap.entitlement * 0.1)
        return if (unused >= threshold) Math.floor(unused) else null
    }
}

object Endpoints {
    /**
     * Maps a GitHub host to its REST API base URL.
     * - github.com            → https://api.github.com
     * - <tenant>.ghe.com      → https://api.<tenant>.ghe.com   (data residency)
     * - other (GHE Server)    → https://<host>/api/v3
     */
    fun apiBaseForHost(host: String): String {
        val h = host.lowercase().removePrefix("api.")
        return when {
            h == "github.com" -> "https://api.github.com"
            h.endsWith(".ghe.com") -> "https://api.$h"
            else -> "https://$h/api/v3"
        }
    }

    /** Normalises user input like "acme.ghe.com" or "https://github.acme.corp/" to an origin, or null. */
    fun normaliseHost(input: String?): String? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            val uri = java.net.URI(if (raw.contains("://")) raw else "https://$raw")
            val host = uri.host ?: return null
            if (host.isBlank()) null else "https://$host" + (if (uri.port > 0) ":${uri.port}" else "")
        } catch (_: Exception) {
            null
        }
    }

    /** Host part of an origin or address. */
    fun hostOf(input: String?): String? = normaliseHost(input)?.removePrefix("https://")
}
