package io.github.m313.contingentwatcher

import io.github.m313.contingentwatcher.core.ForecastOptions
import io.github.m313.contingentwatcher.core.HistoryCompaction
import io.github.m313.contingentwatcher.core.HistoryPoint
import io.github.m313.contingentwatcher.core.MetricsCalc
import io.github.m313.contingentwatcher.core.Time
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsTest {
    private val opts = ForecastOptions(true, 0.0)

    @Test fun curveStartsAtZeroAndEndsAtCurrentUsage() {
        val now = dateUtc(2026, 10, 15, 12)
        val c = MetricsCalc.usageCurve(snap(100.0, now), emptyList(), now)
        assertEquals(dateUtc(2026, 10, 1), c.start)
        assertEquals(0.0, c.at(c.start), 0.0)
        assertEquals(100.0, c.at(now), 0.0)
    }

    @Test fun todayAndLast7DaysComeFromHistory() {
        val now = dateUtc(2026, 10, 20, 15)
        val dayStart = Time.localDayStart(now)
        val history = listOf(
            HistoryPoint(now - 10 * DAY, 50.0),
            HistoryPoint(now - 7 * DAY, 80.0),
            HistoryPoint(dayStart - HOUR, 140.0),
            HistoryPoint(dayStart + HOUR, 140.0),
        )
        val m = MetricsCalc.compute(snap(150.0, now), history, opts, now)
        close(m.today, 10.0)
        close(m.last7Days, 70.0)
    }

    @Test fun bufferSign() {
        val now = dateUtc(2026, 10, 16)
        val below = MetricsCalc.compute(snap(100.0, now), emptyList(), opts, now)
        close(below.buffer, 50.0)
        close(below.bufferDays, 5.0)
        close(MetricsCalc.compute(snap(200.0, now), emptyList(), opts, now).buffer, -50.0)
    }

    @Test fun dailyBucketsCoverCycle() {
        val now = dateUtc(2026, 10, 10, 12)
        val history = listOf(HistoryPoint(dateUtc(2026, 10, 8, 12), 60.0), HistoryPoint(dateUtc(2026, 10, 9, 12), 70.0))
        val m = MetricsCalc.compute(snap(90.0, now), history, opts, now)
        close(m.daily.sumOf { it.used }, 90.0)
        assertTrue(m.daily.first().estimated)
        assertFalse(m.daily.last().estimated)
        assertTrue(m.daily.size in 10..11)
    }

    @Test fun streakCountsFinishedDaysWithinPlan() {
        val now = dateUtc(2026, 10, 10, 12)
        val base = dateUtc(2026, 10, 6, 0, 30)
        val history = (0..4).map { HistoryPoint(base + it * DAY, 100.0 + it * 5) }
        val m = MetricsCalc.compute(snap(122.0, now), history, opts, now)
        assertTrue("streak ${m.streakDays}", m.streakDays in 2..4)
    }

    @Test fun compactionThinsOldPoints() {
        val now = dateUtc(2026, 10, 20)
        val old = (0 until 6).map { HistoryPoint(now - 5 * DAY + it * 10 * 60_000L, it.toDouble()) }
        val recent = (0 until 6).map { HistoryPoint(now - HOUR + it * 5 * 60_000L, 10.0 + it) }
        val out = HistoryCompaction.compact(old + recent, now)
        assertEquals(1, out.count { it.t < now - 2 * DAY })
        assertEquals(6, out.count { it.t >= now - 2 * DAY })
        assertEquals(5.0, out.first { it.t < now - 2 * DAY }.used, 0.0)
    }

    @Test fun demandExtrapolatesPace() {
        val now = dateUtc(2026, 10, 16)
        val d = MetricsCalc.compute(snap(200.0, now), emptyList(), opts, now).demand
        assertNotNull(d)
        close(d!!.estimate, 400.0)
        close(d.overBy, 100.0)
        assertNull(d.exhaustedOn)
    }

    @Test fun demandUsesPaceUntilAllowanceRanOut() {
        val now = dateUtc(2026, 10, 25)
        val history = listOf(
            HistoryPoint(dateUtc(2026, 10, 6), 150.0),
            HistoryPoint(dateUtc(2026, 10, 11), 300.0),
            HistoryPoint(dateUtc(2026, 10, 20), 300.0),
        )
        val d = MetricsCalc.compute(snap(300.0, now), history, opts, now).demand!!
        assertTrue("estimate ${d.estimate}", Math.abs(d.estimate - 900) < 5)
        assertTrue(d.exhaustedOn!!.startsWith("2026-11-1"))
    }

    @Test fun demandCountsPaidOverage() {
        val now = dateUtc(2026, 10, 16)
        val d = MetricsCalc.compute(snap(300.0, now, overagePermitted = true, overageCount = 60.0), emptyList(), opts, now).demand!!
        close(d.estimate, 720.0)
    }

    @Test fun streakEndsWhenAllowanceEmpty() {
        val now = dateUtc(2026, 10, 25, 12)
        val history = listOf(
            HistoryPoint(dateUtc(2026, 10, 10, 12), 299.0),
            HistoryPoint(dateUtc(2026, 10, 11, 12), 300.0),
            HistoryPoint(dateUtc(2026, 10, 24, 12), 300.0),
        )
        val m = MetricsCalc.compute(snap(300.0, now), history, opts, now)
        assertEquals(0, m.streakDays)
        assertTrue(m.daily.count { it.exhausted } >= 10)
    }
}
