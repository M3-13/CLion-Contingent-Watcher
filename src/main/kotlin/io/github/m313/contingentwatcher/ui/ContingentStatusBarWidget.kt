package io.github.m313.contingentwatcher.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.ClickListener
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.m313.contingentwatcher.core.Appearance
import io.github.m313.contingentwatcher.core.Forecast
import io.github.m313.contingentwatcher.core.PaceStatus
import io.github.m313.contingentwatcher.core.QuotaSnapshot
import io.github.m313.contingentwatcher.l10n.L10n
import io.github.m313.contingentwatcher.services.ContingentService
import io.github.m313.contingentwatcher.services.ContingentSettings
import io.github.m313.contingentwatcher.services.ViewState
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel

class ContingentStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ContingentStatusBarWidget.ID
    override fun getDisplayName(): String = L10n.t("Copilot Contingent")
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = ContingentStatusBarWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

/** Shows the quota (remaining, used, percent or daily budget) with a fill-level icon. */
class ContingentStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private var background: Color? = null

    private val label = object : JLabel() {
        override fun paintComponent(g: Graphics) {
            background?.let { bg ->
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bg
                g2.fillRoundRect(0, JBUI.scale(2), width, height - JBUI.scale(4), JBUI.scale(6), JBUI.scale(6))
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }.apply {
        border = JBUI.Borders.empty(0, 6)
        iconTextGap = JBUI.scale(4)
        isOpaque = false
    }

    private val disposable: Disposable = Disposer.newDisposable("ContingentStatusBarWidget")

    init {
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                if (ContingentService.instance.view is ViewState.SignedOut) {
                    ContingentService.instance.signIn(project)
                } else {
                    Ui.openDashboard(project, null)
                }
                return true
            }
        }.installOn(label)
        ContingentService.instance.addListener(disposable) { render() }
        ContingentService.instance.start()
        render()
    }

    override fun ID(): String = ID
    override fun getComponent(): JComponent = label
    override fun install(statusBar: StatusBar) {}
    override fun dispose() = Disposer.dispose(disposable)

    private fun icon(level: Int, color: Color?): Icon? {
        val name = Appearance.iconFor(ContingentSettings.instance.iconStyle, level) ?: return null
        val icon = IconLoader.getIcon("/icons/status/$name.svg", ContingentStatusBarWidget::class.java)
        return if (color != null) IconUtil.colorize(icon, color) else icon
    }

    private fun custom(): Color? = Appearance.resolveColor(ContingentSettings.instance.statusBarColor)?.let { Color.decode(Appearance.hex6(it)) }

    fun render() {
        val custom = custom()
        background = null
        label.foreground = custom ?: UIUtil.getLabelForeground()
        when (val v = ContingentService.instance.view) {
            ViewState.Loading -> set(icon(4, custom), "…", L10n.t("Loading Copilot quota…"))
            is ViewState.SignedOut -> set(
                icon(4, custom),
                L10n.t("Sign in"),
                "<b>" + L10n.t(if (v.provider == "github-enterprise") "Sign in with GitHub Enterprise to see your Copilot quota" else "Sign in with GitHub to see your Copilot quota") +
                    "</b><br><br>" + L10n.t("Enterprise account? Set the provider in the settings or check the log."),
            )
            is ViewState.NoCopilot -> set(
                icon(4, custom),
                "—",
                "<b>" + L10n.t("No metered Copilot quota found for this account") + "</b><br><br>" +
                    if (v.status == 403) {
                        L10n.t("Access denied (403). Your organization may require SSO authorization or block this endpoint.")
                    } else {
                        L10n.t("Enterprise account? Set the provider in the settings or check the log.")
                    },
            )
            is ViewState.Error -> {
                val stale = v.stale
                if (stale != null) renderOk(stale.first, stale.second, true, custom) else set(icon(4, custom), "⚠", L10n.t("Could not load Copilot quota: {0}", v.message))
            }
            is ViewState.Ok -> renderOk(v.snap, v.forecast, false, custom)
        }
        label.repaint()
    }

    private fun renderOk(snap: QuotaSnapshot, f: Forecast, stale: Boolean, custom: Color?) {
        if (snap.unlimited) {
            set(icon(4, custom), "∞", L10n.t("Unlimited Copilot quota"))
            return
        }
        val severe = f.status == PaceStatus.CRITICAL || f.status == PaceStatus.EXHAUSTED
        val warn = severe || f.status == PaceStatus.AHEAD
        // With a custom colour only the text is coloured; the warning background is used with the default colour.
        if (warn && custom == null) {
            background = if (severe) JBColor(Color(0xF5, 0xC6, 0xC6), Color(0x6E, 0x2A, 0x2A)) else JBColor(Color(0xF9, 0xE7, 0xB0), Color(0x5E, 0x4D, 0x1D))
        }
        val text = StatusText.value(snap, f) + if (stale) " ⟲" else ""
        set(icon(Appearance.fillLevel(snap.remaining, snap.entitlement), custom), text, StatusText.tooltipHtml(snap, f, stale))
    }

    private fun set(icon: Icon?, text: String, tooltip: String) {
        label.icon = icon
        label.text = text
        label.toolTipText = "<html>$tooltip</html>"
    }

    companion object {
        const val ID = "CopilotContingentWatcher"
    }
}
