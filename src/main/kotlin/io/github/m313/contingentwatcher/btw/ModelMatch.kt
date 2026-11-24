package io.github.m313.contingentwatcher.btw

/**
 * Pure matching of a model reference (from a chat session or a setting)
 * against the available models. Never guesses across model variants:
 * "gpt-5" does not match "gpt-5-mini".
 */
interface LmInfo {
    val id: String
    val family: String
    val vendor: String?
    val name: String?
}

object ModelMatch {
    /** Lower-cased reference without a vendor prefix ("copilot/GPT-5" → "gpt-5"). */
    fun stripVendor(ref: String): String {
        val r = ref.trim().lowercase()
        val i = r.lastIndexOf('/')
        return if (i >= 0) r.substring(i + 1) else r
    }

    /** Suffixes that only mark a snapshot of the same model ("gpt-4.1-2025-04-14", "…-preview"). */
    private val VERSION_SUFFIX = Regex("^(\\d{4}|preview|latest|exp)")

    fun <T : LmInfo> match(models: List<T>, ref: String?, allowPrefix: Boolean = false): T? {
        if (ref.isNullOrBlank()) return null
        val full = ref.trim().lowercase()
        val bare = stripVendor(full)
        if (bare.isEmpty()) return null
        fun lc(s: String?) = (s ?: "").trim().lowercase()

        // 1. Exact id (with or without vendor prefix).
        models.firstOrNull { m -> lc(m.id) == bare || lc(m.id) == full || (m.vendor != null && "${lc(m.vendor)}/${lc(m.id)}" == full) }?.let { return it }
        // 2. Exact family, then exact display name.
        models.firstOrNull { lc(it.family) == bare }?.let { return it }
        models.firstOrNull { it.name != null && lc(it.name) == full }?.let { return it }
        // 3. The reference is a dated/preview snapshot of an available family or id.
        var best: T? = null
        var bestLen = 0
        for (m in models) {
            for (c in listOf(lc(m.family), lc(m.id))) {
                if (c.isNotEmpty() && bare.startsWith("$c-") && VERSION_SUFFIX.containsMatchIn(bare.substring(c.length + 1)) && c.length > bestLen) {
                    best = m
                    bestLen = c.length
                }
            }
        }
        if (best != null) return best
        // 4. User-typed prefix ("claude-sonnet" → shortest matching family).
        if (allowPrefix) {
            return models.filter { lc(it.family).startsWith(bare) || lc(it.id).startsWith(bare) }
                .sortedWith(compareBy<T>({ lc(it.family).length }, { lc(it.id).length }))
                .firstOrNull()
        }
        return null
    }

    /** `btw.model`: "chat" (the current chat's model) or "included" (a free model). Anything unknown means "chat". */
    fun btwModeOf(value: String?): String = if (value?.trim()?.lowercase() == "included") "included" else "chat"

    fun matchesFamily(modelFamily: String, preferred: String): Boolean {
        val m = modelFamily.trim().lowercase()
        val p = preferred.trim().lowercase()
        if (m.isEmpty() || p.isEmpty()) return false
        return m == p || m.startsWith("$p-") || m.startsWith(p)
    }

    /**
     * Picks the first available model matching the preferred families, in order of
     * preference. Exact matches win over prefix matches within one preference.
     * Returns null if none match (never falls back to an arbitrary model).
     */
    fun <T : LmInfo> chooseModel(available: List<T>, preferred: List<String>): T? {
        for (pref in preferred) {
            available.firstOrNull { it.family.trim().lowercase() == pref.trim().lowercase() }?.let { return it }
            available.firstOrNull { matchesFamily(it.family, pref) }?.let { return it }
        }
        return null
    }

    fun availableFamilies(available: List<LmInfo>): List<String> = available.map { it.family }.toSortedSet().toList()

    /** Default for `chat.preferredModels` (included / 0x model families). */
    val DEFAULT_PREFERRED_MODELS: List<String> = listOf("gpt-5-mini", "gpt-4.1", "gpt-4o")

    /** Validates the raw setting value; falls back to the defaults when empty. */
    fun normalizePreferred(value: List<String>?): List<String> {
        val list = value.orEmpty().filter { it.isNotBlank() }
        return list.ifEmpty { DEFAULT_PREFERRED_MODELS }
    }
}
