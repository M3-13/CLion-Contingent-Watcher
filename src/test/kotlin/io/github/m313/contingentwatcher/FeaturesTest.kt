package io.github.m313.contingentwatcher

import io.github.m313.contingentwatcher.core.ActivityBook
import io.github.m313.contingentwatcher.core.ActivityKey
import io.github.m313.contingentwatcher.core.Appearance
import io.github.m313.contingentwatcher.core.Cycles
import io.github.m313.contingentwatcher.core.ForecastCalc
import io.github.m313.contingentwatcher.core.ForecastOptions
import io.github.m313.contingentwatcher.core.HistoryPoint
import io.github.m313.contingentwatcher.core.IconStyle
import io.github.m313.contingentwatcher.core.PaceStatus
import io.github.m313.contingentwatcher.core.Surplus
import io.github.m313.contingentwatcher.core.TIPS
import io.github.m313.contingentwatcher.core.Tips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
    private val opts = ForecastOptions(true, 0.0)

    @Test fun surplusShownInLastWeek() {
        val now = dateUtc(2026, 10, 27)
        val s = snap(100.0, now)
        val unused = Surplus.expected(s, ForecastCalc.compute(s, emptyList(), opts, now), now)
        assertTrue(unused != null && unused > 100)
    }

    @Test fun surplusNotShownEarlyOrOnPlan() {
        val early = dateUtc(2026, 10, 10)
        val s1 = snap(10.0, early)
        assertNull(Surplus.expected(s1, ForecastCalc.compute(s1, emptyList(), opts, early), early))
        val late = dateUtc(2026, 10, 27)
        val s2 = snap(270.0, late)
        assertNull(Surplus.expected(s2, ForecastCalc.compute(s2, emptyList(), opts, late), late))
    }

    @Test fun cycleSummaryAndAddCycle() {
        val now = dateUtc(2026, 10, 30, 20)
        val history = listOf(
            HistoryPoint(dateUtc(2026, 10, 28, 1), 100.0),
            HistoryPoint(dateUtc(2026, 10, 28, 23), 140.0),
            HistoryPoint(dateUtc(2026, 10, 29, 23), 150.0),
        )
        val sum = Cycles.summarize(snap(150.0, now), history, opts, now)
        assertEquals(150.0, sum.used, 0.0)
        assertTrue(sum.peakDay != null && sum.peakDay!!.used >= 30)
        assertTrue(sum.activeDays >= 1)
        val older = sum.copy(resetDate = "2026-11-01T00:00:00.000Z")
        val list = Cycles.add(Cycles.add(listOf(older), sum), sum.copy(used = 151.0))
        assertEquals(2, list.size)
        assertEquals(151.0, list[0].used, 0.0)
    }

    @Test fun activityBranchesAndDays() {
        val t0 = dateLocal(2026, 10, 20, 10)
        var items = ActivityBook.addActiveTime(emptyList(), ActivityKey("app", "feature/login"), t0, 30_000, "Add login form")
        items = ActivityBook.addActiveTime(items, ActivityKey("app", "feature/login"), t0 + DAY, 30_000)
        assertEquals(1, items.size)
        assertEquals(60_000L, items[0].activeMs)
        assertEquals("Add login form", items[0].detail)
        items = ActivityBook.addActiveTime(items, ActivityKey("app", "main"), t0, 30_000)
        items = ActivityBook.addActiveTime(items, ActivityKey("app", "main"), t0 + DAY, 30_000)
        assertEquals(2, items.count { it.branch == "main" })
        assertNotEquals(ActivityBook.itemId(ActivityKey("app", "main"), t0), ActivityBook.itemId(ActivityKey("app", "main"), t0 + DAY))
    }

    @Test fun activityUsageAndActiveFlag() {
        val t0 = dateLocal(2026, 10, 20, 10)
        val key = ActivityKey("app", "feature/x")
        var items = ActivityBook.addUsage(emptyList(), key, t0, 3.0)
        items = ActivityBook.addUsage(items, key, t0, 0.0)
        items = ActivityBook.addUsage(items, key, t0, -2.0)
        assertEquals(3.0, items[0].used, 0.0)
        assertTrue(ActivityBook.isActive(items[0], key, t0 + 60_000))
        assertFalse(ActivityBook.isActive(items[0], key, t0 + 60 * 60_000))
        assertFalse(ActivityBook.isActive(items[0], ActivityKey("app", "other"), t0))
    }

    @Test fun appearance() {
        assertNull(Appearance.resolveColor("auto"))
        assertEquals("#14b8a6", Appearance.resolveColor("teal"))
        assertEquals("#ABC", Appearance.resolveColor("#ABC"))
        assertNull(Appearance.resolveColor("red; injection"))
        assertEquals(4, Appearance.fillLevel(300.0, 300.0))
        assertEquals(1, Appearance.fillLevel(1.0, 300.0))
        assertEquals(0, Appearance.fillLevel(0.0, 300.0))
        assertEquals("contingent-level-3", Appearance.iconFor(IconStyle.GAUGE, 3))
        assertEquals("contingent-battery-2", Appearance.iconFor(IconStyle.BATTERY, 2))
        assertNull(Appearance.iconFor(IconStyle.NONE, 2))
        assertEquals("#aabbcc", Appearance.hex6("#ABC"))
    }

    @Test fun tipIdsUnique() {
        assertEquals(TIPS.size, TIPS.map { it.id }.toSet().size)
        TIPS.forEach { assertTrue(it.id, it.`when`.isNotEmpty()) }
    }

    @Test fun situations() {
        assertEquals(listOf("surplus", "onTrack", "lateMonth"), Tips.situationsFor(Tips.SituationInput(PaceStatus.ON_TRACK, 0.9, true)))
        assertEquals(listOf("ahead", "earlyMonth"), Tips.situationsFor(Tips.SituationInput(PaceStatus.AHEAD, 0.1, false)))
        assertEquals(listOf("critical"), Tips.situationsFor(Tips.SituationInput(PaceStatus.CRITICAL, 0.5, false)))
    }

    @Test fun relevantTipsBounds() {
        for (s in listOf("onTrack", "ahead", "critical", "exhausted", "unlimited", "surplus")) {
            for (seed in 0 until 31) {
                val tips = Tips.relevant(listOf(s), seed.toDouble())
                assertTrue("$s: ${tips.size}", tips.size in 3..5)
                assertEquals(tips.size, tips.map { it.id }.toSet().size)
                if (TIPS.any { s in it.`when` }) assertTrue("$s seed $seed", s in tips[0].`when`)
            }
        }
    }

    @Test fun relevantTipsDeterministicAndRotating() {
        assertEquals(Tips.relevant(listOf("ahead"), 4.0).map { it.id }, Tips.relevant(listOf("ahead"), 4.0).map { it.id })
        assertTrue((0 until 31).map { Tips.relevant(listOf("general"), it.toDouble())[0].id }.toSet().size > 1)
        assertTrue(Tips.pick(listOf("critical"), 2.0).id.isNotEmpty())
        assertTrue(Tips.pick(listOf("ahead"), -7.5).id.isNotEmpty())
    }
}
