package io.github.m313.contingentwatcher

import io.github.m313.contingentwatcher.core.BillingParser
import io.github.m313.contingentwatcher.core.Endpoints
import io.github.m313.contingentwatcher.core.QuotaParser
import io.github.m313.contingentwatcher.core.QuotaUnit
import io.github.m313.contingentwatcher.core.Thresholds
import io.github.m313.contingentwatcher.core.UnitPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    private val now = dateUtc(2026, 10, 16, 12)

    @Test fun parsesTypicalResponse() {
        val s = QuotaParser.parseCopilotUser(
            json(
                """{"copilot_plan":"individual","quota_reset_date":"2026-12-01","quota_snapshots":{"chat":{"unlimited":true},
                "completions":{"unlimited":true},"premium_interactions":{"entitlement":300,"remaining":120,"percent_remaining":40,
                "unlimited":false,"overage_permitted":true,"overage_count":0}}}""",
            ),
            now,
        )!!
        assertEquals(300.0, s.entitlement, 0.0)
        assertEquals(120.0, s.remaining, 0.0)
        assertEquals(180.0, s.used, 0.0)
        assertEquals(false, s.unlimited)
        assertEquals(true, s.overagePermitted)
        assertEquals("2026-12-01T00:00:00.000Z", s.resetDate)
        assertEquals(QuotaUnit.REQUESTS, s.unit)
        assertEquals("premium_interactions", s.quotaId)
        assertEquals("individual", s.plan)
        assertEquals(now, s.fetchedAt)
    }

    @Test fun derivesRemainingFromPercent() {
        val s = QuotaParser.parseCopilotUser(json("""{"quota_snapshots":{"premium_interactions":{"entitlement":300,"percent_remaining":40}}}"""), now)!!
        assertEquals(120.0, s.remaining, 1e-9)
        assertEquals(180.0, s.used, 1e-9)
    }

    @Test fun clampsNegativeRemaining() {
        val s = QuotaParser.parseCopilotUser(json("""{"quota_snapshots":{"premium_interactions":{"entitlement":300,"remaining":-25}}}"""), now)!!
        assertEquals(0.0, s.remaining, 0.0)
        assertEquals(300.0, s.used, 0.0)
    }

    @Test fun creditKeyUsesCredits() {
        val s = QuotaParser.parseCopilotUser(json("""{"quota_snapshots":{"monthly_credit_pool":{"entitlement":1000,"remaining":900}}}"""), now)!!
        assertEquals(QuotaUnit.CREDITS, s.unit)
        assertEquals("monthly_credit_pool", s.quotaId)
        assertEquals(100.0, s.used, 0.0)
    }

    @Test fun preferredKeyWins() {
        val s = QuotaParser.parseCopilotUser(json("""{"quota_snapshots":{"other":{"entitlement":5,"remaining":5},"premium_interactions":{"entitlement":300,"remaining":300}}}"""), now)
        assertEquals("premium_interactions", s?.quotaId)
    }

    @Test fun resetDateFallbacks() {
        val s = QuotaParser.parseCopilotUser(json("""{"quota_snapshots":{"premium_interactions":{"entitlement":300,"remaining":1}}}"""), now)
        assertEquals("2026-12-01T00:00:00.000Z", s?.resetDate)
        assertEquals("2027-01-01T00:00:00.000Z", QuotaParser.defaultResetDate(dateUtc(2026, 11, 31, 23, 59)))
        val bad = QuotaParser.parseCopilotUser(json("""{"quota_reset_date":"nonsense","quota_snapshots":{"premium_interactions":{"entitlement":1,"remaining":1}}}"""), now)
        assertEquals("2026-12-01T00:00:00.000Z", bad?.resetDate)
        val utc = QuotaParser.parseCopilotUser(
            json("""{"quota_reset_date_utc":"2027-02-01T00:00:00.000Z","quota_reset_date":"2026-12-01","quota_snapshots":{"premium_interactions":{"entitlement":1,"remaining":1}}}"""),
            now,
        )
        assertEquals("2027-02-01T00:00:00.000Z", utc?.resetDate)
    }

    @Test fun garbageReturnsNull() {
        for (input in listOf("null", "\"x\"", "42", "{}", """{"quota_snapshots":{}}""", """{"quota_snapshots":{"chat":{"unlimited":true}}}""")) {
            assertNull(input, QuotaParser.parseCopilotUser(json(input), now))
        }
        assertNull(QuotaParser.parseCopilotUser(null, now))
    }

    @Test fun unlimitedPlan() {
        val s = QuotaParser.parseCopilotUser(json("""{"copilot_plan":"enterprise","quota_snapshots":{"premium_interactions":{"unlimited":true}}}"""), now)!!
        assertTrue(s.unlimited)
        assertEquals(0.0, s.used, 0.0)
        assertEquals("enterprise", s.plan)
    }

    @Test fun numericStrings() {
        assertEquals(200.0, QuotaParser.parseCopilotUser(json("""{"quota_snapshots":{"premium_interactions":{"entitlement":"300","remaining":"100"}}}"""), now)!!.used, 0.0)
    }

    private val both = json(
        """{"quota_reset_date":"2026-12-01","quota_snapshots":{"chat":{"unlimited":true},
        "premium_interactions":{"entitlement":300,"remaining":100,"unlimited":false},"ai_credits":{"entitlement":3900,"remaining":2500,"unlimited":false}}}""",
    )

    @Test fun autoPrefersCredits() {
        val s = QuotaParser.parseCopilotUser(both, dateUtc(2026, 10, 20))!!
        assertEquals(QuotaUnit.CREDITS, s.unit)
        assertEquals(3900.0, s.entitlement, 0.0)
        assertEquals(1400.0, s.used, 0.0)
    }

    @Test fun requestsPreference() {
        val s = QuotaParser.parseCopilotUser(both, dateUtc(2026, 10, 20), UnitPreference.REQUESTS)!!
        assertEquals(QuotaUnit.REQUESTS, s.unit)
        assertEquals(300.0, s.entitlement, 0.0)
        val r = QuotaParser.parseCopilotUser(json("""{"quota_snapshots":{"premium_interactions":{"entitlement":50,"remaining":10}}}"""), 0, UnitPreference.CREDITS)
        assertEquals(QuotaUnit.REQUESTS, r?.unit)
    }

    @Test fun billingCreditsPerModel() {
        val res = BillingParser.parseModelUsage(
            json("""{"usageItems":[{"model":"Opus","grossQuantity":10,"grossAmount":4.0},{"model":"Sonnet","grossQuantity":40,"grossAmount":1.6},{"model":"Opus","grossQuantity":5,"grossAmount":2.0}]}"""),
        )
        assertEquals("Opus", res[0].model)
        assertEquals(600.0, res[0].credits!!, 1e-9)
        assertEquals(160.0, res[1].credits!!, 1e-9)
        close(res[0].share, 6 / 7.6, 1e-9)
    }

    @Test fun billingTolerantFieldNames() {
        val res = BillingParser.parseModelUsage(
            json(
                """{"usageItems":[{"model":"Claude Opus","grossQuantity":30},{"model":"Claude Sonnet","grossQuantity":10},
                {"model":"Claude Opus","grossQuantity":"20"},{"model":"GPT-5 mini","grossQuantity":0},{"sku":"other","quantity":10}]}""",
            ),
        )
        assertEquals(listOf("Claude Opus" to 50.0, "Claude Sonnet" to 10.0, "other" to 10.0), res.map { it.model to it.quantity })
        close(res[0].share, 50.0 / 70, 1e-9)
        assertEquals(emptyList<Any>(), BillingParser.parseModelUsage(json("null")))
    }

    @Test fun thresholds() {
        assertNull(Thresholds.newlyCrossed(49.9, listOf(50.0, 75.0, 90.0), emptyList()))
        assertEquals(75.0, Thresholds.newlyCrossed(80.0, listOf(50.0, 75.0, 90.0), emptyList()))
        assertEquals(90.0, Thresholds.newlyCrossed(95.0, listOf(90.0, 50.0, 75.0), emptyList()))
        assertEquals(50.0, Thresholds.newlyCrossed(80.0, listOf(50.0, 75.0, 90.0), listOf(75.0)))
        assertNull(Thresholds.newlyCrossed(80.0, listOf(50.0, 75.0, 90.0), listOf(50.0, 75.0)))
        assertEquals(50.0, Thresholds.newlyCrossed(60.0, listOf(50.0, 50.0), emptyList()))
        assertNull(Thresholds.newlyCrossed(60.0, listOf(0.0, -5.0), emptyList()))
        assertEquals("2026-12-01", Thresholds.cycleKey("2026-12-01T00:00:00.000Z"))
        assertNotEquals(Thresholds.cycleKey("2026-12-01T00:00:00.000Z"), Thresholds.cycleKey("2027-01-01T00:00:00.000Z"))
    }

    @Test fun endpoints() {
        assertEquals("https://api.github.com", Endpoints.apiBaseForHost("github.com"))
        assertEquals("https://api.github.com", Endpoints.apiBaseForHost("api.github.com"))
        assertEquals("https://api.acme.ghe.com", Endpoints.apiBaseForHost("ACME.GHE.COM"))
        assertEquals("https://github.acme.corp/api/v3", Endpoints.apiBaseForHost("github.acme.corp"))
        assertEquals("https://acme.ghe.com", Endpoints.normaliseHost(" acme.ghe.com/ "))
        assertEquals("https://github.acme.corp", Endpoints.normaliseHost("https://github.acme.corp/login"))
        assertNull(Endpoints.normaliseHost("  "))
    }
}
