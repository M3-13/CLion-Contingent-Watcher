package io.github.m313.contingentwatcher.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import io.github.m313.contingentwatcher.btw.ModelMatch
import io.github.m313.contingentwatcher.core.Appearance
import io.github.m313.contingentwatcher.core.ForecastOptions
import io.github.m313.contingentwatcher.core.IconStyle

/** Notified after any setting changed. */
fun interface SettingsListener {
    fun settingsChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<SettingsListener> = Topic.create("Copilot Contingent settings", SettingsListener::class.java)
    }
}

/**
 * All settings of the plugin (the counterpart of `contingentWatcher.*` in VS Code).
 * Stored per IDE installation, not per project.
 */
@Service(Service.Level.APP)
@State(name = "CopilotContingentWatcherSettings", storages = [Storage("copilotContingentWatcher.xml")])
class ContingentSettings : PersistentStateComponent<ContingentSettings.SettingsState> {
    class SettingsState {
        /** auto | github | github-enterprise */
        var authProvider: String = "auto"
        /** Optional API base URL override. */
        var apiUrl: String = ""
        /** GitHub Enterprise address (GHE.com tenant or GHE Server), e.g. https://company.ghe.com. */
        var enterpriseUri: String = ""
        /** Where the GitHub token comes from: auto | copilot | ide | manual. */
        var tokenSource: String = "auto"
        var refreshIntervalMinutes: Int = 10
        /** remaining | used | percent | dailyBudget */
        var display: String = "remaining"
        var warningsEnabled: Boolean = true
        var thresholds: MutableList<Int> = mutableListOf(50, 75, 90, 100)
        var paceWarning: Boolean = true
        var countWeekends: Boolean = false
        var reserve: Int = 0
        var tipsEnabled: Boolean = true
        var accentColor: String = "auto"
        /** gauge | battery | ring | bars | none */
        var statusBarIcon: String = "gauge"
        var statusBarColor: String = "auto"
        var surplusHint: Boolean = true
        var activityEnabled: Boolean = true
        var preferredModels: MutableList<String> = ModelMatch.DEFAULT_PREFERRED_MODELS.toMutableList()
        var btwIncludeChat: Boolean = true
        /** chat | included */
        var btwModel: String = "chat"
        /** side | window */
        var btwOpenIn: String = "side"
        /** Optional folder with Copilot chat session files (JSON/JSONL) used as btw context. */
        var btwChatSessionDir: String = ""
        /** auto | credits | requests */
        var unit: String = "auto"
        /** auto | en | de */
        var language: String = "auto"
        /** Getting-started dialog was shown once. */
        var welcomeShown: Boolean = false
    }

    private var state = SettingsState()

    override fun getState(): SettingsState = state

    override fun loadState(s: SettingsState) {
        XmlSerializerUtil.copyBean(s, state)
    }

    val refreshIntervalMinutes: Int get() = state.refreshIntervalMinutes.coerceIn(2, 120)
    val thresholds: List<Double> get() = state.thresholds.filter { it in 1..100 }.map { it.toDouble() }
    val reserve: Double get() = maxOf(0, state.reserve).toDouble()
    val iconStyle: IconStyle get() = IconStyle.of(state.statusBarIcon)
    val forecastOptions: ForecastOptions get() = ForecastOptions(state.countWeekends, reserve)
    val preferredModels: List<String> get() = ModelMatch.normalizePreferred(state.preferredModels)
    val display: String get() = state.display.takeIf { it in listOf("remaining", "used", "percent", "dailyBudget") } ?: "remaining"
    val accent: String get() = state.accentColor.takeIf { Appearance.isValidColorSetting(it) } ?: "auto"
    val statusBarColor: String get() = state.statusBarColor.takeIf { Appearance.isValidColorSetting(it) } ?: "auto"

    /** Applies a change and notifies listeners. */
    fun update(change: (SettingsState) -> Unit) {
        change(state)
        ApplicationManager.getApplication().messageBus.syncPublisher(SettingsListener.TOPIC).settingsChanged()
    }

    fun fireChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(SettingsListener.TOPIC).settingsChanged()
    }

    companion object {
        val instance: ContingentSettings
            get() = ApplicationManager.getApplication().getService(ContingentSettings::class.java)
    }
}
