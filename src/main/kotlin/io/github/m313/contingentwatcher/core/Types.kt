package io.github.m313.contingentwatcher.core

/** Billing unit: legacy premium requests or usage-based AI credits. */
enum class QuotaUnit(val id: String) {
    REQUESTS("requests"),
    CREDITS("credits");

    companion object {
        fun of(id: String?): QuotaUnit = if (id == "credits") CREDITS else REQUESTS
    }
}

/** Normalised quota snapshot, independent of the data source. */
data class QuotaSnapshot(
    /** Monthly allowance. */
    val entitlement: Double,
    /** Used in the current cycle. */
    val used: Double,
    /** Remaining in the current cycle (never negative). */
    val remaining: Double,
    /** True if the plan has no limit. */
    val unlimited: Boolean,
    /** Paid overage enabled. */
    val overagePermitted: Boolean,
    /** Units billed beyond the allowance in this cycle. */
    val overageCount: Double,
    /** Next reset as ISO timestamp (UTC). */
    val resetDate: String,
    val unit: QuotaUnit,
    /** Raw quota id from the API (e.g. "premium_interactions"). */
    val quotaId: String,
    /** Plan identifier as reported by GitHub, e.g. "individual", "business". */
    val plan: String? = null,
    /** When the snapshot was fetched (epoch ms). */
    val fetchedAt: Long,
)

/** A point in the usage history, persisted to compute the burn rate. */
data class HistoryPoint(val t: Long, val used: Double)

data class ForecastOptions(
    val countWeekends: Boolean,
    /** Amount to keep in reserve; excluded from the daily budget. */
    val reserve: Double,
)

enum class PaceStatus(val id: String) {
    ON_TRACK("onTrack"),
    AHEAD("ahead"),
    CRITICAL("critical"),
    EXHAUSTED("exhausted"),
    UNLIMITED("unlimited"),
}

data class Forecast(
    val usedPercent: Double,
    /** Fraction (0..1) of the cycle (in budget days) that has elapsed. */
    val cycleElapsed: Double,
    /** Used / linearly expected usage. 1.0 = exactly on plan. */
    val paceRatio: Double,
    /** Budget days left including today. */
    val daysLeft: Double,
    /** Units per budget day you can spend from now until reset. */
    val dailyBudget: Double,
    /** Observed units per budget day (recent window, fallback: cycle average). */
    val burnRate: Double,
    /** Projected usage at reset if the burn rate continues. */
    val projectedUsed: Double,
    /** Estimated time (epoch ms) the allowance runs out, if before reset. */
    val exhaustionDate: Long?,
    val status: PaceStatus,
)
