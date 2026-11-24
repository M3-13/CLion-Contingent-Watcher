package io.github.m313.contingentwatcher.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import io.github.m313.contingentwatcher.core.Appearance
import io.github.m313.contingentwatcher.core.Endpoints
import io.github.m313.contingentwatcher.l10n.L10n
import io.github.m313.contingentwatcher.services.BillingService
import io.github.m313.contingentwatcher.services.ContingentService
import io.github.m313.contingentwatcher.services.ContingentSettings

/** Settings | Tools | Copilot Contingent Watcher. */
class ContingentConfigurable : BoundConfigurable("Copilot Contingent Watcher") {
    private val settings get() = ContingentSettings.instance
    private val s get() = settings.state

    private fun <T> labelled(vararg pairs: Pair<T, String>): Pair<List<T>, Map<T, String>> = pairs.map { it.first } to pairs.toMap()

    override fun createPanel(): DialogPanel {
        val units = labelled("auto" to L10n.t("Auto"), "credits" to L10n.t("Credits"), "requests" to L10n.t("Requests"))
        val displays = labelled(
            "remaining" to L10n.t("Remaining"),
            "used" to L10n.t("Used"),
            "percent" to L10n.t("Percent"),
            "dailyBudget" to L10n.t("Per day"),
        )
        val icons = labelled(
            "gauge" to L10n.t("Gauge"),
            "battery" to L10n.t("Battery"),
            "ring" to L10n.t("Ring"),
            "bars" to L10n.t("Bars"),
            "none" to L10n.t("None"),
        )
        val languages = labelled("auto" to L10n.t("Like the IDE"), "en" to "English", "de" to "Deutsch")
        val btwModels = labelled("chat" to L10n.t("Chat model"), "included" to L10n.t("Free model"))
        val btwOpen = labelled("side" to L10n.t("Docked beside the editor"), "window" to L10n.t("Own window"))
        val providers = labelled("auto" to L10n.t("Auto"), "github" to "GitHub.com", "github-enterprise" to L10n.t("Enterprise"))
        val sources = labelled(
            "auto" to L10n.t("Auto (Copilot plugin, IDE account, token)"),
            "copilot" to L10n.t("GitHub Copilot plugin"),
            "ide" to L10n.t("IDE GitHub account"),
            "manual" to L10n.t("Token entered by you"),
        )
        val colorHint = L10n.t("auto, teal, violet, blue, green, orange, pink or #hex")

        return panel {
            group(L10n.t("Display")) {
                row(L10n.t("Show")) {
                    comboBox(units.first, textListCellRenderer { units.second[it] ?: "" }).bindItem({ s.unit }, { s.unit = it ?: "auto" })
                        .comment(L10n.t("Auto prefers AI credits when GitHub reports them"))
                }
                row(L10n.t("Status bar shows")) {
                    comboBox(displays.first, textListCellRenderer { displays.second[it] ?: "" }).bindItem({ settings.display }, { s.display = it ?: "remaining" })
                }
                row(L10n.t("Status bar icon")) {
                    comboBox(icons.first, textListCellRenderer { icons.second[it] ?: "" }).bindItem({ settings.iconStyle.id }, { s.statusBarIcon = it ?: "gauge" })
                }
                row(L10n.t("Status bar color")) {
                    textField().bindText({ settings.statusBarColor }, { s.statusBarColor = it.trim().ifEmpty { "auto" } }).comment(colorHint)
                        .validationOnInput { if (Appearance.isValidColorSetting(it.text.trim().ifEmpty { "auto" })) null else error(colorHint) }
                }
                row(L10n.t("Refresh every")) {
                    spinner(2..120).bindIntValue({ settings.refreshIntervalMinutes }, { s.refreshIntervalMinutes = it })
                    label(L10n.t("minutes"))
                }
                row(L10n.t("Language")) {
                    comboBox(languages.first, textListCellRenderer { languages.second[it] ?: "" }).bindItem({ s.language }, { s.language = it ?: "auto" })
                }
            }
            group(L10n.t("Appearance")) {
                row(L10n.t("Accent color")) {
                    textField().bindText({ settings.accent }, { s.accentColor = it.trim().ifEmpty { "auto" } }).comment(colorHint)
                        .validationOnInput { if (Appearance.isValidColorSetting(it.text.trim().ifEmpty { "auto" })) null else error(colorHint) }
                }
                row { checkBox(L10n.t("Show a saving tip in the status bar tooltip")).bindSelected(s::tipsEnabled) }
            }
            group(L10n.t("Budget")) {
                row { checkBox(L10n.t("Count weekends")).bindSelected(s::countWeekends).comment(L10n.t("Spread the budget over all days, not only workdays")) }
                row(L10n.t("Reserve")) {
                    intTextField(0..1_000_000).bindIntText(s::reserve).comment(L10n.t("Kept aside and excluded from the daily budget"))
                }
            }
            group(L10n.t("Warnings")) {
                row { checkBox(L10n.t("Threshold warnings")).bindSelected(s::warningsEnabled) }
                row(L10n.t("Warn at")) {
                    textField().bindText(
                        { s.thresholds.joinToString(", ") },
                        { v -> s.thresholds = v.split(',', ';', ' ').mapNotNull { it.trim().removeSuffix("%").toIntOrNull() }.filter { it in 1..100 }.toSortedSet().toMutableList() },
                    ).comment(L10n.t("Percentages, e.g. 50, 75, 90, 100. Each is reported once per billing cycle."))
                }
                row { checkBox(L10n.t("Pace warning")).bindSelected(s::paceWarning).comment(L10n.t("Warn when you are on course to run out before reset")) }
                row { checkBox(L10n.t("Leftover reminder")).bindSelected(s::surplusHint).comment(L10n.t("Near reset, point out allowance that would expire unused")) }
            }
            group(L10n.t("btw")) {
                row(L10n.t("Model")) {
                    comboBox(btwModels.first, textListCellRenderer { btwModels.second[it] ?: "" }).bindItem({ s.btwModel }, { s.btwModel = it ?: "chat" })
                    button(L10n.t("Choose model…")) {
                        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                io.github.m313.contingentwatcher.btw.BtwRunner.chooseModel(null)
                            } catch (e: Exception) {
                                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                    com.intellij.openapi.ui.Messages.showErrorDialog(e.message ?: e.toString(), L10n.t("btw"))
                                }
                            }
                        }
                    }
                }
                row(L10n.t("Free models")) {
                    textField().columns(COLUMNS_MEDIUM).bindText(
                        { settings.preferredModels.joinToString(", ") },
                        { v -> s.preferredModels = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList() },
                    ).comment(L10n.t("Included model families tried in this order when the model is 'Free model'."))
                }
                row(L10n.t("Open btw in")) {
                    comboBox(btwOpen.first, textListCellRenderer { btwOpen.second[it] ?: "" }).bindItem({ s.btwOpenIn }, { s.btwOpenIn = it ?: "side" })
                }
                row { checkBox(L10n.t("Include current chat in btw")).bindSelected(s::btwIncludeChat) }
                row(L10n.t("Chat sessions folder")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(L10n.t("Chat sessions folder")))
                        .align(AlignX.FILL)
                        .bindText(s::btwChatSessionDir)
                        .comment(L10n.t("Optional. Folder with Copilot chat session files (JSON/JSONL) to use as context; the Copilot plugin's folder is searched too."))
                }
            }
            group(L10n.t("Account")) {
                row(L10n.t("Sign-in provider")) {
                    comboBox(providers.first, textListCellRenderer { providers.second[it] ?: "" }).bindItem({ s.authProvider }, { s.authProvider = it ?: "auto" })
                    button(L10n.t("Sign in…")) { ContingentService.instance.signIn(null) }
                }
                row(L10n.t("Enterprise address")) {
                    textField().align(AlignX.FILL).bindText(s::enterpriseUri).comment("https://company.ghe.com · https://github.company.com")
                        .validationOnInput { if (it.text.isBlank() || Endpoints.normaliseHost(it.text) != null) null else error(L10n.t("Please enter an address like company.ghe.com")) }
                }
                row(L10n.t("Token from")) {
                    comboBox(sources.first, textListCellRenderer { sources.second[it] ?: "" }).bindItem({ s.tokenSource }, { s.tokenSource = it ?: "auto" })
                }
                row(L10n.t("API URL (advanced)")) {
                    textField().align(AlignX.FILL).bindText(s::apiUrl).comment(L10n.t("Automatic") + " · https://api.company.ghe.com")
                }
                row(L10n.t("Usage per model")) {
                    button(L10n.t("Add token")) { BillingService.instance.configure(null) }
                    button(L10n.t("Remove token")) { BillingService.instance.clear() }
                    comment(L10n.t("GitHub token with 'Plan' read permission"))
                }
                row { checkBox(L10n.t("Track activity")).bindSelected(s::activityEnabled).comment(L10n.t("Active time per git branch, stored locally")) }
                row {
                    button(L10n.t("Show log")) { Ui.showLog(null) }
                    button(L10n.t("Get started")) { Ui.showGettingStarted(null) }
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        settings.fireChanged()
        Ui.applyBtwOpenIn()
    }
}
