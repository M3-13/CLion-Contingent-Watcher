package io.github.m313.contingentwatcher.ui

import com.intellij.openapi.util.text.StringUtil
import io.github.m313.contingentwatcher.core.Forecast
import io.github.m313.contingentwatcher.core.QuotaSnapshot
import io.github.m313.contingentwatcher.core.Surplus
import io.github.m313.contingentwatcher.core.Tips
import io.github.m313.contingentwatcher.l10n.L10n
import io.github.m313.contingentwatcher.services.ContingentSettings
import io.github.m313.contingentwatcher.services.Format
import java.time.LocalDate

/** Status bar text and tooltip (counterpart of statusBar.ts). */
object StatusText {
    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)

    fun value(snap: QuotaSnapshot, f: Forecast): String {
        if (snap.unlimited) return "∞"
        return when (ContingentSettings.instance.display) {
            "used" -> "${Format.fmt(snap.used)}/${Format.fmt(snap.entitlement)}"
            "percent" -> "${Math.round(f.usedPercent)}%"
            "dailyBudget" -> L10n.t("{0}/day", Format.fmt(f.dailyBudget))
            else -> Format.fmt(snap.remaining)
        }
    }

    /** HTML body (without <html>) for the tooltip. */
    fun tooltipHtml(snap: QuotaSnapshot, f: Forecast, stale: Boolean): String {
        if (snap.unlimited) return esc(L10n.t("Unlimited Copilot quota"))
        val cfg = ContingentSettings.instance
        val unit = Format.unitLabel(snap)
        val sb = StringBuilder()
        sb.append("<b>").append(esc(L10n.t("Copilot Contingent"))).append("</b> — ").append(esc(Format.statusLabel(f))).append("<br><br>")
        sb.append("<code>").append(Format.bar(snap.used / maxOf(snap.entitlement, 1.0))).append("</code> ")
            .append(Math.round(f.usedPercent)).append("% ").append(esc(L10n.t("used"))).append("<br>")
        sb.append("<code>").append(Format.bar(f.cycleElapsed)).append("</code> ")
            .append(Math.round(f.cycleElapsed * 100)).append("% ").append(esc(L10n.t("of the month elapsed"))).append("<br><hr>")
        sb.append(esc(L10n.t("Remaining"))).append(": <b>").append(Format.fmt(snap.remaining)).append("</b> / ")
            .append(Format.fmt(snap.entitlement)).append(' ').append(esc(unit)).append("<br>")
        sb.append(esc(L10n.t("Daily budget"))).append(": <b>").append(Format.fmt(f.dailyBudget)).append("</b> ").append(esc(unit))
            .append(" · ").append(esc(L10n.t("your pace"))).append(": ").append(Format.fmt(f.burnRate)).append('/').append(esc(L10n.t("day"))).append("<br>")
        f.exhaustionDate?.let { sb.append("⚠ ").append(esc(L10n.t("At this pace empty on {0}", Format.fmtDate(it)))).append("<br>") }
        sb.append(esc(L10n.t("Resets on {0}", Format.fmtDate(snap.resetDate))))
        if (cfg.reserve > 0) sb.append(" · ").append(esc(L10n.t("Reserve: {0}", Format.fmt(cfg.reserve))))
        sb.append("<br>")
        if (snap.overageCount > 0) sb.append(esc(L10n.t("Overage this month: {0}", Format.fmt(snap.overageCount)))).append("<br>")
        if (stale) sb.append(esc(L10n.t("Offline — showing data from {0}", Format.fmtTime(snap.fetchedAt)))).append("<br>")
        if (cfg.state.tipsEnabled) {
            val tip = Tips.pick(
                Tips.situationsFor(Tips.SituationInput(f.status, f.cycleElapsed, Surplus.expected(snap, f) != null)),
                LocalDate.now().dayOfMonth.toDouble(),
            )
            sb.append("<hr>💡 ").append(esc(L10n.t(tip.text)))
        }
        sb.append("<br><br><i>").append(esc(L10n.t("Click to open the dashboard"))).append("</i>")
        return sb.toString()
    }
}
