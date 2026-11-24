package io.github.m313.contingentwatcher.btw

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.m313.contingentwatcher.l10n.L10n
import io.github.m313.contingentwatcher.services.Auth
import io.github.m313.contingentwatcher.services.ContingentLog
import io.github.m313.contingentwatcher.services.Http

/** A chat model offered by Copilot. */
data class CopilotModel(
    override val id: String,
    override val family: String,
    override val name: String?,
    override val vendor: String? = "copilot",
    /** Premium-request multiplier (0 = included), if reported. */
    val multiplier: Double? = null,
    val premium: Boolean? = null,
) : LmInfo

/**
 * Access to Copilot's chat models with the GitHub Copilot plugin's own sign-in:
 * exchanges the GitHub token for a short-lived Copilot token, lists the chat
 * models and streams completions. Uses the user's own Copilot subscription.
 */
object CopilotApi {
    private data class Session(val token: String, val api: String, val expiresAt: Long)

    @Volatile
    private var session: Session? = null

    @Volatile
    private var modelsCache: Pair<Long, List<CopilotModel>>? = null

    class ApiException(message: String) : Exception(message)

    private val log get() = ContingentLog.instance

    private fun headers(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Editor-Version" to Http.editorVersion,
        "Editor-Plugin-Version" to Http.pluginVersion,
        "Copilot-Integration-Id" to "jetbrains-chat",
        "Openai-Intent" to "conversation-panel",
        "Accept" to "application/json",
    )

    private fun session(): Session {
        session?.takeIf { it.expiresAt - 60_000 > System.currentTimeMillis() }?.let { return it }
        val target = Auth.resolveTargets().first()
        val gh = Auth.copilotToken(target) ?: Auth.findToken(target)
            ?: throw ApiException(L10n.t("No Copilot sign-in found. Sign in to the GitHub Copilot plugin, then try again."))
        val url = "${Auth.apiBaseFor(target)}/copilot_internal/v2/token"
        val res = Http.get(
            url,
            mapOf(
                "Authorization" to "token ${gh.token}",
                "Accept" to "application/json",
                "Editor-Version" to Http.editorVersion,
                "Editor-Plugin-Version" to Http.pluginVersion,
            ),
        )
        log.info("btw: GET $url → ${res.status} (token: ${gh.source})")
        if (!res.ok) {
            log.warn("btw: response: ${res.body.take(300)}")
            throw ApiException(
                if (res.status == 401 || res.status == 403 || res.status == 404) {
                    L10n.t("Copilot did not accept the sign-in ({0}). Sign in to the GitHub Copilot plugin, then try again.", "HTTP ${res.status}")
                } else {
                    L10n.t("The model request failed: {0}", "HTTP ${res.status}")
                },
            )
        }
        val o = JsonParser.parseString(res.body).asJsonObject
        val token = o.get("token")?.asString ?: throw ApiException(L10n.t("The model request failed: {0}", "no token"))
        val api = o.getAsJsonObject("endpoints")?.get("api")?.asString?.trimEnd('/') ?: "https://api.githubcopilot.com"
        val expires = (o.get("expires_at")?.asLong ?: (System.currentTimeMillis() / 1000 + 600)) * 1000
        return Session(token, api, expires).also { session = it }
    }

    fun reset() {
        session = null
        modelsCache = null
    }

    /** Copilot chat models usable via /chat/completions (cached for 10 minutes). */
    fun models(): List<CopilotModel> {
        modelsCache?.takeIf { System.currentTimeMillis() - it.first < 600_000 }?.let { return it.second }
        val s = session()
        val res = Http.get("${s.api}/models", headers(s.token))
        log.info("btw: GET models → ${res.status}")
        if (!res.ok) throw ApiException(L10n.t("Could not list Copilot models: {0}", "HTTP ${res.status}"))
        val data = JsonParser.parseString(res.body).asJsonObject.getAsJsonArray("data") ?: JsonArray()
        val out = ArrayList<CopilotModel>()
        for (el in data) {
            val m = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val caps = m.getAsJsonObject("capabilities")
            if (caps?.get("type")?.asString != "chat") continue
            if (m.get("model_picker_enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) continue
            val policy = m.getAsJsonObject("policy")?.get("state")?.asString
            if (policy != null && policy != "enabled") continue
            val endpoints = m.getAsJsonArray("supported_endpoints")
            if (endpoints != null && endpoints.size() > 0 && endpoints.none { it.asString == "/chat/completions" }) continue
            val id = m.get("id")?.asString ?: continue
            val billing = m.getAsJsonObject("billing")
            out.add(
                CopilotModel(
                    id = id,
                    family = caps.get("family")?.asString ?: id,
                    name = m.get("name")?.asString,
                    vendor = "copilot",
                    multiplier = billing?.get("multiplier")?.takeIf { it.isJsonPrimitive }?.asDouble,
                    premium = billing?.get("is_premium")?.takeIf { it.isJsonPrimitive }?.asBoolean,
                ),
            )
        }
        val list = out.distinctBy { it.id }
        modelsCache = System.currentTimeMillis() to list
        return list
    }

    /**
     * Streams an answer. `onText` gets each fragment; `isCancelled` is polled.
     * `register` receives a callback that aborts the HTTP request.
     */
    fun stream(model: CopilotModel, messages: List<String>, onText: (String) -> Unit, isCancelled: () -> Boolean, register: (() -> Unit) -> Unit) {
        val s = session()
        val body = JsonObject().apply {
            addProperty("model", model.id)
            addProperty("stream", true)
            addProperty("n", 1)
            add(
                "messages",
                JsonArray().apply {
                    messages.forEach { text ->
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", text)
                        })
                    }
                },
            )
        }
        val res = Http.postStream("${s.api}/chat/completions", headers(s.token) + ("Accept" to "text/event-stream"), body.toString(), register) { line ->
            if (isCancelled()) return@postStream false
            val l = line.trim()
            if (!l.startsWith("data:")) return@postStream true
            val payload = l.removePrefix("data:").trim()
            if (payload == "[DONE]") return@postStream false
            try {
                val o = JsonParser.parseString(payload).asJsonObject
                val choices = o.getAsJsonArray("choices") ?: return@postStream true
                for (c in choices) {
                    val delta = c.asJsonObject.getAsJsonObject("delta") ?: continue
                    val content = delta.get("content")
                    if (content != null && content.isJsonPrimitive) onText(content.asString)
                }
            } catch (_: Exception) {
                // Keep-alive or unknown event.
            }
            true
        }
        log.info("btw: POST chat/completions (${model.id}) → ${res.status}")
        if (!res.ok && !isCancelled()) {
            log.warn("btw: response: ${res.body.take(300)}")
            if (res.status == 401) session = null
            throw ApiException(
                when (res.status) {
                    403, 429 -> L10n.t("The request was blocked (content filter or quota).")
                    404 -> L10n.t("The model was not found. It may have been retired; check your preferred models setting.")
                    else -> L10n.t("The model request failed: {0}", "HTTP ${res.status}")
                },
            )
        }
    }
}
