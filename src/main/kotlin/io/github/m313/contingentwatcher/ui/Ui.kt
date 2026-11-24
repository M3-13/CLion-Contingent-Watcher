package io.github.m313.contingentwatcher.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.testFramework.LightVirtualFile
import io.github.m313.contingentwatcher.l10n.L10n
import io.github.m313.contingentwatcher.services.ActivityTracker
import io.github.m313.contingentwatcher.services.ContingentLog
import io.github.m313.contingentwatcher.services.ContingentSettings

/** Opening the plugin's windows and dialogs. Call on the EDT. */
object Ui {
    private fun projectOr(project: Project?): Project? =
        project?.takeIf { !it.isDisposed } ?: ActivityTracker.instance.currentProject() ?: ProjectManager.getInstance().openProjects.firstOrNull()

    /** Opens the dashboard, optionally on a tab (overview, history, tips, settings). */
    fun openDashboard(project: Project?, tab: String?) {
        val p = projectOr(project) ?: return
        val tw = ToolWindowManager.getInstance(p).getToolWindow(DashboardToolWindowFactory.ID) ?: return
        tw.activate {
            if (tab != null) DashboardToolWindowFactory.panel(p)?.selectTab(tab)
        }
    }

    /** Shows the btw window; with `focusInput` its input gets the focus (optionally prefilled). */
    fun openBtw(project: Project?, focusInput: Boolean, prefill: String? = null) {
        val p = projectOr(project) ?: return
        val tw = ToolWindowManager.getInstance(p).getToolWindow(BtwToolWindowFactory.ID) ?: return
        val after = Runnable { if (focusInput) BtwToolWindowFactory.panel(p)?.focusInput(prefill) }
        if (focusInput) tw.activate(after) else tw.show(after)
    }

    /** "side" docks the btw window beside the editor, "window" gives it its own window. */
    fun applyBtwOpenIn(toolWindow: ToolWindow? = null) {
        val type = if (ContingentSettings.instance.state.btwOpenIn == "window") ToolWindowType.WINDOWED else ToolWindowType.DOCKED
        val windows = if (toolWindow != null) {
            listOf(toolWindow)
        } else {
            ProjectManager.getInstance().openProjects.mapNotNull { ToolWindowManager.getInstance(it).getToolWindow(BtwToolWindowFactory.ID) }
        }
        windows.forEach { if (it.type != type) it.setType(type, null) }
    }

    fun openSettings(project: Project?) {
        ShowSettingsUtil.getInstance().showSettingsDialog(projectOr(project), ContingentConfigurable::class.java)
    }

    /** Shows the plugin's log in a read-only editor tab. Tokens are never logged. */
    fun showLog(project: Project?) {
        val p = projectOr(project) ?: return
        val file = LightVirtualFile(L10n.t("Copilot Contingent") + ".log", ContingentLog.instance.text())
        file.isWritable = false
        OpenFileDescriptor(p, file).navigate(true)
    }

    fun showGettingStarted(project: Project?) {
        GettingStartedDialog(projectOr(project)).show()
    }
}
