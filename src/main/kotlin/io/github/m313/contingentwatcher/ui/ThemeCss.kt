package io.github.m313.contingentwatcher.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.UIManager

/**
 * Maps the IDE's look and feel to the `--vscode-*` CSS variables the shared
 * webview styles use, so the dashboard and btw window follow the IDE theme.
 */
object ThemeCss {
    private fun css(c: Color?): String? = c?.let {
        if (it.alpha == 255) String.format("#%02x%02x%02x", it.red, it.green, it.blue)
        else String.format("rgba(%d, %d, %d, %.3f)", it.red, it.green, it.blue, it.alpha / 255.0)
    }

    private fun ui(key: String): Color? = UIManager.getColor(key)

    val isDark: Boolean get() = !JBColor.isBright()

    /** Tool window background. */
    fun background(): Color = UIUtil.getPanelBackground()

    fun variables(): Map<String, String> {
        val dark = isDark
        val fg = UIUtil.getLabelForeground()
        val bg = background()
        val scheme = EditorColorsManager.getInstance().globalScheme
        val font = UIUtil.getLabelFont()
        val px = font.size2D / JBUIScale.scale(1f)
        val vars = linkedMapOf<String, String?>(
            "--vscode-foreground" to css(fg),
            "--vscode-descriptionForeground" to css(UIUtil.getContextHelpForeground()),
            "--vscode-errorForeground" to css(ui("Label.errorForeground") ?: JBColor.RED),
            "--vscode-sideBar-background" to css(bg),
            "--vscode-editor-background" to css(bg),
            "--vscode-editor-foreground" to css(fg),
            "--vscode-focusBorder" to css(JBUI.CurrentTheme.Focus.focusColor()),
            "--vscode-panelTitle-activeBorder" to css(ui("TabbedPane.underlineColor") ?: JBUI.CurrentTheme.Focus.focusColor()),
            "--vscode-widget-border" to css(JBColor.border()),
            "--vscode-toolbar-hoverBackground" to css(JBUI.CurrentTheme.ActionButton.hoverBackground()),
            "--vscode-button-background" to css(ui("Button.default.startBackground") ?: JBUI.CurrentTheme.Focus.focusColor()),
            "--vscode-button-foreground" to css(ui("Button.default.foreground") ?: Color.WHITE),
            "--vscode-button-hoverBackground" to css(ui("Button.default.endBackground") ?: ui("Button.default.startBackground")),
            "--vscode-button-border" to css(ui("Button.default.borderColor")),
            "--vscode-button-secondaryBackground" to css(ui("Button.startBackground") ?: bg),
            "--vscode-button-secondaryForeground" to css(ui("Button.foreground") ?: fg),
            "--vscode-button-secondaryHoverBackground" to css(JBUI.CurrentTheme.ActionButton.hoverBackground()),
            "--vscode-input-background" to css(UIUtil.getTextFieldBackground()),
            "--vscode-input-foreground" to css(UIUtil.getTextFieldForeground()),
            "--vscode-input-border" to css(ui("Component.borderColor") ?: JBColor.border()),
            "--vscode-input-placeholderForeground" to css(UIUtil.getContextHelpForeground()),
            "--vscode-dropdown-background" to css(ui("ComboBox.background") ?: UIUtil.getTextFieldBackground()),
            "--vscode-dropdown-foreground" to css(ui("ComboBox.foreground") ?: fg),
            "--vscode-dropdown-border" to css(ui("Component.borderColor") ?: JBColor.border()),
            "--vscode-checkbox-border" to css(ui("Component.borderColor") ?: JBColor.border()),
            "--vscode-textLink-foreground" to css(JBUI.CurrentTheme.Link.Foreground.ENABLED),
            "--vscode-textLink-activeForeground" to css(JBUI.CurrentTheme.Link.Foreground.HOVERED),
            "--vscode-textCodeBlock-background" to css(scheme.defaultBackground),
            "--vscode-textPreformat-background" to css(scheme.defaultBackground),
            "--vscode-textPreformat-foreground" to css(scheme.defaultForeground),
            "--vscode-statusBar-background" to css(ui("StatusBar.background") ?: bg),
            "--vscode-statusBar-foreground" to css(fg),
            "--vscode-statusBar-border" to css(ui("StatusBar.borderColor") ?: JBColor.border()),
            "--vscode-charts-red" to if (dark) "#f14c4c" else "#e51400",
            "--vscode-charts-green" to if (dark) "#89d185" else "#388a34",
            "--vscode-charts-yellow" to if (dark) "#cca700" else "#bf8803",
            "--vscode-charts-blue" to if (dark) "#3794ff" else "#1a85ff",
            "--vscode-charts-purple" to if (dark) "#b180d7" else "#652d90",
            "--vscode-font-family" to "\"${font.family}\", system-ui, -apple-system, \"Segoe UI\", sans-serif",
            "--vscode-font-size" to "${Math.round(px)}px",
            "--vscode-editor-font-family" to "\"${scheme.editorFontName}\", monospace",
            "--vscode-editor-font-size" to "${scheme.editorFontSize}px",
        )
        @Suppress("UNCHECKED_CAST")
        return vars.filterValues { it != null } as Map<String, String>
    }

    /** `:root{…}` with all variables. */
    fun rootCss(): String = ":root{" + variables().entries.joinToString(";") { "${it.key}:${it.value}" } + "}"

    /** Body classes the webview scripts look at. */
    fun bodyClass(): String = if (isDark) "vscode-dark" else "vscode-light"
}
