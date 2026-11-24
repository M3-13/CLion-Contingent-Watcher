package io.github.m313.contingentwatcher.services

import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import io.github.m313.contingentwatcher.core.Endpoints
import java.io.File

/** "github" (github.com) or "github-enterprise" (GHE.com tenant / GHE Server). */
data class AuthTarget(
    val provider: String,
    /** Host for GitHub Enterprise, null for github.com. */
    val enterpriseHost: String? = null,
    /** Explicit API base URL override. */
    val apiUrl: String? = null,
) {
    val host: String get() = if (provider == "github-enterprise" && enterpriseHost != null) enterpriseHost else "github.com"
    val isEnterprise: Boolean get() = provider == "github-enterprise"
}

/** A token found for a host, and where it came from (for the log; never the token itself). */
data class TokenInfo(val token: String, val source: String, val login: String? = null)

/** An account from the IDE's GitHub settings (Settings | Version Control | GitHub). */
data class IdeAccount(val name: String, val host: String, val ref: Any)

/** Implemented by the optional GitHub plugin integration. */
interface IdeGitHubAccounts {
    fun accounts(): List<IdeAccount>
    fun token(account: IdeAccount): String?
    /** Shows the GitHub plugin's own login dialog; true if an account was added. */
    fun requestLogin(host: String, project: Project?): Boolean

    companion object {
        val EP: ExtensionPointName<IdeGitHubAccounts> = ExtensionPointName.create("io.github.m313.contingentwatcher.ideGitHubAccounts")

        fun get(): IdeGitHubAccounts? = try {
            EP.extensionList.firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }
}

object Auth {
    private val log get() = ContingentLog.instance
    private val settings get() = ContingentSettings.instance.state

    fun apiBaseFor(t: AuthTarget): String {
        t.apiUrl?.takeIf { it.isNotBlank() }?.let { return it.trim().trimEnd('/') }
        return if (t.isEnterprise && t.enterpriseHost != null) Endpoints.apiBaseForHost(t.enterpriseHost) else "https://api.github.com"
    }

    /** Web URL of the user's GitHub (github.com or the enterprise host). */
    fun githubWebBase(): String {
        if (settings.authProvider != "github") {
            enterpriseHost()?.let { return "https://$it" }
        }
        return "https://github.com"
    }

    /** The enterprise host: the setting, else an enterprise account in the IDE or the Copilot plugin. */
    fun enterpriseHost(): String? {
        Endpoints.hostOf(settings.enterpriseUri)?.let { return it }
        IdeGitHubAccounts.get()?.let { ide ->
            try {
                ide.accounts().firstOrNull { !isDotCom(it.host) }?.let { return it.host }
            } catch (_: Throwable) {
            }
        }
        return CopilotTokens.read().firstOrNull { !isDotCom(it.host) }?.host
    }

    private fun isDotCom(host: String) = host.equals("github.com", true) || host.equals("api.github.com", true)

    /** Copilot itself signed in with an enterprise account. */
    private fun copilotUsesEnterprise(): Boolean {
        val entries = CopilotTokens.read()
        return entries.isNotEmpty() && entries.none { isDotCom(it.host) }
    }

    /**
     * Ordered list of targets to try. In "auto" mode we follow Copilot's own
     * sign-in and fall back to the other provider if no token exists.
     */
    fun resolveTargets(): List<AuthTarget> {
        val apiUrl = settings.apiUrl.trim().ifEmpty { null }
        val host = enterpriseHost()
        val gh = AuthTarget("github", null, apiUrl)
        val ghe = AuthTarget("github-enterprise", host, apiUrl)
        return when (settings.authProvider) {
            "github" -> listOf(gh)
            "github-enterprise" -> listOf(ghe)
            else -> {
                val preferEnterprise = copilotUsesEnterprise() || Endpoints.hostOf(settings.enterpriseUri) != null
                when {
                    preferEnterprise -> if (host != null) listOf(ghe, gh) else listOf(ghe)
                    host != null -> listOf(gh, ghe)
                    else -> listOf(gh)
                }
            }
        }
    }

