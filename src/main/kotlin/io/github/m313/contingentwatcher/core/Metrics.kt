package io.github.m313.contingentwatcher.core

import io.github.m313.contingentwatcher.core.Time.DAY
import java.time.DayOfWeek

data class DailyUsage(
    val date: String,
    val used: Double,
    val weekend: Boolean,
    /** Before the first recorded data point (spread linearly). */
    val estimated: Boolean,
    /** Allowance was already empty at the start of that day. */
    val exhausted: Boolean,
)

/** Estimated monthly need at the observed pace (independent of the allowance cap). */
data class Demand(
    /** What the whole cycle would need at the pace observed so far. */
    val estimate: Double,
    /** estimate minus allowance (positive = allowance too small). */
    val overBy: Double,
    /** When the allowance ran out (ISO), if it did. */
    val exhaustedOn: String? = null,
)

data class UsageMetrics(
    val today: Double,
    val last7Days: Double,
    val cycleAverage: Double,
    val buffer: Double,
    val bufferDays: Double,
    val streakDays: Int,
    val daily: List<DailyUsage>,
    val planPerDay: Double,
    val demand: Demand?,
)

/** Usage curve: 0 at cycle start, the current value at `now`, linear in between. */
class UsageCurve(val start: Long, val firstReal: Long, private val pts: List<HistoryPoint>) {
    fun at(t: Long): Double {
        if (t <= pts[0].t) return 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (t <= b.t) {
                return if (b.t == a.t) b.used else a.used + (b.used - a.used) * (t - a.t).toDouble() / (b.t - a.t).toDouble()
            }
        }
        return pts.last().used
    }
}

object MetricsCalc {
    /** First time the usage curve reaches `level` (binary search; curve is monotonic). */
    private fun timeReaching(curve: UsageCurve, from: Long, to: Long, level: Double): Long? {
        if (curve.at(to) < level) return null
        var lo = from
        var hi = to
        var i = 0
        while (i < 40 && hi - lo > 60_000) {
            i++
            val mid = (lo + hi) / 2
            if (curve.at(mid) >= level) hi = mid else lo = mid
        }
        return hi
    }

    /**
     * Builds a usage curve from history: starts at 0 at cycle start, ends at the
     * current value, linearly interpolated in between. The stretch before the
     * first real data point is an estimate (plugin not yet installed/running).
     */
    fun usageCurve(snap: QuotaSnapshot, history: List<HistoryPoint>, now: Long): UsageCurve {
        val start = ForecastCalc.cycleStart(Time.parse(snap.resetDate) ?: now)
        val real = history.filter { it.t > start && it.t <= now }.sortedBy { it.t }
        val firstReal = real.firstOrNull()?.t ?: now
        val pts = ArrayList<HistoryPoint>()
        pts.add(HistoryPoint(start, 0.0))
        pts.addAll(real)
        pts.add(HistoryPoint(now, snap.used))
        // Usage never decreases within a cycle; guard against noisy data.
        for (i in 1 until pts.size) {
            pts[i] = HistoryPoint(pts[i].t, maxOf(pts[i].used, pts[i - 1].used))
        }
        return UsageCurve(start, firstReal, pts)
    }

    fun compute(snap: QuotaSnapshot, history: List<HistoryPoint>, opts: ForecastOptions, now: Long = System.currentTimeMillis()): UsageMetrics {
        val curve = usageCurve(snap, history, now)
        val start = curve.start
        val resetMs = Time.parse(snap.resetDate) ?: now

        val totalDays = maxOf(ForecastCalc.budgetDays(start, resetMs, opts.countWeekends), 1e-6)
        val elapsedDays = ForecastCalc.budgetDays(start, minOf(now, resetMs), opts.countWeekends)
        val planPerDay = snap.entitlement / totalDays

        val today = maxOf(0.0, snap.used - curve.at(maxOf(start, Time.localDayStart(now))))
        val last7Days = maxOf(0.0, snap.used - curve.at(maxOf(start, now - 7 * DAY)))
        val cycleAverage = snap.used / maxOf(1.0, elapsedDays)

        val expected = snap.entitlement * minOf(1.0, elapsedDays / totalDays)
        val buffer = expected - snap.used
        val bufferDays = if (planPerDay > 0) buffer / planPerDay else 0.0

        val daily = ArrayList<DailyUsage>()
        var d = Time.localDayStart(start)
        while (d <= now) {
            val next = Time.nextLocalDay(d)
            val from = maxOf(d, start)
            val to = minOf(next, now)
            val wd = Time.local(d).dayOfWeek
            daily.add(
                DailyUsage(
                    date = Time.localIsoDate(d),
                    used = maxOf(0.0, curve.at(to) - curve.at(from)),
                    weekend = wd == DayOfWeek.SATURDAY || wd == DayOfWeek.SUNDAY,
                    estimated = to <= curve.firstReal,
                    // Nothing left to spend from the start of this day on.
                    exhausted = snap.entitlement > 0 && curve.at(from) >= snap.entitlement - 1e-6,
                ),
            )
            d = next
        }

        // Consecutive finished days (newest first) within the plan, skipping
        // weekends when they don't count. Estimated days end the streak.
        var streakDays = 0
        for (i in daily.size - 2 downTo 0) {
            val day = daily[i]
            // An empty allowance isn't "staying within budget": it ends the streak.
            if (day.estimated || day.exhausted) break
            if (!opts.countWeekends && day.weekend && day.used == 0.0) continue
            if (day.used > planPerDay + 1e-9) break
            streakDays++
        }

        fun estimateDemand(): Demand? {
            if (snap.unlimited || snap.entitlement <= 0) return null
            var exhaustedOn: Long? = null
            val rate: Double? = when {
                // Paid overage: real usage keeps counting beyond the allowance.
                snap.overageCount > 0 -> if (elapsedDays >= 1) (snap.used + snap.overageCount) / elapsedDays else null
                snap.remaining <= 0 -> {
                    // Capped at the allowance: use the pace until it ran out.
                    exhaustedOn = timeReaching(curve, start, now, snap.entitlement - 1e-6)
                    val days = exhaustedOn?.let { ForecastCalc.budgetDays(start, it, opts.countWeekends) } ?: 0.0
                    if (days >= 0.5) snap.entitlement / days else null
                }
                else -> if (elapsedDays >= 1) snap.used / elapsedDays else null
            }
            if (rate == null) return null
            val estimate = maxOf(snap.used, rate * totalDays)
            return Demand(estimate, estimate - snap.entitlement, exhaustedOn?.let { Time.iso(it) })
        }

        return UsageMetrics(today, last7Days, cycleAverage, buffer, bufferDays, streakDays, daily, planPerDay, estimateDemand())
    }
}
