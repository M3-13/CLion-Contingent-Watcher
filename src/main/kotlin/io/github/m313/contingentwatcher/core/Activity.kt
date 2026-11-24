package io.github.m313.contingentwatcher.core

/**
 * Pure bookkeeping for "what did I work on and how long did it take":
 * active editor time and quota usage per git branch. Default branches are
 * split per day, so "main" doesn't become one endless item.
 */
data class ActivityKey(
    /** Repository folder name (or project name without git). */
    val repo: String,
    /** Branch name, null without git. */
    val branch: String? = null,
)

data class ActivityItem(
    val id: String,
    val repo: String,
    val branch: String? = null,
    /** Local date (YYYY-MM-DD) for per-day items. */
    val day: String? = null,
    val detail: String? = null,
    val start: Long,
    val end: Long,
    val activeMs: Long,
    val used: Double,
)

object ActivityBook {
    const val MAX_ITEMS = 100
    /** No editor activity for this long counts as a pause. */
    const val IDLE_MS = 5 * 60 * 1000L
    /** An item counts as "active" if it was touched this recently. */
    const val ACTIVE_WINDOW_MS = 10 * 60 * 1000L

    private val DEFAULT_BRANCHES = setOf("main", "master", "develop", "dev", "trunk")
    private val DETACHED = Regex("^HEAD|detached", RegexOption.IGNORE_CASE)

    fun localDate(t: Long): String = Time.localIsoDate(t)

    fun isPerDay(key: ActivityKey): Boolean =
        key.branch == null || key.branch.lowercase() in DEFAULT_BRANCHES || DETACHED.containsMatchIn(key.branch)

    fun itemId(key: ActivityKey, now: Long): String {
        val base = "${key.repo}::${key.branch ?: ""}"
        return if (isPerDay(key)) "$base::${localDate(now)}" else base
    }

    private fun upsert(items: MutableList<ActivityItem>, key: ActivityKey, now: Long): Int {
        val id = itemId(key, now)
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) return idx
        items.add(
            0,
            ActivityItem(
                id = id,
                repo = key.repo,
                branch = key.branch,
                day = if (isPerDay(key)) localDate(now) else null,
                start = now,
                end = now,
                activeMs = 0,
                used = 0.0,
            ),
        )
        while (items.size > MAX_ITEMS) items.removeAt(items.size - 1)
        return 0
    }

    /** Adds active time to the current item. Returns a new list. */
    fun addActiveTime(items: List<ActivityItem>, key: ActivityKey, now: Long, deltaMs: Long, detail: String? = null): List<ActivityItem> {
        val copy = items.toMutableList()
        val i = upsert(copy, key, now)
        val it = copy[i]
        copy[i] = it.copy(activeMs = it.activeMs + maxOf(0L, deltaMs), end = now, detail = if (!detail.isNullOrEmpty()) detail else it.detail)
        return sortItems(copy)
    }

    /** Attributes quota usage to the current item (only if positive). */
    fun addUsage(items: List<ActivityItem>, key: ActivityKey, now: Long, used: Double): List<ActivityItem> {
        if (!(used > 0)) return items
        val copy = items.toMutableList()
        val i = upsert(copy, key, now)
        val it = copy[i]
        copy[i] = it.copy(used = it.used + used, end = now)
        return sortItems(copy)
    }

    private fun sortItems(items: List<ActivityItem>): List<ActivityItem> = items.sortedByDescending { it.end }.take(MAX_ITEMS)

    /** True if the item is the one currently being worked on. */
    fun isActive(item: ActivityItem, current: ActivityKey?, now: Long): Boolean =
        current != null && item.id == itemId(current, now) && now - item.end < ACTIVE_WINDOW_MS
}
