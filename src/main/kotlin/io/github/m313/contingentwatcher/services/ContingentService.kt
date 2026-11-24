package io.github.m313.contingentwatcher.services

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.m313.contingentwatcher.btw.CopilotApi
import io.github.m313.contingentwatcher.core.Appearance
import io.github.m313.contingentwatcher.core.Cycles
import io.github.m313.contingentwatcher.core.Forecast
import io.github.m313.contingentwatcher.core.ForecastCalc
import io.github.m313.contingentwatcher.core.MetricsCalc
import io.github.m313.contingentwatcher.core.QuotaSnapshot
import io.github.m313.contingentwatcher.core.Surplus
import io.github.m313.contingentwatcher.core.Time
import io.github.m313.contingentwatcher.core.Tips
import io.github.m313.contingentwatcher.l10n.L10n
import io.github.m313.contingentwatcher.ui.DashboardStrings
import io.github.m313.contingentwatcher.ui.SignInFlow
import io.github.m313.contingentwatcher.ui.Ui
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class ViewState {
    object Loading : ViewState()
    data class SignedOut(val provider: String) : ViewState()
    data class NoCopilot(val provider: String, val status: Int?) : ViewState()
    data class Error(val message: String, val stale: Pair<QuotaSnapshot, Forecast>?) : ViewState()
    data class Ok(val snap: QuotaSnapshot, val forecast: Forecast) : ViewState()
}

/**
 * The controller: refreshes the quota, keeps the current view state, builds the
 * dashboard model and handles dashboard commands and settings.
 */
@Service(Service.Level.APP)
class ContingentService : Disposable {
    @Volatile
    var view: ViewState = ViewState.Loading
        private set

    @Volatile
    var provider: String? = null
        private set

    private val started = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private var timer: ScheduledFuture<*>? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val settings get() = ContingentSettings.instance
    private val store get() = ContingentStore.instance
    private val log get() = ContingentLog.instance

