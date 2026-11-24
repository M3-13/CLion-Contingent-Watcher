package io.github.m313.contingentwatcher.services

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import io.github.m313.contingentwatcher.core.Forecast
import io.github.m313.contingentwatcher.core.PaceStatus
import io.github.m313.contingentwatcher.core.QuotaSnapshot
import io.github.m313.contingentwatcher.core.Surplus
import io.github.m313.contingentwatcher.core.Thresholds
import io.github.m313.contingentwatcher.l10n.L10n
import io.github.m313.contingentwatcher.ui.Ui

/** Shows at most one notification per refresh, never twice for the same event. */
object Notifier {
    const val GROUP = "Copilot Contingent"

    fun maybeNotify(snap: QuotaSnapshot, f: Forecast) {
        val cfg = ContingentSettings.instance
        val state = ContingentStore.instance
        if (!cfg.state.warningsEnabled || snap.unlimited || state.snoozed) return

        val crossed = Thresholds.newlyCrossed(f.usedPercent, cfg.thresholds, state.notifiedThresholds(snap.resetDate))
        if (crossed != null) {
            state.markNotified(snap.resetDate, crossed, cfg.thresholds)
            val msg = if (crossed >= 100) {
                L10n.t("Copilot allowance used up. Resets on {0}.", Format.fmtDate(snap.resetDate))
            } else {
                L10n.t(
                    "Copilot: {0}% of your allowance used — {1} {2} left, about {3} per day until {4}.",
                    Math.round(crossed),
                    Format.fmt(snap.remaining),
                    Format.unitLabel(snap),
                    Format.fmt(f.dailyBudget),
                    Format.fmtDate(snap.resetDate),
                )
            }
            show(msg, if (crossed >= 90) NotificationType.WARNING else NotificationType.INFORMATION, tips = true, details = true, snooze = true)
            return
        }

        if (cfg.state.paceWarning && f.status == PaceStatus.CRITICAL && f.exhaustionDate != null && !state.paceWarnedToday()) {
            state.markPaceWarned()
            val msg = L10n.t(
                "Copilot: at your current pace the allowance runs out on {0} (reset {1}). Budget: {2} per day.",
                Format.fmtDate(f.exhaustionDate),
                Format.fmtDate(snap.resetDate),
                Format.fmt(f.dailyBudget),
            )
            show(msg, NotificationType.INFORMATION, tips = true, details = false, snooze = true)
            return
        }

        val unused = if (cfg.state.surplusHint) Surplus.expected(snap, f) else null
        if (unused != null && !state.surplusNotified(snap.resetDate)) {
            state.markSurplusNotified(snap.resetDate)
            show(Format.surplusMessage(snap, unused), NotificationType.INFORMATION, tips = false, details = true, snooze = false)
        }
    }

    private fun show(msg: String, type: NotificationType, tips: Boolean, details: Boolean, snooze: Boolean) {
        val n: Notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(msg, type)
        if (tips) n.addAction(NotificationAction.createSimpleExpiring(L10n.t("Saving tips")) { Ui.openDashboard(null, "tips") })
        if (details) n.addAction(NotificationAction.createSimpleExpiring(L10n.t("Details")) { Ui.openDashboard(null, null) })
        if (snooze) {
            n.addAction(
                NotificationAction.createSimpleExpiring(L10n.t("Snooze 24h")) {
                    ContingentStore.instance.snooze(24)
                    ContingentService.instance.render()
                },
            )
        }
        n.notify(null)
    }

    /** A plain information balloon. */
    fun info(msg: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(msg, type).notify(null)
    }
}
