package io.github.m313.contingentwatcher.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import io.github.m313.contingentwatcher.l10n.L10n
import io.github.m313.contingentwatcher.services.ContingentService
import javax.swing.Action
import javax.swing.JComponent

/** "Get started" (counterpart of the VS Code walkthrough). */
class GettingStartedDialog(private val project: Project?) : DialogWrapper(project) {
    init {
        title = L10n.t("Get started with Copilot Contingent Watcher")
        setOKButtonText(L10n.t("Close"))
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun step(n: Int, title: String, text: String, button: String, action: () -> Unit) = panel {
        group("$n. $title") {
            row { text(text, maxLineLength = 70) }
            row {
                button(button) {
                    close(OK_EXIT_CODE)
                    action()
                }
            }
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row { text(L10n.t("Keep an eye on your Copilot quota and make it last until the reset."), maxLineLength = 70) }
        row {
            cell(
                step(
                    1,
                    L10n.t("Sign in with GitHub"),
                    L10n.t("The plugin reuses the sign-in of the GitHub Copilot plugin or a GitHub account from the IDE settings. No token is stored by the plugin unless you enter one."),
                    L10n.t("Sign in"),
                ) { ContingentService.instance.signIn(project) },
            )
        }
        row {
            cell(
                step(
                    2,
                    L10n.t("Read the status bar"),
                    L10n.t("Your quota, daily budget and pace at a glance. Hover over the status bar item for details, click it for the dashboard."),
                    L10n.t("Show details"),
                ) { Ui.openDashboard(project, "overview") },
            )
        }
        row {
            cell(
                step(
                    3,
                    L10n.t("Make your quota last"),
                    L10n.t("Practical habits that save requests without slowing you down."),
                    L10n.t("Show saving tips"),
                ) { Ui.openDashboard(project, "tips") },
            )
        }
        row {
            cell(
                step(
                    4,
                    L10n.t("Side questions with btw"),
                    L10n.t("Ctrl+Alt+Shift+B (Cmd+Alt+Shift+B on macOS) opens the btw window: quick side questions that are not mixed into your main task."),
                    L10n.t("Ask btw"),
                ) { Ui.openBtw(project, focusInput = true) },
            )
        }
        row {
            cell(
                step(
                    5,
                    L10n.t("Make it yours"),
                    L10n.t("Display mode, warning thresholds, weekends, reserve and refresh interval are in the settings."),
                    L10n.t("Open settings"),
                ) { Ui.openSettings(project) },
            )
        }
    }
}
