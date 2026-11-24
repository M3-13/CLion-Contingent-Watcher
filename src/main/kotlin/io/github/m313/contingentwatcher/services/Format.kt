package io.github.m313.contingentwatcher.services

import io.github.m313.contingentwatcher.core.Forecast
import io.github.m313.contingentwatcher.core.PaceStatus
import io.github.m313.contingentwatcher.core.QuotaSnapshot
import io.github.m313.contingentwatcher.core.QuotaUnit
import io.github.m313.contingentwatcher.core.Time
import io.github.m313.contingentwatcher.l10n.L10n
import java.text.NumberFormat
import java.time.Instant
import java.time.format.DateTimeFormatter

object Format {
    fun fmt(n: Double): String {
        if (!n.isFinite()) return "∞"
        return if (n >= 100 || n == Math.floor(n)) {
            NumberFormat.getIntegerInstance(L10n.locale).format(Math.round(n))
        } else {
            String.format(L10n.locale, "%.1f", n)
        }
    }

    fun unitLabel(snap: QuotaSnapshot): String = if (snap.unit == QuotaUnit.CREDITS) L10n.t("credits") else L10n.t("requests")

    /** "24 Nov" / "24. Nov." */
    fun fmtDate(ms: Long): String {
        val pattern = if (L10n.language == "de") "d. MMM" else "MMM d"
        return DateTimeFormatter.ofPattern(pattern, L10n.locale).format(Instant.ofEpochMilli(ms).atZone(Time.zone))
    }

    fun fmtDate(iso: String): String = Time.parse(iso)?.let { fmtDate(it) } ?: iso

    fun fmtTime(ms: Long): String = DateTimeFormatter.ofPattern("HH:mm", L10n.locale).format(Instant.ofEpochMilli(ms).atZone(Time.zone))

    fun monthLabel(ms: Long): String = DateTimeFormatter.ofPattern("LLLL yyyy", L10n.locale).format(Instant.ofEpochMilli(ms).atZone(Time.zone))

    /** Text-based progress bar. */
    fun bar(fraction: Double, width: Int = 20): String {
        val f = fraction.coerceIn(0.0, 1.0)
        val filled = Math.round(f * width).toInt()
        return "█".repeat(filled) + "░".repeat(width - filled)
    }

    fun statusLabel(f: Forecast): String = when (f.status) {
        PaceStatus.ON_TRACK -> L10n.t("On track")
        PaceStatus.AHEAD -> L10n.t("Faster than planned")
        PaceStatus.CRITICAL -> L10n.t("Will run out before reset")
        PaceStatus.EXHAUSTED -> L10n.t("Allowance used up")
        PaceStatus.UNLIMITED -> L10n.t("Unlimited")
    }

    /** "Use it or lose it" message shown in the dashboard and as notification. */
    fun surplusMessage(snap: QuotaSnapshot, unused: Double): String = L10n.t(
        "About {0} {1} will expire unused on {2}. A good time for demanding tasks with a premium model: refactoring, reviews, tests.",
        fmt(unused),
        unitLabel(snap),
        fmtDate(snap.resetDate),
    )
}
