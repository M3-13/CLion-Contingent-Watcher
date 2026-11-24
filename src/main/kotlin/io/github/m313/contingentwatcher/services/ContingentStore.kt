package io.github.m313.contingentwatcher.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import io.github.m313.contingentwatcher.core.ActivityItem
import io.github.m313.contingentwatcher.core.CycleSummary
import io.github.m313.contingentwatcher.core.Cycles
import io.github.m313.contingentwatcher.core.ForecastOptions
import io.github.m313.contingentwatcher.core.HistoryCompaction
import io.github.m313.contingentwatcher.core.HistoryPoint
import io.github.m313.contingentwatcher.core.QuotaSnapshot
import io.github.m313.contingentwatcher.core.Thresholds
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Persists history and notification state locally (counterpart of VS Code's
 * globalState, sync off). Values are JSON strings inside the XML storage.
 */
@Suppress("SENSELESS_COMPARISON") // Gson may leave non-null fields null.
@Service(Service.Level.APP)
@State(
    name = "CopilotContingentWatcherData",
    storages = [Storage(value = "copilotContingentWatcherData.xml", roamingType = RoamingType.DISABLED)],
)
class ContingentStore : PersistentStateComponent<ContingentStore.Data> {
    class Data {
        var history: String = "[]"
        var lastSnapshot: String = ""
        var notifiedCycle: String = ""
        var notifiedThresholds: String = "[]"
        var snoozeUntil: Long = 0
        var paceWarnedDay: String = ""
        var cycles: String = "[]"
        var surplusNotified: String = ""
        var activity: String = "[]"
        var btwChosenModel: String = ""
    }

    private var data = Data()
    private val gson = Gson()

    override fun getState(): Data = data

    override fun loadState(s: Data) {
        XmlSerializerUtil.copyBean(s, data)
    }

    private inline fun <reified T> read(json: String, fallback: T): T = try {
        if (json.isBlank()) fallback else gson.fromJson<T>(json, object : TypeToken<T>() {}.type) ?: fallback
    } catch (_: Exception) {
        fallback
    }

    val last: QuotaSnapshot?
        get() = read<QuotaSnapshot?>(data.lastSnapshot, null)?.takeIf { it.resetDate != null && it.unit != null }

    val history: List<HistoryPoint>
        get() = read<List<HistoryPoint?>>(data.history, emptyList()).filterNotNull()

    val cycles: List<CycleSummary>
        get() = read<List<CycleSummary?>>(data.cycles, emptyList()).filterNotNull().filter { it.resetDate != null }

    /** Stores the snapshot; archives the cycle and clears history when a new cycle has started. */
    @Synchronized
    fun record(snap: QuotaSnapshot) {
        val prev = last
        var history = history.toMutableList()
        val newCycle = prev != null && (Thresholds.cycleKey(prev.resetDate) != Thresholds.cycleKey(snap.resetDate) || snap.used < prev.used)
        if (newCycle && prev != null) {
            // Archive the finished cycle before its history is dropped.
            val summary = Cycles.summarize(prev, history, ForecastOptions(true, 0.0), prev.fetchedAt)
            data.cycles = gson.toJson(Cycles.add(cycles, summary))
            history = ArrayList()
        }
        val lastPt = history.lastOrNull()
        // Only store a point if usage changed or an hour passed: keeps state small.
        if (lastPt == null || lastPt.used != snap.used || snap.fetchedAt - lastPt.t > 3_600_000) {
            history.add(HistoryPoint(snap.fetchedAt, snap.used))
        }
        data.history = gson.toJson(HistoryCompaction.compact(history, snap.fetchedAt))
        data.lastSnapshot = gson.toJson(snap)
    }

    fun surplusNotified(resetDate: String): Boolean = data.surplusNotified == Thresholds.cycleKey(resetDate)

    fun markSurplusNotified(resetDate: String) {
        data.surplusNotified = Thresholds.cycleKey(resetDate)
    }

    fun notifiedThresholds(resetDate: String): List<Double> =
        if (data.notifiedCycle == Thresholds.cycleKey(resetDate)) read<List<Double>>(data.notifiedThresholds, emptyList()) else emptyList()

    /** Marks the crossed threshold and every lower one, so we never spam. */
    fun markNotified(resetDate: String, threshold: Double, all: List<Double>) {
        val merged = (notifiedThresholds(resetDate) + all.filter { it <= threshold }).toSet().toList()
        data.notifiedCycle = Thresholds.cycleKey(resetDate)
        data.notifiedThresholds = gson.toJson(merged)
    }

    val snoozedUntil: Long?
        get() = data.snoozeUntil.takeIf { it > System.currentTimeMillis() }

    val snoozed: Boolean get() = System.currentTimeMillis() < data.snoozeUntil

    fun snooze(hours: Int) {
        data.snoozeUntil = System.currentTimeMillis() + hours * 3_600_000L
    }

    fun unsnooze() {
        data.snoozeUntil = 0
    }

    private fun utcToday(): String = LocalDate.now(ZoneOffset.UTC).toString()

    fun paceWarnedToday(): Boolean = data.paceWarnedDay == utcToday()

    fun markPaceWarned() {
        data.paceWarnedDay = utcToday()
    }

    var activity: List<ActivityItem>
        get() = read<List<ActivityItem?>>(data.activity, emptyList()).filterNotNull().filter { it.id != null }
        set(v) {
            data.activity = gson.toJson(v)
        }

    /** Model the user chose for btw (id, family, name). */
    var btwChosenModel: Triple<String, String, String?>?
        get() {
            val m = read<Map<String, String?>>(data.btwChosenModel, emptyMap())
            val id = m["id"] ?: return null
            val family = m["family"] ?: return null
            return Triple(id, family, m["name"])
        }
        set(v) {
            data.btwChosenModel = if (v == null) "" else gson.toJson(mapOf("id" to v.first, "family" to v.second, "name" to v.third))
        }

    companion object {
        val instance: ContingentStore
            get() = ApplicationManager.getApplication().getService(ContingentStore::class.java)
    }
}
