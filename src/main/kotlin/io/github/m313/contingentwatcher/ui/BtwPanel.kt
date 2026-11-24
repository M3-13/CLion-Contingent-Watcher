package io.github.m313.contingentwatcher.ui

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import io.github.m313.contingentwatcher.btw.BtwHub
import io.github.m313.contingentwatcher.btw.BtwRunner
import io.github.m313.contingentwatcher.btw.BtwView
import io.github.m313.contingentwatcher.btw.EditorSnapshot
import io.github.m313.contingentwatcher.l10n.L10n
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

/**
 * The "btw" window: answers to side questions stream here, beside the editor
 * (or in its own window), so they are visible without being mixed into the
 * main task. Session only: nothing is persisted.
 */
class BtwPanel(private val project: Project, parent: Disposable) : Disposable, BtwView {
    private val hub = BtwHub.instance

    @Volatile
    private var ready = false
    private var pendingFocus: String? = null
    private var hasPendingFocus = false

    private val web = WebPanel(this, script = "btw.js", style = "btw.css", title = L10n.t("btw"), onMessage = ::onMessage)

    val component: JComponent get() = web.component

    private var language = L10n.language

    init {
        Disposer.register(parent, this)
        hub.attach(this)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            io.github.m313.contingentwatcher.services.SettingsListener.TOPIC,
            io.github.m313.contingentwatcher.services.SettingsListener {
                if (language != L10n.language) {
                    language = L10n.language
                    ready = false
                    web.reload()
                }
            },
        )
    }

    override fun post(msg: JsonObject) {
        if (ready) ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) web.post(msg) }
    }

    /** Focuses the input (optionally prefilled). */
    fun focusInput(prefill: String?) {
        if (ready) {
            web.focus()
            web.post(JsonObject().apply {
                addProperty("type", "focus")
                prefill?.let { addProperty("prefill", it) }
            })
        } else {
            pendingFocus = prefill
            hasPendingFocus = true
        }
    }

    private fun onMessage(msg: JsonElement) {
        val o = if (msg.isJsonObject) msg.asJsonObject else return
        when (o.get("type")?.asString) {
            "ready" -> {
                ready = true
                web.post(JsonObject().apply {
                    addProperty("type", "init")
                    add("strings", WebPanel.gson.toJsonTree(strings()))
                    add("cards", JsonArray().also { a -> hub.allCards().forEach { a.add(it.toJson()) } })
                    if (hasPendingFocus) {
                        add("focus", JsonObject().apply { pendingFocus?.let { addProperty("prefill", it) } })
                        web.focus()
                    }
                })
                hasPendingFocus = false
                pendingFocus = null
            }
            "ask" -> o.get("question")?.asString?.trim()?.takeIf { it.isNotEmpty() }?.let { ask(project, it.take(4000), null) }
            "copy" -> o.get("text")?.asString?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
            "cancel" -> o.get("id")?.asString?.let { hub.cancel(it) }
            "clear" -> hub.clear()
            "openSettings" -> Ui.openSettings(project)
        }
    }

    override fun dispose() {
        hub.detach(this)
    }

    companion object {
        val KEY: Key<BtwPanel> = Key.create("contingentWatcher.btw")

        /** Asks a side question: answered right away, independent of any chat. */
        fun ask(project: Project?, question: String, editor: EditorSnapshot?) {
            val snapshot = editor ?: BtwRunner.editorSnapshot(project)
            val card = BtwHub.instance.startCard(question)
            BtwRunner.run(project, card, question, snapshot)
        }

        /** UI strings for the webview (keys are referenced by web/btw.js). */
        fun strings(): Map<String, String> = mapOf(
            "headerHint" to L10n.t("Side questions"),
            "placeholder" to L10n.t("Ask a side question… (works while the agent is busy)"),
            "inputLabel" to L10n.t("Side question"),
            "hint" to L10n.t("Enter to send · Shift+Enter for a new line"),
            "send" to L10n.t("Send"),
            "clear" to L10n.t("Clear"),
            "clearTitle" to L10n.t("Remove all side questions"),
            "stop" to L10n.t("Stop"),
            "copy" to L10n.t("Copy"),
            "copyAnswer" to L10n.t("Copy answer"),
            "copyCode" to L10n.t("Copy code"),
            "copied" to L10n.t("Copied"),
            "openSettings" to L10n.t("Open settings"),
            "context" to L10n.t("Context: {0}"),
            "notMixed" to L10n.t("not mixed into your main task"),
            "thinking" to L10n.t("Thinking"),
            "stopped" to L10n.t("Stopped."),
            "answerReady" to L10n.t("Answer ready"),
            "question" to L10n.t("Question"),
            "emptyTitle" to L10n.t("Type your side question here. It works while Copilot's agent is busy."),
            "emptyBody" to L10n.t("Answered with the context of your editor (and your chat, if available), without being mixed into your main task."),
        )
    }
}

class BtwToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.stripeTitle = L10n.t("btw")
        val factory = ContentFactory.getInstance()
        if (!Jcef.isSupported()) {
            toolWindow.contentManager.addContent(
                factory.createContent(com.intellij.ui.components.JBLabel(L10n.t("The btw window needs the IDE's embedded browser (JCEF).")), "", false),
            )
            return
        }
        val content = factory.createContent(null, "", false)
        val panel = BtwPanel(project, content)
        content.component = panel.component
        content.putUserData(BtwPanel.KEY, panel)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        Ui.applyBtwOpenIn(toolWindow)
    }

    companion object {
        const val ID = "btw"

        fun panel(project: Project): BtwPanel? =
            ToolWindowManager.getInstance(project).getToolWindow(ID)?.contentManager?.contents?.firstNotNullOfOrNull { it.getUserData(BtwPanel.KEY) }
    }
}
