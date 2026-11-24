package io.github.m313.contingentwatcher.core

enum class IconStyle(val id: String) {
    GAUGE("gauge"),
    BATTERY("battery"),
    RING("ring"),
    BARS("bars"),
    NONE("none");

    companion object {
        fun of(id: String?): IconStyle = entries.firstOrNull { it.id == id } ?: GAUGE
    }
}

object Appearance {
    /** Accent presets; tuned to read well on light and dark themes. */
    val ACCENT_PRESETS: Map<String, String> = linkedMapOf(
        "teal" to "#14b8a6",
        "violet" to "#8b5cf6",
        "blue" to "#3b82f6",
        "green" to "#22c55e",
        "orange" to "#f97316",
        "pink" to "#ec4899",
    )

    private val HEX = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

    /** Resolves a preset id or #hex to a colour; null = theme default. */
    fun resolveColor(value: String?): String? {
        if (value.isNullOrEmpty() || value == "auto") return null
        val v = value.trim()
        ACCENT_PRESETS[v]?.let { return it }
        return if (HEX.matches(v)) v else null
    }

    /** True if `value` is a valid colour setting (auto, preset or #hex). */
    fun isValidColorSetting(value: String?): Boolean = value == "auto" || resolveColor(value) != null

    /** Level 0..4 (4 = full) matching the remaining share. */
    fun fillLevel(remaining: Double, entitlement: Double): Int {
        if (entitlement <= 0) return 4
        return Math.ceil(remaining / entitlement * 4).toInt().coerceIn(0, 4)
    }

    /** Icon resource name for the status bar, or null for no icon. */
    fun iconFor(style: IconStyle, level: Int): String? = when (style) {
        IconStyle.NONE -> null
        IconStyle.GAUGE -> "contingent-level-$level"
        else -> "contingent-${style.id}-$level"
    }

    /** "#abc" → "#aabbcc"; other values unchanged. */
    fun hex6(v: String): String {
        val h = v.trim().lowercase()
        return if (h.length == 4 && h.startsWith("#")) "#" + h.drop(1).map { "$it$it" }.joinToString("") else h
    }
}
