package io.github.m313.contingentwatcher.core

import io.github.m313.contingentwatcher.core.Time.DAY
import java.time.DayOfWeek

object ForecastCalc {
    /** Window used to compute the recent burn rate. */
    private const val RECENT_WINDOW_DAYS = 7

    private fun isWeekend(t: Long): Boolean {
        val wd = Time.utc(t).dayOfWeek
        return wd == DayOfWeek.SATURDAY || wd == DayOfWeek.SUNDAY
    }

    /** Cycle start = reset date minus one calendar month (UTC), with JavaScript Date overflow semantics. */
    fun cycleStart(reset: Long): Long {
        val d = Time.utc(reset)
        return Time.utcDate(d.year, d.monthValue - 1, d.dayOfMonth)
    }

    /**
     * Budget days between [from, to): a day counts fully, the current day counts
     * with its remaining fraction. Weekends are skipped unless countWeekends.
     */
    fun budgetDays(from: Long, to: Long, countWeekends: Boolean): Double {
        if (to <= from) return 0.0
        var total = 0.0
        var day = Time.startOfUtcDay(from)
        while (day < to) {
            if (countWeekends || !isWeekend(day)) {
                val start = maxOf(day, from)
                val end = minOf(day + DAY, to)
                total += maxOf(0L, end - start).toDouble() / DAY
            }
            day += DAY
        }
        return total
    }

    /** Adds n budget days to t (skipping weekends if requested). */
    fun addBudgetDays(t: Long, n: Double, countWeekends: Boolean): Long {
        var cursor = t
        var left = n
        // Hard cap to avoid infinite loops on bad input.
        var i = 0
        while (i < 400 && left > 0) {
            i++
            val dayEnd = Time.startOfUtcDay(cursor) + DAY
            if (!countWeekends && isWeekend(cursor)) {
                cursor = dayEnd
                continue
            }
            val avail = (dayEnd - cursor).toDouble() / DAY
            if (left <= avail) {
                return cursor + Math.round(left * DAY)
            }
            left -= avail
            cursor = dayEnd
        }
        return cursor
    }

    /** Units per budget day over the recent window, from history points. */
    fun recentBurnRate(history: List<HistoryPoint>, now: Long, cycleStartMs: Long, countWeekends: Boolean): Double? {
        val windowStart = maxOf(cycleStartMs, now - RECENT_WINDOW_DAYS * DAY)
        val pts = history.filter { it.t in windowStart..now }.sortedBy { it.t }
        if (pts.size < 2) return null
        val first = pts.first()
        val last = pts.last()
        val days = budgetDays(first.t, last.t, countWeekends)
        // Need at least ~half a budget day of data to be meaningful.
        if (days < 0.5) return null
        return maxOf(0.0, last.used - first.used) / days
    }

    fun compute(snap: QuotaSnapshot, history: List<HistoryPoint>, opts: ForecastOptions, now: Long = System.currentTimeMillis()): Forecast {
        val resetMs = Time.parse(snap.resetDate) ?: now
        val startMs = cycleStart(resetMs)

        val totalDays = maxOf(budgetDays(startMs, resetMs, opts.countWeekends), 1e-6)
        val elapsedDays = budgetDays(startMs, minOf(now, resetMs), opts.countWeekends)
        val daysLeft = budgetDays(maxOf(now, startMs), resetMs, opts.countWeekends)
        val cycleElapsed = minOf(1.0, elapsedDays / totalDays)

        val usedPercent = if (snap.entitlement > 0) snap.used / snap.entitlement * 100 else 0.0
        val expectedUsed = snap.entitlement * cycleElapsed
        val paceRatio = when {
            expectedUsed > 0 -> snap.used / expectedUsed
            snap.used > 0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }

        val spendable = maxOf(0.0, snap.remaining - opts.reserve)
        val dailyBudget = if (daysLeft > 0) spendable / maxOf(daysLeft, 1.0) else 0.0

        val average = if (elapsedDays >= 1) snap.used / elapsedDays else null
        val burnRate = recentBurnRate(history, now, startMs, opts.countWeekends) ?: average ?: 0.0
        val projectedUsed = snap.used + burnRate * daysLeft

        var exhaustion: Long? = null
        if (burnRate > 0 && projectedUsed > snap.entitlement && snap.remaining > 0) {
            exhaustion = addBudgetDays(now, snap.remaining / burnRate, opts.countWeekends)
        }

        val status = when {
            snap.unlimited -> PaceStatus.UNLIMITED
            snap.remaining <= 0 -> PaceStatus.EXHAUSTED
            exhaustion != null && exhaustion < resetMs - 3 * DAY -> PaceStatus.CRITICAL
            paceRatio > 1.15 && snap.used >= 5 -> PaceStatus.AHEAD
            else -> PaceStatus.ON_TRACK
        }

        return Forecast(usedPercent, cycleElapsed, paceRatio, daysLeft, dailyBudget, burnRate, projectedUsed, exhaustion, status)
    }
}
