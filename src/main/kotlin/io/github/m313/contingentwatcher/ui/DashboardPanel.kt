package io.github.m313.contingentwatcher.ui

import com.google.gson.JsonElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import io.github.m313.contingentwatcher.services.ContingentService
import io.github.m313.contingentwatcher.services.ContingentSettings
import io.github.m313.contingentwatcher.services.SettingsListener
import io.github.m313.contingentwatcher.services.ViewState
import io.github.m313.contingentwatcher.l10n.L10n
import com.intellij.openapi.application.ApplicationManager
import javax.swing.JComponent

/** The dashboard (overview, history, tips, settings) in the "Copilot Contingent" tool window. */
class DashboardPanel(private val project: Project, parent: Disposable) : Disposable {
    private val service = ContingentService.instance
    private var ready = false
    private var pendingTab: String? = null
    private var language = L10n.language

    private val web = WebPanel(
        this,
        script = "dashboard.js",
        style = "dashboard.css",
        title = L10n.t("Copilot Contingent"),
        bodyAttrs = { "data-icon-font=\"${WebPanel.iconFontDataUrl()}\"" },
        onMessage = ::onMessage,
    )

    val component: JComponent get() = web.component

    init {
        Disposer.register(parent, this)
        service.addListener(this) { update() }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            SettingsListener.TOPIC,
            SettingsListener {
                if (language != L10n.language) {
                    language = L10n.language
                    ready = false
                    web.reload()
                }
            },
        )
    }

    fun update() {
        if (ready && !project.isDisposed) web.post(mapOf("type" to "model", "model" to service.model(project)))
    }

    fun selectTab(tab: String) {
        if (ready) web.post(mapOf("type" to "selectTab", "tab" to tab)) else pendingTab = tab
    }

    private fun onMessage(msg: JsonElement) {
        val o = if (msg.isJsonObject) msg.asJsonObject else return
        try {
            when (o.get("type")?.asString) {
                "ready" -> {
                    ready = true
                    update()
                    pendingTab?.let { selectTab(it) }
                    pendingTab = null
                }
                "command" -> o.get("command")?.asString?.let { if (it in COMMANDS) service.onCommand(it, project) }
                "setSetting" -> {
                    val key = o.get("key")?.asString ?: return
                    if (key in SETTINGS) service.onSetting(key, WebPanel.gson.fromJson(o.get("value"), Any::class.java), project)
                }
            }
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), L10n.t("Copilot Contingent"))
        }
    }

    override fun dispose() {}

    companion object {
        val KEY: Key<DashboardPanel> = Key.create("contingentWatcher.dashboard")

        private val COMMANDS = setOf(
            "refresh", "signIn", "showLog", "openSettings", "openBilling", "manageCopilot", "openChat", "snooze", "unsnooze",
            "openWalkthrough", "configureBillingToken", "clearBillingToken", "clearActivity", "btw", "snoozeCompletions",
        )
        private val SETTINGS = setOf(
            "display", "countWeekends", "reserve", "thresholds", "warningsEnabled", "paceWarning", "tipsEnabled", "authProvider",
            "copilotCompletions", "copilotNextEdit", "accent", "statusBarIcon", "statusBarColor", "surplusHint", "activityTracking",
            "refreshIntervalMinutes", "alignment", "apiUrl", "preferredModels", "btwModel", "btwOpenIn", "btwIncludeChat",
            "copilotEagerness", "copilotLanguage", "unit",
        )
    }
}

class DashboardToolWindowFactory : ToolWindowFactory, com.intellij.openapi.project.DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.stripeTitle = L10n.t("Copilot Contingent")
        val factory = ContentFactory.getInstance()
        if (!JBCefApp.isSupported()) {
            toolWindow.contentManager.addContent(factory.createContent(FallbackPanel(toolWindow.disposable), "", false))
            return
        }
        val content = factory.createContent(null, "", false)
        val panel = DashboardPanel(project, content)
        content.component = panel.component
        content.putUserData(DashboardPanel.KEY, panel)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        ContingentService.instance.start()
    }

    companion object {
        const val ID = "Copilot Contingent"

        fun panel(project: Project): DashboardPanel? =
            ToolWindowManager.getInstance(project).getToolWindow(ID)?.contentManager?.contents?.firstNotNullOfOrNull { it.getUserData(DashboardPanel.KEY) }
    }
}

/** Shown when the IDE runs without JCEF: the key numbers as plain text. */
class FallbackPanel(parent: Disposable) : com.intellij.ui.components.JBScrollPane() {
    private val label = com.intellij.ui.components.JBLabel().apply {
        verticalAlignment = javax.swing.SwingConstants.TOP
        border = com.intellij.util.ui.JBUI.Borders.empty(12)
    }

    init {
        setViewportView(label)
        ContingentService.instance.addListener(parent) { update() }
        update()
    }

    private fun update() {
        val v = ContingentService.instance.view
        val text = StringBuilder("<html><b>${L10n.t("Copilot Contingent")}</b><br><br>")
        when (v) {
            is ViewState.Ok -> text.append(StatusText.tooltipHtml(v.snap, v.forecast, false))
            is ViewState.Error -> v.stale?.let { text.append(StatusText.tooltipHtml(it.first, it.second, true)) } ?: text.append(L10n.t("Could not load Copilot quota: {0}", v.message))
            is ViewState.SignedOut -> text.append(L10n.t("Sign in to see your Copilot quota"))
            is ViewState.NoCopilot -> text.append(L10n.t("No metered Copilot quota found for this account"))
            ViewState.Loading -> text.append(L10n.t("Loading Copilot quota…"))
        }
        text.append("<br><br><i>").append(L10n.t("The full dashboard needs the IDE's embedded browser (JCEF).")).append("</i></html>")
        label.text = text.toString()
    }
}
