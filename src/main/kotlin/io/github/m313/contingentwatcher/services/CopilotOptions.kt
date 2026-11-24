package io.github.m313.contingentwatcher.services

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Copilot's own options (as in its status bar menu), discovered at runtime from
 * the actions the installed GitHub Copilot plugin registers, so we follow
 * whatever the installed version offers. Anything not found stays hidden.
 *
 * The state is computed in the background and cached ([state]); the dashboard
 * re-renders when it changes. Performing an action happens on the EDT.
 */
object CopilotOptions {
    data class State(
        val completions: Boolean? = null,
        val nextEdit: Boolean? = null,
        /** Language label and whether completions are on for it. */
        val language: Pair<String, Boolean>? = null,
        val snoozeAvailable: Boolean = false,
    )

    @Volatile
    private var cached = State()
    private val computing = AtomicBoolean(false)

    val installed: Boolean
        // Copilot registers its actions only while it is installed and enabled.
        get() = copilotActionIds().isNotEmpty()

    /** Last known state; refreshes it in the background and re-renders on change. */
    fun state(project: Project?): State {
        if (!installed) return State()
        if (computing.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val next = compute(project)
                    if (next != cached) {
                        cached = next
                        ContingentService.instance.render()
                    }
                } catch (_: Throwable) {
                } finally {
                    computing.set(false)
                }
            }
        }
        return cached
    }

    private fun copilotActionIds(): List<String> = try {
        ActionManager.getInstance().getActionIdList("").filter { it.contains("copilot", ignoreCase = true) }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun find(ids: List<String>, include: Regex, exclude: Regex? = null): String? =
        ids.filter { include.containsMatchIn(it) && (exclude == null || !exclude.containsMatchIn(it)) }.minByOrNull { it.length }

    private val LANG = Regex("language|lang", RegexOption.IGNORE_CASE)
    private val NES = Regex("nes\\b|nextedit|next.?edit", RegexOption.IGNORE_CASE)

    private data class Ids(
        val enable: String?,
        val disable: String?,
        val enableLang: String?,
        val disableLang: String?,
        val nesToggle: String?,
        val snooze: String?,
    )

    private fun ids(): Ids {
        val all = copilotActionIds()
        val other = Regex("$LANG|$NES|chat|agent|edit|review|commit", RegexOption.IGNORE_CASE)
        return Ids(
            enable = find(all, Regex("enable.*copilot|copilot.*\\.enable", RegexOption.IGNORE_CASE), Regex("disable|$other", RegexOption.IGNORE_CASE)),
            disable = find(all, Regex("disable.*copilot|copilot.*\\.disable", RegexOption.IGNORE_CASE), other),
            enableLang = find(all, Regex("enable.*($LANG)", RegexOption.IGNORE_CASE), Regex("disable", RegexOption.IGNORE_CASE)),
            disableLang = find(all, Regex("disable.*($LANG)", RegexOption.IGNORE_CASE)),
            nesToggle = find(all, NES),
            snooze = find(all, Regex("snooze", RegexOption.IGNORE_CASE), Regex("unsnooze|cancel", RegexOption.IGNORE_CASE)),
        )
    }

    private fun dataContext(project: Project?): DataContext {
        if (project == null || project.isDisposed) return DataContext.EMPTY_CONTEXT
        val fem = FileEditorManager.getInstance(project)
        val b = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        fem.selectedTextEditor?.let { b.add(CommonDataKeys.EDITOR, it) }
        fem.selectedFiles.firstOrNull()?.let { b.add(CommonDataKeys.VIRTUAL_FILE, it) }
        return b.build()
    }

    private fun event(action: AnAction, project: Project?): AnActionEvent =
        AnActionEvent.createEvent(ApplicationManager.getApplication().runReadAction<DataContext> { dataContext(project) }, Presentation().apply { copyFrom(action.templatePresentation) }, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)

    /** Runs `block` where the action expects its update: the EDT, or a read action in the background. */
    private fun <T> onUpdateThread(action: AnAction, block: () -> T): T? {
        var result: T? = null
        if (action.actionUpdateThread == ActionUpdateThread.EDT) {
            ApplicationManager.getApplication().invokeAndWait({ result = runCatching(block).getOrNull() }, ModalityState.any())
        } else {
            result = ApplicationManager.getApplication().runReadAction<T?> { runCatching(block).getOrNull() }
        }
        return result
    }

    /** True if the action is currently available (enabled and visible). */
    private fun available(id: String?, project: Project?): Boolean? {
        val action = id?.let { ActionManager.getInstance().getAction(it) } ?: return null
        return onUpdateThread(action) {
            val e = event(action, project)
            ActionUtil.updateAction(action, e)
            e.presentation.isEnabledAndVisible
        }
    }

    private fun compute(project: Project?): State {
        val i = ids()
        val canDisable = available(i.disable, project)
        val canEnable = available(i.enable, project)
        val completions = when {
            canDisable == true -> true
            canEnable == true -> false
            else -> null
        }
        val file = project?.takeIf { !it.isDisposed }?.let { p -> ApplicationManager.getApplication().runReadAction<com.intellij.openapi.vfs.VirtualFile?> { FileEditorManager.getInstance(p).selectedFiles.firstOrNull() } }
        val language = file?.let {
            val label = it.fileType.displayName
            when {
                available(i.disableLang, project) == true -> label to true
                available(i.enableLang, project) == true -> label to false
                else -> null
            }
        }
        val nes = i.nesToggle?.let { id ->
            val action = ActionManager.getInstance().getAction(id) as? ToggleAction ?: return@let null
            onUpdateThread(action) { action.isSelected(event(action, project)) }
        }
        return State(completions, nes, language, i.snooze != null)
    }

    private fun perform(id: String?, project: Project?) {
        val action = id?.let { ActionManager.getInstance().getAction(it) } ?: return
        val e = event(action, project)
        ActionUtil.updateAction(action, e)
        if (e.presentation.isEnabled) ActionUtil.performAction(action, e)
        // Read the new state soon.
        ApplicationManager.getApplication().invokeLater { state(project) }
    }

    fun setCompletions(enabled: Boolean, project: Project?) {
        val i = ids()
        perform(if (enabled) i.enable else i.disable, project)
    }

    fun setLanguage(enabled: Boolean, project: Project?) {
        val i = ids()
        perform(if (enabled) i.enableLang else i.disableLang, project)
    }

    fun setNextEdit(enabled: Boolean, project: Project?) {
        if (cached.nextEdit != enabled) perform(ids().nesToggle, project)
    }

    fun snooze(project: Project?) = perform(ids().snooze, project)

    /** Opens Copilot's chat tool window. */
    fun openChat(project: Project?): Boolean {
        project ?: return false
        val twm = ToolWindowManager.getInstance(project)
        val id = twm.toolWindowIds.firstOrNull { it.contains("copilot", true) && it.contains("chat", true) }
            ?: twm.toolWindowIds.firstOrNull { it.contains("copilot", true) }
            ?: return false
        twm.getToolWindow(id)?.activate(null)
        return true
    }
}
