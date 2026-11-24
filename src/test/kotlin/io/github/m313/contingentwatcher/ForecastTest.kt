package io.github.m313.contingentwatcher

import io.github.m313.contingentwatcher.core.ForecastCalc
import io.github.m313.contingentwatcher.core.ForecastOptions
import io.github.m313.contingentwatcher.core.HistoryPoint
import io.github.m313.contingentwatcher.core.PaceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastTest {
    private fun utc(m: Int, d: Int, h: Int = 0) = dateUtc(2026, m - 1, d, h)
    private val nov1 = utc(11, 1)
    private val dec1 = utc(12, 1)
    private val now = utc(11, 16) // Monday, halfway (calendar days)
    private val opts = ForecastOptions(true, 0.0)

    @Test fun cycleStartIsOneMonthBeforeReset() {
        assertEquals(nov1, ForecastCalc.cycleStart(dec1))
        assertEquals(dateUtc(2026, 11, 1), ForecastCalc.cycleStart(dateUtc(2027, 0, 1)))
    }

    @Test fun budgetDaysWithAndWithoutWeekends() {
        assertEquals(30.0, ForecastCalc.budgetDays(nov1, dec1, true), 0.0)
        assertEquals(21.0, ForecastCalc.budgetDays(nov1, dec1, false), 0.0)
        assertEquals(0.0, ForecastCalc.budgetDays(dec1, nov1, true), 0.0)
        assertEquals(0.0, ForecastCalc.budgetDays(nov1, nov1, true), 0.0)
    }

    @Test fun budgetDaysCountsPartialDays() {
        assertEquals(1.0, ForecastCalc.budgetDays(utc(11, 6, 12), utc(11, 9, 12), false), 0.0)
        assertEquals(3.0, ForecastCalc.budgetDays(utc(11, 6, 12), utc(11, 9, 12), true), 0.0)
        assertEquals(0.0, ForecastCalc.budgetDays(utc(11, 7, 6), utc(11, 8, 18), false), 0.0)
    }

    @Test fun addBudgetDaysSkipsWeekends() {
        assertEquals(utc(11, 9, 12), ForecastCalc.addBudgetDays(utc(11, 6, 12), 1.0, false))
        assertEquals(utc(11, 7, 12), ForecastCalc.addBudgetDays(utc(11, 6, 12), 1.0, true))
        assertEquals(utc(11, 10, 0), ForecastCalc.addBudgetDays(utc(11, 7, 10), 1.0, false))
        assertEquals(utc(11, 23, 12), ForecastCalc.addBudgetDays(utc(11, 16), 7.5, true))
    }

    @Test fun onTrack() {
        val f = ForecastCalc.compute(snap(140.0, now), emptyList(), opts, now)
        assertEquals(PaceStatus.ON_TRACK, f.status)
        close(f.cycleElapsed, 0.5)
        close(f.daysLeft, 15.0)
        close(f.usedPercent, 140.0 / 300 * 100)
        close(f.paceRatio, 140.0 / 150)
        close(f.burnRate, 140.0 / 15)
        close(f.projectedUsed, 280.0)
        close(f.dailyBudget, 160.0 / 15)
        assertNull(f.exhaustionDate)
    }

    @Test fun aheadWithoutEarlyExhaustion() {
        val hist = listOf(HistoryPoint(utc(11, 9), 178.0), HistoryPoint(utc(11, 16), 180.0))
        val f = ForecastCalc.compute(snap(180.0, now), hist, opts, now)
        assertEquals(PaceStatus.AHEAD, f.status)
        close(f.paceRatio, 1.2)
        assertNull(f.exhaustionDate)
    }

    @Test fun criticalWithExhaustionBeforeReset() {
        val f = ForecastCalc.compute(snap(200.0, now), emptyList(), opts, now)
        assertEquals(PaceStatus.CRITICAL, f.status)
        assertEquals(utc(11, 23, 12), f.exhaustionDate)
        assertTrue(f.exhaustionDate!! < dec1 - 3 * DAY)
    }

    @Test fun exhausted() {
        val f = ForecastCalc.compute(snap(300.0, now), emptyList(), opts, now)
        assertEquals(PaceStatus.EXHAUSTED, f.status)
        assertEquals(0.0, f.dailyBudget, 0.0)
        assertNull(f.exhaustionDate)
    }

    @Test fun unlimited() {
        val f = ForecastCalc.compute(snap(0.0, now, entitlement = 0.0, unlimited = true, remaining = 0.0), emptyList(), opts, now)
        assertEquals(PaceStatus.UNLIMITED, f.status)
        assertEquals(0.0, f.usedPercent, 0.0)
    }

    @Test fun reserveReducesDailyBudget() {
        close(ForecastCalc.compute(snap(140.0, now), emptyList(), opts, now).dailyBudget, 160.0 / 15)
        close(ForecastCalc.compute(snap(140.0, now), emptyList(), ForecastOptions(true, 40.0), now).dailyBudget, 120.0 / 15)
        assertEquals(0.0, ForecastCalc.compute(snap(140.0, now), emptyList(), ForecastOptions(true, 1000.0), now).dailyBudget, 0.0)
    }

    @Test fun recentBurnRateOverridesAverage() {
        val hist = listOf(HistoryPoint(utc(11, 9), 130.0), HistoryPoint(utc(11, 16), 200.0))
        val f = ForecastCalc.compute(snap(200.0, now), hist, opts, now)
        close(f.burnRate, 10.0)
        close(f.projectedUsed, 200.0 + 10 * 15)
    }

    @Test fun singleHistoryPointFallsBackToAverage() {
        close(ForecastCalc.compute(snap(150.0, now), listOf(HistoryPoint(utc(11, 10), 100.0)), opts, now).burnRate, 10.0)
    }

    @Test fun weekdaysOnlyChangesDaysLeft() {
        val f = ForecastCalc.compute(snap(100.0, now), emptyList(), ForecastOptions(false, 0.0), now)
        close(f.daysLeft, 11.0)
        close(f.cycleElapsed, 10.0 / 21)
    }

    @Test fun startOfCycleIsOnTrack() {
        val f = ForecastCalc.compute(snap(0.0, nov1), emptyList(), opts, nov1)
        assertEquals(PaceStatus.ON_TRACK, f.status)
        assertEquals(0.0, f.paceRatio, 0.0)
        assertEquals(0.0, f.burnRate, 0.0)
        assertNotNull(f)
    }
}
