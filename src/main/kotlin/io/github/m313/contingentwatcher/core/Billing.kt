package io.github.m313.contingentwatcher.core

import com.google.gson.JsonElement

data class ModelUsage(
    val model: String,
    /** Requests (or other billed units) used. */
    val quantity: Double,
    /** Credits used (1 credit = $0.01 of gross amount), if the report has amounts. */
    val credits: Double?,
    val share: Double,
)

object BillingParser {
    private class Total(var quantity: Double = 0.0, var amount: Double = 0.0, var hasAmount: Boolean = false)

    /**
     * Parses the billing usage report (premium request usage endpoint) into
     * per-model totals. Tolerant: accepts `usageItems` or `usage_items`, and
     * gross/net/plain quantity fields.
     */
    fun parseModelUsage(body: JsonElement?): List<ModelUsage> {
        val root = Json.obj(body) ?: return emptyList()
        val rawEl = root.get("usageItems")?.takeIf { it.isJsonArray } ?: root.get("usage_items")?.takeIf { it.isJsonArray }
        val raw = rawEl?.asJsonArray ?: return emptyList()
        val totals = LinkedHashMap<String, Total>()
        for (el in raw) {
            val it = Json.obj(el) ?: continue
            val m = Json.str(it.get("model"))
            val model = if (!m.isNullOrEmpty()) m else Json.str(it.get("sku"))
            val q = Json.num(it.get("grossQuantity")) ?: Json.num(it.get("gross_quantity")) ?: Json.num(it.get("quantity"))
                ?: Json.num(it.get("netQuantity")) ?: 0.0
            val amount = Json.num(it.get("grossAmount")) ?: Json.num(it.get("gross_amount")) ?: Json.num(it.get("amount"))
            if (model.isNullOrEmpty() || (q <= 0 && !(amount != null && amount > 0))) continue
            val t = totals.getOrPut(model) { Total() }
            t.quantity += maxOf(0.0, q)
            if (amount != null) {
                t.amount += amount
                t.hasAmount = true
            }
        }
        val withCredits = totals.values.all { it.hasAmount }
        // Share by credits when every row has an amount (that's what costs money).
        fun weight(t: Total) = if (withCredits) t.amount else t.quantity
        val sum = totals.values.sumOf { weight(it) }
        return totals.entries
            .map { (model, t) ->
                ModelUsage(
                    model = model,
                    quantity = t.quantity,
                    credits = if (withCredits) Math.round(t.amount * 100 * 10) / 10.0 else null,
                    share = if (sum > 0) weight(t) / sum else 0.0,
                )
            }
            .sortedByDescending { it.share }
    }
}
