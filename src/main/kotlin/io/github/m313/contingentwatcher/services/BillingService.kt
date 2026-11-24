package io.github.m313.contingentwatcher.services

import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.github.m313.contingentwatcher.core.BillingParser
import io.github.m313.contingentwatcher.core.ModelUsage
import io.github.m313.contingentwatcher.l10n.L10n
import java.net.URLEncoder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList

sealed class ModelUsageState {
    object NotConfigured : ModelUsageState()
    object Loading : ModelUsageState()
    data class Ok(val items: List<ModelUsage>, val period: ZonedDateTime) : ModelUsageState()
    data class Error(val message: String) : ModelUsageState()
}

/**
 * Optional per-model usage from GitHub's official billing API. Needs a
 * fine-grained token with "Plan" (read) permission, kept in the IDE's password safe.
 */
@Service(Service.Level.APP)
class BillingService {
    @Volatile
    var state: ModelUsageState = ModelUsageState.NotConfigured
        private set

    @Volatile
    var tokenSet: Boolean = false
        private set

    private var fetchedAt = 0L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val log get() = ContingentLog.instance

    init {
        ApplicationManager.getApplication().executeOnPooledThread {
            tokenSet = ManualToken.getBilling() != null
            if (tokenSet) refresh(true)
        }
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    private fun set(s: ModelUsageState) {
        state = s
        listeners.forEach { runCatching { it() } }
    }

    /** Asks for the token (EDT). */
    fun configure(project: Project?) {
        val enter = L10n.t("Enter token")
        val create = L10n.t("Create token on GitHub")
        val choice = Messages.showDialog(
            project,
            L10n.t("Per-model usage needs a fine-grained GitHub token with the account permission \"Plan\" (read). It is stored securely in the IDE's password safe."),
            L10n.t("GitHub token for per-model usage"),
            arrayOf(enter, create, Messages.getCancelButton()),
            0,
            Messages.getInformationIcon(),
        )
        if (choice == 1) {
            BrowserUtil.browse("${Auth.githubWebBase()}/settings/personal-access-tokens/new")
        } else if (choice != 0) {
            return
        }
        val token = Messages.showPasswordDialog(
            project,
            L10n.t("Fine-grained token with \"Plan\" (read) permission"),
            L10n.t("GitHub token for per-model usage"),
            null,
        )?.trim()
        if (token.isNullOrEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            ManualToken.setBilling(token)
            tokenSet = true
            refresh(true)
        }
    }

    fun clear() {
        ApplicationManager.getApplication().executeOnPooledThread {
            ManualToken.setBilling(null)
            tokenSet = false
            set(ModelUsageState.NotConfigured)
        }
    }

    /** Refreshes at most every 30 minutes unless forced. Call off the EDT. */
    fun refresh(force: Boolean = false) {
        val token = ManualToken.getBilling()
        tokenSet = token != null
        if (token == null) {
            set(ModelUsageState.NotConfigured)
            return
        }
        if (!force && System.currentTimeMillis() - fetchedAt < CACHE_MS && state is ModelUsageState.Ok) return
        set(ModelUsageState.Loading)
        val base = Auth.apiBaseFor(Auth.resolveTargets().first())
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )
        try {
            val me = Http.get("$base/user", headers)
            if (!me.ok) throw IllegalStateException(if (me.status == 401) L10n.t("Token invalid or expired") else "HTTP ${me.status}")
            val login = JsonParser.parseString(me.body).asJsonObject.get("login")?.asString ?: ""
            val now = ZonedDateTime.now(ZoneOffset.UTC)
            val url = "$base/users/${URLEncoder.encode(login, "UTF-8")}/settings/billing/premium_request/usage?year=${now.year}&month=${now.monthValue}"
            val res = Http.get(url, headers)
            log.info("[billing] GET premium_request/usage → ${res.status}")
            if (!res.ok) {
                log.warn("[billing] response: ${res.body.take(300)}")
                throw IllegalStateException(if (res.status == 403) L10n.t("Token lacks the \"Plan\" (read) permission") else "HTTP ${res.status}")
            }
            fetchedAt = System.currentTimeMillis()
            set(ModelUsageState.Ok(BillingParser.parseModelUsage(JsonParser.parseString(res.body)), now))
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            log.error("[billing] $message")
            set(ModelUsageState.Error(message))
        }
    }

    companion object {
        private const val CACHE_MS = 30 * 60 * 1000L

        val instance: BillingService
            get() = ApplicationManager.getApplication().getService(BillingService::class.java)
    }
}
