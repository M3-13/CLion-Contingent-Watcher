package io.github.m313.contingentwatcher

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.github.m313.contingentwatcher.core.QuotaSnapshot
import io.github.m313.contingentwatcher.core.QuotaUnit
import io.github.m313.contingentwatcher.core.Time
import org.junit.Assert.assertTrue

const val DAY = 86_400_000L
const val HOUR = 3_600_000L

/** Like JavaScript's Date.UTC with 0-based months. */
fun dateUtc(y: Int, m0: Int, d: Int, h: Int = 0, min: Int = 0): Long = Time.utcDate(y, m0 + 1, d, h, min)

/** Like `new Date(y, m0, d, h).getTime()` (local time). */
fun dateLocal(y: Int, m0: Int, d: Int, h: Int = 0): Long =
    java.time.LocalDateTime.of(y, m0 + 1, d, h, 0).atZone(Time.zone).toInstant().toEpochMilli()

fun json(s: String): JsonElement = JsonParser.parseString(s)

fun close(actual: Double, expected: Double, eps: Double = 1e-6) =
    assertTrue("$actual !~ $expected", Math.abs(actual - expected) < eps)

fun snap(
    used: Double,
    now: Long,
    entitlement: Double = 300.0,
    resetDate: String = "2026-12-01T00:00:00.000Z",
    unlimited: Boolean = false,
    overagePermitted: Boolean = false,
    overageCount: Double = 0.0,
    remaining: Double = maxOf(0.0, entitlement - used),
) = QuotaSnapshot(
    entitlement = entitlement,
    used = used,
    remaining = remaining,
    unlimited = unlimited,
    overagePermitted = overagePermitted,
    overageCount = overageCount,
    resetDate = resetDate,
    unit = QuotaUnit.REQUESTS,
    quotaId = "premium_interactions",
    fetchedAt = now,
)
