package io.github.m313.contingentwatcher.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import io.github.m313.contingentwatcher.services.IdeAccount
import io.github.m313.contingentwatcher.services.IdeGitHubAccounts
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount

/**
 * Accounts from Settings | Version Control | GitHub (only loaded when the GitHub
 * plugin is enabled). The GitHub plugin has no public account API, so this uses
 * its account manager; every call is guarded by the caller (see Auth).
 */
class GitHubPluginAccounts : IdeGitHubAccounts {
    private fun manager(): GHAccountManager = ApplicationManager.getApplication().getService(GHAccountManager::class.java)

    override fun accounts(): List<IdeAccount> =
        manager().accountsState.value.map { IdeAccount(it.name, if (it.server.isGithubDotCom) "github.com" else it.server.host.lowercase(), it) }

    override fun token(account: IdeAccount): String? {
        val acc = account.ref as? GithubAccount ?: return null
        // Never call this on the EDT: credentials come from the password safe.
        return runBlocking { manager().findCredentials(acc) }
    }

    /** Opens Settings | Version Control | GitHub, where the user adds the account. */
    override fun requestLogin(host: String, project: Project?): Boolean {
        val before = accounts().count { it.host.equals(host, true) }
        ApplicationManager.getApplication().invokeAndWait({
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "GitHub")
        }, ModalityState.defaultModalityState())
        return accounts().count { it.host.equals(host, true) } > before
    }
}
