package io.github.m313.contingentwatcher.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import io.github.m313.contingentwatcher.core.Endpoints
import io.github.m313.contingentwatcher.l10n.L10n
import io.github.m313.contingentwatcher.services.ContingentSettings
import io.github.m313.contingentwatcher.services.CopilotTokens
import io.github.m313.contingentwatcher.services.IdeGitHubAccounts
import io.github.m313.contingentwatcher.services.ManualToken
import javax.swing.ButtonGroup
import javax.swing.JComponent

/**
 * Guided sign-in: which kind of GitHub account, the enterprise address if
 * needed, and which sign-in to use (Copilot plugin, IDE GitHub account or a token).
 */
object SignInFlow {
    /** Returns false if the user cancelled. EDT. */
    fun run(project: Project?): Boolean {
        val dialog = SignInDialog(project)
        if (!dialog.showAndGet()) return false
        val s = ContingentSettings.instance
        val host = dialog.host()
        val manualToken = String(dialog.token.password).trim()
        s.update {
            it.authProvider = if (dialog.kindGitHub.isSelected) "github" else "github-enterprise"
            if (!dialog.kindGitHub.isSelected) it.enterpriseUri = Endpoints.normaliseHost(dialog.address.text) ?: ""
            it.tokenSource = when {
                dialog.useCopilot.isSelected -> "auto"
                dialog.useIde.isSelected -> "ide"
                else -> "manual"
            }
        }
        if (dialog.useManual.isSelected && manualToken.isNotEmpty()) {
            ApplicationManager.getApplication().executeOnPooledThread { ManualToken.set(host, manualToken) }.get()
        }
        if (dialog.useIde.isSelected) {
            val ide = IdeGitHubAccounts.get()
            val has = ApplicationManager.getApplication().executeOnPooledThread<Boolean> {
                ide?.accounts()?.any { it.host.equals(host, true) } == true
            }.get()
            if (ide != null && !has) ide.requestLogin(host, project)
        }
        return true
    }
}

private class SignInDialog(project: Project?) : DialogWrapper(project) {
    val kindGitHub = JBRadioButton("GitHub.com", true)
    val kindGhe = JBRadioButton(L10n.t("GitHub Enterprise Cloud with own domain"))
    val kindGhes = JBRadioButton(L10n.t("GitHub Enterprise Server"))
    val address = JBTextField(ContingentSettings.instance.state.enterpriseUri, 30)
    val useCopilot = JBRadioButton(L10n.t("Use the sign-in of the GitHub Copilot plugin (recommended)"))
    val useIde = JBRadioButton(L10n.t("Use a GitHub account from the IDE settings (Version Control › GitHub)"))
    val useManual = JBRadioButton(L10n.t("Enter a GitHub token"))
    val token = JBPasswordField()
    private val found = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        title = L10n.t("Which GitHub account do you use Copilot with?")
        ButtonGroup().apply { add(kindGitHub); add(kindGhe); add(kindGhes) }
        ButtonGroup().apply { add(useCopilot); add(useIde); add(useManual) }
        val s = ContingentSettings.instance.state
        when {
            s.authProvider == "github-enterprise" && (Endpoints.hostOf(s.enterpriseUri)?.endsWith(".ghe.com") == true) -> kindGhe.isSelected = true
            s.authProvider == "github-enterprise" -> kindGhes.isSelected = true
        }
        when (s.tokenSource) {
            "ide" -> useIde.isSelected = true
            "manual" -> useManual.isSelected = true
            else -> useCopilot.isSelected = true
        }
        listOf(kindGitHub, kindGhe, kindGhes, useCopilot, useIde, useManual).forEach { it.addActionListener { refreshState() } }
        address.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = refreshState()
        })
        init()
        refreshState()
    }

    fun host(): String = if (kindGitHub.isSelected) "github.com" else Endpoints.hostOf(address.text) ?: ""

    private fun refreshState() {
        address.isEnabled = !kindGitHub.isSelected
        address.emptyText.text = if (kindGhe.isSelected) "https://company.ghe.com" else "https://github.company.com"
        token.isEnabled = useManual.isSelected
        val host = host()
        val copilot = CopilotTokens.read().any { it.host.equals(host, true) }
        found.text = if (host.isEmpty()) {
            ""
        } else if (copilot) {
            L10n.t("GitHub Copilot plugin is signed in for {0}.", host)
        } else {
            L10n.t("No GitHub Copilot plugin sign-in found for {0}.", host)
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(L10n.t("Not sure? Check the address you use to sign in to GitHub in the browser.")).applyToComponent { foreground = UIUtil.getContextHelpForeground() } }
        buttonsGroup {
            row { cell(kindGitHub).comment(L10n.t("Personal account, Copilot Business/Enterprise on github.com, Enterprise Managed Users")) }
            row { cell(kindGhe).comment(L10n.t("Your company signs in at an address like company.ghe.com")) }
            row { cell(kindGhes).comment(L10n.t("Your company runs its own GitHub server, e.g. github.company.com")) }
        }
        row(L10n.t("Address:")) { cell(address).align(AlignX.FILL).comment(L10n.t("The address you open in the browser to use GitHub")) }
        separator()
        buttonsGroup {
            row { cell(useCopilot) }
            row { cell(found) }
            row { cell(useIde).comment(L10n.t("If there is no account for this address yet, the GitHub sign-in opens.")) }
            row { cell(useManual).comment(L10n.t("A classic token with the scope read:user. It is stored in the IDE's password safe.")) }
            row(L10n.t("Token:")) { cell(token).align(AlignX.FILL) }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (!kindGitHub.isSelected && Endpoints.normaliseHost(address.text) == null) {
            return ValidationInfo(L10n.t("Please enter an address like company.ghe.com"), address)
        }
        if (useManual.isSelected && token.password.isEmpty()) {
            return ValidationInfo(L10n.t("Please enter a token"), token)
        }
        if (useIde.isSelected && IdeGitHubAccounts.get() == null) {
            return ValidationInfo(L10n.t("The IDE's GitHub plugin is not enabled."), useIde)
        }
        return null
    }
}
