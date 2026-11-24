package io.github.m313.contingentwatcher.services

import com.google.gson.JsonParser
import io.github.m313.contingentwatcher.core.QuotaParser
import io.github.m313.contingentwatcher.core.QuotaSnapshot
import io.github.m313.contingentwatcher.core.UnitPreference

sealed class FetchResult {
    abstract val target: AuthTarget

    data class Ok(val snapshot: QuotaSnapshot, override val target: AuthTarget) : FetchResult()
    data class SignedOut(override val target: AuthTarget) : FetchResult()
    data class NoCopilot(override val target: AuthTarget, val status: Int? = null) : FetchResult()
    data class Error(override val target: AuthTarget, val message: String) : FetchResult()
}

/**
 * Reads the Copilot quota with a GitHub token the IDE already has (Copilot
 * plugin sign-in, IDE GitHub account) or one entered in the settings.
 */
object QuotaService {
    private val log get() = ContingentLog.instance

    fun fetch(): FetchResult {
        val targets = Auth.resolveTargets()
        var first: FetchResult? = null
        for (target in targets) {
            val res = fetchFrom(target)
            if (res is FetchResult.Ok) return res
            if (first == null) first = res
            // A real answer from the server (not just "no token") ends the search.
            if (res !is FetchResult.SignedOut) return res
        }
        return first!!
    }

    private fun fetchFrom(target: AuthTarget): FetchResult {
        if (target.isEnterprise && target.enterpriseHost == null && target.apiUrl == null) {
            log.info("[${target.provider}] no enterprise address configured")
            return FetchResult.SignedOut(target)
        }
        val token = Auth.findToken(target)
        if (token == null) {
            log.info("[${target.provider}] no token available for ${target.host}")
            return FetchResult.SignedOut(target)
        }
        val url = "${Auth.apiBaseFor(target)}/copilot_internal/user"
        return try {
            val res = Http.get(
                url,
                mapOf(
                    "Authorization" to "token ${token.token}",
                    "Accept" to "application/json",
                    "Editor-Version" to Http.editorVersion,
                    "Editor-Plugin-Version" to Http.pluginVersion,
                ),
            )
            log.info("[${target.provider}] GET $url → ${res.status} (token: ${token.source})")
            if (!res.ok) {
                // Body may explain SSO/policy issues; never contains the token.
                log.warn("[${target.provider}] response: ${res.body.take(300)}")
                return when (res.status) {
                    401 -> FetchResult.SignedOut(target)
                    403, 404 -> FetchResult.NoCopilot(target, res.status)
                    else -> FetchResult.Error(target, "HTTP ${res.status}")
                }
            }
            val json = JsonParser.parseString(res.body)
            val snaps = json.asJsonObject?.getAsJsonObject("quota_snapshots")
            // Shapes only (no personal data): helps to see whether credits or requests are reported.
            log.info(
                "[${target.provider}] quotas: " + (
                    snaps?.entrySet()?.joinToString(", ") { (k, v) ->
                        val o = if (v.isJsonObject) v.asJsonObject else null
                        "$k(entitlement=${o?.get("entitlement") ?: "?"}, unlimited=${o?.get("unlimited") ?: "?"})"
                    }?.ifEmpty { null } ?: "(none)"
                    ),
            )
            val snapshot = QuotaParser.parseCopilotUser(json, System.currentTimeMillis(), UnitPreference.of(ContingentSettings.instance.state.unit))
            if (snapshot != null) FetchResult.Ok(snapshot, target) else FetchResult.NoCopilot(target)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            log.error("[${target.provider}] GET $url failed: $message")
            FetchResult.Error(target, message)
        }
    }
}
