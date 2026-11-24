package io.github.m313.contingentwatcher.actions

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import io.github.m313.contingentwatcher.btw.BtwRunner
import io.github.m313.contingentwatcher.l10n.L10n
import io.github.m313.contingentwatcher.services.ActivityTracker
import io.github.m313.contingentwatcher.services.BillingService
import io.github.m313.contingentwatcher.services.ContingentService
import io.github.m313.contingentwatcher.services.ContingentSettings
import io.github.m313.contingentwatcher.services.ContingentStore
import io.github.m313.contingentwatcher.services.Notifier
import io.github.m313.contingentwatcher.ui.BtwPanel
import io.github.m313.contingentwatcher.ui.Ui

/** Base: localised text (follows the plugin's language setting), always available. */
abstract class ContingentAction(private val english: String) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = L10n.t(english)
    }
}

class RefreshAction : ContingentAction("Refresh Quota") {
    override fun actionPerformed(e: AnActionEvent) = ContingentService.instance.refresh()
}

class ShowDetailsAction : ContingentAction("Show Quota Details") {
    override fun actionPerformed(e: AnActionEvent) = Ui.openDashboard(e.project, "overview")
}

class ShowTipsAction : ContingentAction("Show Saving Tips") {
    override fun actionPerformed(e: AnActionEvent) = Ui.openDashboard(e.project, "tips")
}

class OpenSettingsAction : ContingentAction("Open Settings") {
    override fun actionPerformed(e: AnActionEvent) = Ui.openSettings(e.project)
}

class SignInAction : ContingentAction("Sign in with GitHub") {
    override fun actionPerformed(e: AnActionEvent) = ContingentService.instance.signIn(e.project)
}

class SnoozeAction : ContingentAction("Snooze Warnings for 24 Hours") {
    override fun actionPerformed(e: AnActionEvent) {
        ContingentStore.instance.snooze(24)
        ContingentService.instance.render()
        e.project?.let { WindowManager.getInstance().getStatusBar(it)?.info = L10n.t("Copilot quota warnings snoozed for 24h") }
    }
}

class ShowLogAction : ContingentAction("Show Log") {
    override fun actionPerformed(e: AnActionEvent) = Ui.showLog(e.project)
}

class ConfigureBillingTokenAction : ContingentAction("Set Up Usage per Model") {
    override fun actionPerformed(e: AnActionEvent) = BillingService.instance.configure(e.project)
}

class GettingStartedAction : ContingentAction("Get Started") {
    override fun actionPerformed(e: AnActionEvent) = Ui.showGettingStarted(e.project)
}

/** Opens the btw window and focuses its input; with a selection, prefills "About the selection: ". */
class BtwAction : ContingentAction("btw: Ask a Side Question") {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val prefill = if (editor?.selectionModel?.hasSelection() == true) L10n.t("About the selection: ") else null
        Ui.openBtw(e.project, focusInput = true, prefill = prefill)
    }
}

class BtwShowAction : ContingentAction("Show btw Window") {
    override fun actionPerformed(e: AnActionEvent) = Ui.openBtw(e.project, focusInput = false)
}

/** Editor context menu: asks btw to explain the selection right away. */
class BtwExplainSelectionAction : ContingentAction("btw: Explain Selection") {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project
        val snapshot = BtwRunner.editorSnapshot(project, editor)
        Ui.openBtw(project, focusInput = false)
        BtwPanel.ask(project, L10n.t("Explain the selected code briefly."), snapshot)
    }
}

/** Starts polling when the first project opens and greets new users once. */
class ContingentStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ActivityTracker.instance
        ContingentService.instance.start()
        val settings = ContingentSettings.instance
        if (!settings.state.welcomeShown) {
            settings.state.welcomeShown = true
            NotificationGroupManager.getInstance().getNotificationGroup(Notifier.GROUP)
                .createNotification(
                    L10n.t("Copilot Contingent Watcher"),
                    L10n.t("Your Copilot quota is shown in the status bar. Quick side questions: Ctrl+Alt+Shift+B."),
                    NotificationType.INFORMATION,
                )
                .addAction(NotificationAction.createSimpleExpiring(L10n.t("Get started")) { Ui.showGettingStarted(project) })
                .notify(project)
        }
    }
}
