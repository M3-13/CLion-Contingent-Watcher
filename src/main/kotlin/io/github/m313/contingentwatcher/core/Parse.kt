package io.github.m313.contingentwatcher.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Which quota to show when the API reports several. */
enum class UnitPreference(val id: String) {
    AUTO("auto"),
    CREDITS("credits"),
    REQUESTS("requests");

    companion object {
        fun of(id: String?): UnitPreference = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** Defensive JSON helpers shared by the parsers. */
internal object Json {
    fun num(v: JsonElement?): Double? {
        if (v == null || v.isJsonNull || !v.isJsonPrimitive) return null
        val p = v.asJsonPrimitive
        val n = when {
            p.isNumber -> p.asDouble
            p.isString -> p.asString.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            else -> null
        }
        return n?.takeIf { it.isFinite() }
    }

    fun str(v: JsonElement?): String? =
        if (v != null && v.isJsonPrimitive && (v as JsonPrimitive).isString) v.asString else null

    fun bool(v: JsonElement?): Boolean = v != null && v.isJsonPrimitive && v.asJsonPrimitive.isBoolean && v.asBoolean

    fun obj(v: JsonElement?): JsonObject? = if (v != null && v.isJsonObject) v.asJsonObject else null
}

object QuotaParser {
    private val IGNORED = setOf("chat", "completions")
    private val REQUEST_KEYS = listOf("premium_interactions", "premium_requests")
    private val CREDIT = Regex("credit", RegexOption.IGNORE_CASE)
    private val DATE_ONLY = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /** First day of next month, 00:00 UTC: GitHub's documented reset time. */
    fun defaultResetDate(now: Long): String = Time.firstOfNextUtcMonth(now)

    private fun normaliseReset(raw: JsonElement?, now: Long): String {
        val s = Json.str(raw)
        if (!s.isNullOrEmpty()) {
            val iso = if (DATE_ONLY.matches(s)) "${s}T00:00:00Z" else s
            Time.parse(iso)?.let { return Time.iso(it) }
        }
        return defaultResetDate(now)
    }

    /**
     * Parses the (undocumented) `copilot_internal/user` response defensively.
     * Every field is optional; returns null if no metered quota is found.
     */
    fun parseCopilotUser(body: JsonElement?, now: Long = System.currentTimeMillis(), prefer: UnitPreference = UnitPreference.AUTO): QuotaSnapshot? {
        val root = Json.obj(body) ?: return null
        val snapshots = Json.obj(root.get("quota_snapshots")) ?: JsonObject()
        val keys = snapshots.keySet().toList()
        val metered = keys.filter { it !in IGNORED && snapshots.get(it)?.isJsonObject == true }
        fun unlimitedOf(k: String) = Json.bool(snapshots.getAsJsonObject(k).get("unlimited"))
        val credit = metered.firstOrNull { CREDIT.containsMatchIn(it) && !unlimitedOf(it) } ?: metered.firstOrNull { CREDIT.containsMatchIn(it) }
        val request = REQUEST_KEYS.firstOrNull { snapshots.get(it)?.let { v -> !v.isJsonNull } == true }
        // Credits (usage-based billing) are what costs money, so they win in "auto".
        val key = (if (prefer == UnitPreference.REQUESTS) (request ?: credit) else (credit ?: request))
            ?: metered.firstOrNull { !unlimitedOf(it) }
            ?: metered.firstOrNull()
            ?: return null
        val q = Json.obj(snapshots.get(key)) ?: JsonObject()

        val unlimited = Json.bool(q.get("unlimited"))
        val entitlement = Json.num(q.get("entitlement")) ?: 0.0
        var remaining = Json.num(q.get("remaining")) ?: Json.num(q.get("quota_remaining"))
        val pct = Json.num(q.get("percent_remaining"))
        if (remaining == null && pct != null) {
            remaining = entitlement * pct / 100.0
        }
        val rem = maxOf(0.0, remaining ?: entitlement)
        val used = maxOf(0.0, entitlement - rem)

        return QuotaSnapshot(
            entitlement = entitlement,
            used = used,
            remaining = rem,
            unlimited = unlimited,
            overagePermitted = Json.bool(q.get("overage_permitted")),
            overageCount = Json.num(q.get("overage_count")) ?: 0.0,
            resetDate = normaliseReset(root.get("quota_reset_date_utc")?.takeUnless { it.isJsonNull } ?: root.get("quota_reset_date"), now),
            unit = if (CREDIT.containsMatchIn(key)) QuotaUnit.CREDITS else QuotaUnit.REQUESTS,
            quotaId = key,
            plan = Json.str(root.get("copilot_plan")),
            fetchedAt = now,
        )
    }
}