    /** Finds a token for the target's host from the configured sources. Never shows UI. */
    fun findToken(target: AuthTarget): TokenInfo? {
        val host = target.host
        val sources = when (settings.tokenSource) {
            "copilot" -> listOf("copilot")
            "ide" -> listOf("ide")
            "manual" -> listOf("manual")
            else -> listOf("copilot", "ide", "manual")
        }
        for (s in sources) {
            val found = try {
                when (s) {
                    "copilot" -> CopilotTokens.read().firstOrNull { it.host.equals(host, true) }?.let { TokenInfo(it.token, "GitHub Copilot plugin", it.user) }
                    "ide" -> IdeGitHubAccounts.get()?.let { ide ->
                        ide.accounts().filter { it.host.equals(host, true) }.firstNotNullOfOrNull { acc ->
                            ide.token(acc)?.let { TokenInfo(it, "IDE GitHub account", acc.name) }
                        }
                    }
                    else -> ManualToken.get(host)?.let { TokenInfo(it, "token entered in the settings") }
                }
            } catch (e: Throwable) {
                log.debug("[${target.provider}] token source $s failed: ${e.message}")
                null
            }
            if (found != null) {
                log.debug("[${target.provider}] using token from $s${found.login?.let { " for $it" } ?: ""}")
                return found
            }
        }
        return null
    }

    /** The Copilot plugin's own token for the host (needed for the Copilot model API used by btw). */
    fun copilotToken(target: AuthTarget): TokenInfo? =
        CopilotTokens.read().firstOrNull { it.host.equals(target.host, true) }?.let { TokenInfo(it.token, "GitHub Copilot plugin", it.user) }
}

/**
 * Reads the sign-in of the GitHub Copilot plugin (apps.json / hosts.json in its
 * config folder). Read-only: this plugin stores no token of its own.
 */
object CopilotTokens {
    data class Entry(val host: String, val user: String?, val token: String)

    fun configDir(): File {
        if (SystemInfo.isWindows) {
            val local = System.getenv("LOCALAPPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Local")
            return File(local, "github-copilot")
        }
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        return File(xdg ?: (System.getProperty("user.home") + "/.config"), "github-copilot")
    }

    fun read(): List<Entry> {
        val out = ArrayList<Entry>()
        for (name in listOf("apps.json", "hosts.json")) {
            val f = File(configDir(), name)
            if (!f.isFile || f.length() > 1_000_000) continue
            try {
                val obj = JsonParser.parseString(f.readText()).asJsonObject
                for ((key, v) in obj.entrySet()) {
                    if (!v.isJsonObject) continue
                    val o = v.asJsonObject
                    val token = o.get("oauth_token")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    val host = key.substringBefore(':').trim().lowercase()
                    if (host.isEmpty() || token.isBlank()) continue
                    out.add(Entry(host, o.get("user")?.takeIf { it.isJsonPrimitive }?.asString, token))
                }
            } catch (_: Exception) {
                // Unreadable or unknown format: ignore.
            }
        }
        return out.distinctBy { it.host to it.token }
    }
}

/** A token the user entered manually, kept in the IDE's password safe. */
object ManualToken {
    private fun attrs(key: String) = CredentialAttributes(generateServiceName("Copilot Contingent Watcher", key))

    fun get(host: String): String? = PasswordSafe.instance.getPassword(attrs("github-token@$host"))?.takeIf { it.isNotBlank() }

    fun set(host: String, token: String?) {
        PasswordSafe.instance.set(attrs("github-token@$host"), token?.let { Credentials("token", it) })
    }

    fun getBilling(): String? = PasswordSafe.instance.getPassword(attrs("billing-token"))?.takeIf { it.isNotBlank() }

    fun setBilling(token: String?) {
        PasswordSafe.instance.set(attrs("billing-token"), token?.let { Credentials("token", it) })
    }
}
