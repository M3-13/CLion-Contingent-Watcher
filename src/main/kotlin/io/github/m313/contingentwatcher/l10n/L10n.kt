package io.github.m313.contingentwatcher.l10n

import com.google.gson.JsonParser
import com.intellij.DynamicBundle
import io.github.m313.contingentwatcher.services.ContingentSettings
import java.util.Locale

/**
 * Translations keyed by the English source string, like `vscode.l10n.t`:
 * the German bundle of the VS Code extension plus CLion-specific strings.
 * The language follows the IDE (language pack) unless set in the settings.
 */
object L10n {
    private val de: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        for (path in listOf("/l10n/bundle.l10n.de.json", "/l10n/clion.l10n.de.json")) {
            val stream = L10n::class.java.getResourceAsStream(path) ?: continue
            stream.reader(Charsets.UTF_8).use { r ->
                val obj = JsonParser.parseReader(r).asJsonObject
                for ((k, v) in obj.entrySet()) if (v.isJsonPrimitive) out[k] = v.asString
            }
        }
        out
    }

    /** "de" or "en". */
    val language: String
        get() {
            val setting = try {
                ContingentSettings.instance.state.language
            } catch (_: Throwable) {
                "auto"
            }
            if (setting == "de" || setting == "en") return setting
            val locale = try {
                DynamicBundle.getLocale()
            } catch (_: Throwable) {
                Locale.getDefault()
            }
            return if (locale.language == "de") "de" else "en"
        }

    /** BCP-47 tag used for number and date formatting. */
    val locale: Locale
        get() = if (language == "de") Locale.GERMAN else Locale.ENGLISH

    fun t(english: String, vararg args: Any?): String {
        val template = if (language == "de") de[english] ?: english else english
        return format(template, *args)
    }

    fun format(template: String, vararg args: Any?): String {
        if (args.isEmpty()) return template
        return Regex("""\{(\d+)\}""").replace(template) { m ->
            val i = m.groupValues[1].toInt()
            if (i < args.size) args[i].toString() else m.value
        }
    }
}