    init {
        store.last?.let { view = ViewState.Ok(it, forecast(it)) }
        val bus = ApplicationManager.getApplication().messageBus.connect(this)
        bus.subscribe(SettingsListener.TOPIC, SettingsListener { onSettingsChanged() })
        bus.subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) = onFocus()
            },
        )
        BillingService.instance.addListener { render() }
        ActivityTracker.instance.addListener { render() }
    }

    /** Called when re-rendering is needed (status bar widgets, dashboards). Runs on the EDT. */
    fun addListener(parent: Disposable, l: () -> Unit) {
        listeners.add(l)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(l) }
    }

    fun render() {
        ApplicationManager.getApplication().invokeLater({ listeners.forEach { runCatching { it() } } }, ModalityState.any())
    }

    fun forecast(snap: QuotaSnapshot): Forecast = ForecastCalc.compute(snap, store.history, settings.forecastOptions)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        refresh()
        schedule()
    }

    private fun schedule() {
        timer?.cancel(false)
        val minutes = settings.refreshIntervalMinutes.toLong()
        timer = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            {
                // Don't poll while the IDE is in the background; refresh on focus instead.
                if (ApplicationManager.getApplication().isActive) refresh()
            },
            minutes,
            minutes,
            TimeUnit.MINUTES,
        )
    }

    private var lastInterval = -1
    private var lastUnit = ""
    private var lastAuth = ""

    private fun onSettingsChanged() {
        val s = settings.state
        if (lastInterval != settings.refreshIntervalMinutes && started.get()) schedule()
        lastInterval = settings.refreshIntervalMinutes
        ActivityTracker.instance.setEnabled(s.activityEnabled)
        val authKey = listOf(s.authProvider, s.apiUrl, s.enterpriseUri, s.tokenSource).joinToString("|")
        val needsFetch = (lastUnit.isNotEmpty() && lastUnit != s.unit) || (lastAuth.isNotEmpty() && lastAuth != authKey)
        lastUnit = s.unit
        lastAuth = authKey
        if (needsFetch) {
            CopilotApi.reset()
            refresh()
        }
        val v = view
        if (v is ViewState.Ok) view = v.copy(forecast = forecast(v.snap))
        render()
    }

    private fun onFocus() {
        val last = store.last
        // Refresh on focus if data is older than 2 minutes.
        if (started.get() && (last == null || System.currentTimeMillis() - last.fetchedAt > 120_000)) refresh()
    }

    /** Refreshes in the background (one at a time). `done` runs on the EDT afterwards. */
    fun refresh(done: (() -> Unit)? = null) {
        if (!inFlight.compareAndSet(false, true)) {
            done?.let { ApplicationManager.getApplication().invokeLater(it) }
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                doRefresh()
            } catch (e: Throwable) {
                log.error("refresh failed: ${e.message}")
            } finally {
                inFlight.set(false)
                done?.let { ApplicationManager.getApplication().invokeLater(it) }
            }
        }
    }

    private fun doRefresh() {
        val res = QuotaService.fetch()
        provider = res.target.provider
        when (res) {
            is FetchResult.Ok -> {
                val prev = store.last
                store.record(res.snapshot)
                if (prev != null && prev.resetDate == res.snapshot.resetDate && res.snapshot.used > prev.used) {
                    ActivityTracker.instance.recordUsage(res.snapshot.used - prev.used)
                }
                ApplicationManager.getApplication().executeOnPooledThread { BillingService.instance.refresh() }
                val f = forecast(res.snapshot)
                view = ViewState.Ok(res.snapshot, f)
                render()
                ApplicationManager.getApplication().invokeLater { Notifier.maybeNotify(res.snapshot, f) }
                return
            }
            is FetchResult.Error -> {
                val last = store.last
                view = ViewState.Error(res.message, last?.let { it to forecast(it) })
            }
            is FetchResult.SignedOut -> view = ViewState.SignedOut(res.target.provider)
            is FetchResult.NoCopilot -> view = ViewState.NoCopilot(res.target.provider, res.status)
        }
        render()
    }

    /** Guided sign-in (account type, enterprise address, token source), then refresh. EDT. */
    fun signIn(project: Project?) {
        if (!SignInFlow.run(project)) return
        CopilotApi.reset()
        refresh {
            val v = view
            if (v is ViewState.SignedOut || v is ViewState.NoCopilot || (v is ViewState.Error && v.stale == null)) {
                val choice = Messages.showDialog(
                    project,
                    L10n.t("Sign-in did not work. The log shows which address was tried and what GitHub answered."),
                    L10n.t("Copilot Contingent"),
                    arrayOf(L10n.t("Show log"), L10n.t("Try again"), Messages.getCancelButton()),
                    0,
                    Messages.getWarningIcon(),
                )
                when (choice) {
                    0 -> Ui.showLog(project)
                    1 -> signIn(project)
                }
            }
        }
    }

    // ------------------------------------------------------------------ dashboard model

    /** Builds the dashboard model (see dashboard.js) from the current state. Call on the EDT. */
    fun model(project: Project?): Map<String, Any?> {
        val v = view
        val data: Pair<QuotaSnapshot, Forecast>? = when (v) {
            is ViewState.Ok -> v.snap to v.forecast
            is ViewState.Error -> v.stale
            else -> null
        }
        val s = settings.state
        val state = when {
            data != null -> "ok"
            v is ViewState.Loading -> "loading"
            v is ViewState.SignedOut -> "signedOut"
            v is ViewState.NoCopilot -> "noCopilot"
            else -> "error"
        }
        val copilot = CopilotOptions.state(project)
        val lang = copilot.language
        val model = linkedMapOf<String, Any?>(
            "state" to state,
            "stale" to (v is ViewState.Error && v.stale != null),
            "errorMessage" to (v as? ViewState.Error)?.message,
            "provider" to provider,
            "locale" to L10n.language,
            "strings" to DashboardStrings.all(),
            "appearance" to mapOf("accentColor" to Appearance.resolveColor(settings.accent)),
            "models" to modelsSection(data?.first),
            "review" to reviewSection(data?.first),
            "activity" to activitySection(),
            "tips" to (
                data?.let { (snap, f) ->
                    Tips.relevant(
                        Tips.situationsFor(Tips.SituationInput(f.status, f.cycleElapsed, Surplus.expected(snap, f) != null)),
                        LocalDate.now().dayOfMonth.toDouble(),
                    ).map { mapOf("id" to it.id, "text" to L10n.t(it.text), "relevant" to true) }
                } ?: emptyList<Any>()
                ),
            "settings" to linkedMapOf(
                "display" to settings.display,
                "countWeekends" to s.countWeekends,
                "reserve" to settings.reserve.toInt(),
                "thresholds" to settings.thresholds.map { it.toInt() },
                "warningsEnabled" to s.warningsEnabled,
                "paceWarning" to s.paceWarning,
                "tipsEnabled" to s.tipsEnabled,
                "authProvider" to s.authProvider,
                "snoozedUntil" to store.snoozedUntil,
                "copilotCompletions" to copilot.completions,
                "copilotNextEdit" to copilot.nextEdit,
                "copilotLanguage" to lang?.let { mapOf("id" to it.first, "label" to it.first, "enabled" to it.second) },
                "copilotSnoozeAvailable" to copilot.snoozeAvailable,
                "refreshIntervalMinutes" to settings.refreshIntervalMinutes,
                "apiUrl" to s.apiUrl,
                "preferredModels" to settings.preferredModels,
                "btwIncludeChat" to s.btwIncludeChat,
                "btwModel" to (if (s.btwModel == "included") "included" else "chat"),
                "btwOpenIn" to (if (s.btwOpenIn == "window") "window" else "side"),
                "unit" to (s.unit.takeIf { it == "credits" || it == "requests" } ?: "auto"),
                "accent" to settings.accent,
                "statusBarIcon" to settings.iconStyle.id,
                "statusBarColor" to settings.statusBarColor,
                "surplusHint" to s.surplusHint,
                "activityTracking" to s.activityEnabled,
                "billingTokenSet" to BillingService.instance.tokenSet,
            ),
        )
        if (data != null) {
            val (snap, f) = data
            val m = MetricsCalc.compute(snap, store.history, settings.forecastOptions)
            model["quota"] = linkedMapOf(
                "unitLabel" to Format.unitLabel(snap),
                "entitlement" to snap.entitlement,
                "used" to snap.used,
                "remaining" to snap.remaining,
                "usedPercent" to f.usedPercent,
                "unlimited" to snap.unlimited,
                "overagePermitted" to snap.overagePermitted,
                "overageCount" to snap.overageCount,
                "resetDate" to snap.resetDate,
                "plan" to snap.plan,
                "fetchedAt" to snap.fetchedAt,
            )
            model["forecast"] = linkedMapOf(
                "status" to f.status.id,
                "statusLabel" to Format.statusLabel(f),
                "cycleElapsed" to f.cycleElapsed,
                "paceRatio" to (if (f.paceRatio.isFinite()) f.paceRatio else 99.0),
                "daysLeft" to f.daysLeft,
                "dailyBudget" to f.dailyBudget,
                "burnRate" to f.burnRate,
                "projectedUsed" to f.projectedUsed,
                "exhaustionDate" to f.exhaustionDate?.let { Time.iso(it) },
            )
            model["metrics"] = linkedMapOf(
                "today" to m.today,
                "last7Days" to m.last7Days,
                "cycleAverage" to m.cycleAverage,
                "buffer" to m.buffer,
                "bufferDays" to m.bufferDays,
                "streakDays" to m.streakDays,
                "daily" to m.daily.map {
                    mapOf("date" to it.date, "used" to it.used, "weekend" to it.weekend, "estimated" to it.estimated, "exhausted" to it.exhausted)
                },
                "planPerDay" to m.planPerDay,
                "demand" to m.demand?.let { mapOf("estimate" to it.estimate, "overBy" to it.overBy, "exhaustedOn" to it.exhaustedOn) },
            )
            val unused = if (s.surplusHint) Surplus.expected(snap, f) else null
            if (unused != null) model["surplus"] = mapOf("expectedUnused" to unused, "message" to Format.surplusMessage(snap, unused))
        }
        return model
    }

    private fun modelsSection(snap: QuotaSnapshot?): Map<String, Any?> {
        val b = BillingService.instance.state
        // Company seats are billed to the organization; the personal report then has no per-model data.
        if (snap?.plan != null && Regex("business|enterprise", RegexOption.IGNORE_CASE).containsMatchIn(snap.plan) && b !is ModelUsageState.Ok) {
            return mapOf(
                "state" to "error",
                "errorMessage" to L10n.t("Your Copilot seat is billed to your organization, so GitHub most likely shows per-model usage only to its admins."),
                "items" to emptyList<Any>(),
            )
        }
        return when (b) {
            is ModelUsageState.Ok -> mapOf(
                "state" to "ok",
                "items" to b.items.map { mapOf("model" to it.model, "quantity" to it.quantity, "credits" to it.credits, "share" to it.share) },
                "periodLabel" to Format.monthLabel(b.period.toInstant().toEpochMilli()),
                "showCredits" to (b.items.isNotEmpty() && b.items.all { it.credits != null }),
            )
            is ModelUsageState.Error -> mapOf("state" to "error", "errorMessage" to b.message, "items" to emptyList<Any>())
            ModelUsageState.Loading -> mapOf("state" to "loading", "items" to emptyList<Any>())
            ModelUsageState.NotConfigured -> mapOf("state" to "notConfigured", "items" to emptyList<Any>())
        }
    }

    private fun reviewSection(current: QuotaSnapshot?): Map<String, Any?> {
        // A cycle is labelled by the month it covered (the month before its reset).
        fun label(resetDate: String): String {
            val r = Time.parse(resetDate) ?: return resetDate
            val d = Time.utc(r)
            return Format.monthLabel(Time.utcDate(d.year, d.monthValue - 1, maxOf(1, d.dayOfMonth)))
        }
        fun cycleMap(c: io.github.m313.contingentwatcher.core.CycleSummary, isCurrent: Boolean) = linkedMapOf(
            "label" to label(c.resetDate),
            "used" to c.used,
            "entitlement" to c.entitlement,
            "avgPerActiveDay" to c.avgPerActiveDay,
            "activeDays" to c.activeDays,
            "peakDay" to c.peakDay?.let { mapOf("date" to it.date, "used" to it.used) },
            "demand" to c.demand,
            "current" to isCurrent,
        )
        val cycles = ArrayList<Map<String, Any?>>()
        if (current != null) {
            cycles.add(cycleMap(Cycles.summarize(current, store.history, settings.forecastOptions, System.currentTimeMillis()), true))
        }
        for (c in store.cycles) {
            if (current == null || c.resetDate.take(10) != current.resetDate.take(10)) cycles.add(cycleMap(c, false))
        }
        return mapOf("cycles" to cycles.take(6))
    }

    private fun activitySection(): Map<String, Any?> {
        val (items, isActive) = ActivityTracker.instance.snapshot()
        return mapOf(
            "enabled" to settings.state.activityEnabled,
            "items" to items.take(50).map { i ->
                mapOf(
                    "id" to i.id,
                    "title" to (if (i.day != null) "${i.branch ?: i.repo} · ${Format.fmtDate(i.start)}" else (i.branch ?: i.repo)),
                    "detail" to i.detail,
                    "repo" to i.repo,
                    "start" to i.start,
                    "end" to i.end,
                    "activeMinutes" to Math.round(i.activeMs / 60_000.0),
                    "used" to Math.round(i.used * 10) / 10.0,
                    "active" to isActive(i),
                )
            },
        )
    }

    private fun githubUrl(url: String): String {
        // Point enterprise users to their own host.
        val host = Auth.enterpriseHost()
        return if (provider == "github-enterprise" && host != null) url.replace("https://github.com", "https://$host") else url
    }

    // ------------------------------------------------------------------ commands and settings

    /** Handles a dashboard command (EDT). */
    fun onCommand(command: String, project: Project?) {
        when (command) {
            "refresh" -> refresh()
            "signIn" -> signIn(project)
            "showLog" -> Ui.showLog(project)
            "openSettings" -> Ui.openSettings(project)
            "openBilling" -> BrowserUtil.browse(githubUrl(BILLING_URL))
            "manageCopilot" -> BrowserUtil.browse(githubUrl(COPILOT_SETTINGS_URL))
            "openChat" -> if (!CopilotOptions.openChat(project)) Notifier.info(L10n.t("GitHub Copilot Chat was not found in this IDE."))
            "snooze" -> {
                store.snooze(24)
                render()
            }
            "unsnooze" -> {
                store.unsnooze()
                render()
            }
            "configureBillingToken" -> BillingService.instance.configure(project)
            "clearBillingToken" -> BillingService.instance.clear()
            "clearActivity" -> {
                val ok = Messages.showYesNoDialog(
                    project,
                    L10n.t("Clear the recorded activity history?"),
                    L10n.t("Copilot Contingent"),
                    L10n.t("Clear"),
                    Messages.getCancelButton(),
                    Messages.getWarningIcon(),
                )
                if (ok == Messages.YES) ActivityTracker.instance.clear()
            }
            "snoozeCompletions" -> CopilotOptions.snooze(project)
            "btw" -> Ui.openBtw(project, focusInput = true)
            "openWalkthrough" -> Ui.showGettingStarted(project)
        }
    }

    /** Applies a setting changed in the dashboard (EDT). */
    fun onSetting(key: String, value: Any?, project: Project?) {
        when (key) {
            "copilotCompletions" -> {
                CopilotOptions.setCompletions(value == true, project)
                render()
                return
            }
            "copilotNextEdit" -> {
                CopilotOptions.setNextEdit(value == true, project)
                render()
                return
            }
            "copilotLanguage" -> {
                CopilotOptions.setLanguage(value == true, project)
                render()
                return
            }
            "copilotEagerness", "alignment" -> return
        }
        settings.update { s ->
            when (key) {
                "display" -> (value as? String)?.takeIf { it in listOf("remaining", "used", "percent", "dailyBudget") }?.let { s.display = it }
                "countWeekends" -> s.countWeekends = value == true
                "reserve" -> s.reserve = maxOf(0, (value as? Number)?.toInt() ?: value.toString().toDoubleOrNull()?.toInt() ?: 0)
                "thresholds" -> (value as? List<*>)?.let { list ->
                    s.thresholds = list.mapNotNull { (it as? Number)?.toDouble() ?: it?.toString()?.toDoubleOrNull() }
                        .filter { it > 0 && it <= 100 }.map { Math.round(it).toInt() }.toSortedSet().toMutableList()
                }
                "warningsEnabled" -> s.warningsEnabled = value == true
                "paceWarning" -> s.paceWarning = value == true
                "tipsEnabled" -> s.tipsEnabled = value == true
                "authProvider" -> s.authProvider = (value as? String)?.takeIf { it in listOf("auto", "github", "github-enterprise") } ?: "auto"
                "accent" -> s.accentColor = (value as? String)?.takeIf { Appearance.isValidColorSetting(it) } ?: "auto"
                "statusBarIcon" -> s.statusBarIcon = io.github.m313.contingentwatcher.core.IconStyle.of(value as? String).id
                "statusBarColor" -> s.statusBarColor = (value as? String)?.takeIf { Appearance.isValidColorSetting(it) } ?: "auto"
                "surplusHint" -> s.surplusHint = value == true
                "activityTracking" -> s.activityEnabled = value == true
                "refreshIntervalMinutes" -> s.refreshIntervalMinutes = ((value as? Number)?.toInt() ?: value.toString().toDoubleOrNull()?.toInt() ?: 10).coerceIn(2, 120)
                "apiUrl" -> s.apiUrl = (value as? String)?.trim() ?: ""
                "unit" -> s.unit = (value as? String)?.takeIf { it == "credits" || it == "requests" } ?: "auto"
                "btwOpenIn" -> s.btwOpenIn = if (value == "window") "window" else "side"
                "btwModel" -> s.btwModel = if (value == "included") "included" else "chat"
                "btwIncludeChat" -> s.btwIncludeChat = value == true
                "preferredModels" -> (value as? List<*>)?.let { list ->
                    s.preferredModels = list.mapNotNull { it?.toString()?.trim()?.ifEmpty { null } }.distinct().toMutableList()
                }
            }
        }
        if (key == "btwOpenIn") Ui.applyBtwOpenIn()
    }

    override fun dispose() {
        timer?.cancel(false)
    }

    companion object {
        private const val BILLING_URL = "https://github.com/settings/billing"
        private const val COPILOT_SETTINGS_URL = "https://github.com/settings/copilot"

        val instance: ContingentService
            get() = ApplicationManager.getApplication().getService(ContingentService::class.java)
    }
}
