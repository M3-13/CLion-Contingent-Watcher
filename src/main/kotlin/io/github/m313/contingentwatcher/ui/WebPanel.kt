package io.github.m313.contingentwatcher.ui

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import io.github.m313.contingentwatcher.l10n.L10n
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.util.Base64
import javax.swing.JComponent

/**
 * Hosts one of the shared webview UIs (web/dashboard.js, web/btw.js) in JCEF.
 * Provides the `acquireVsCodeApi()` bridge the scripts expect, inlines styles
 * and scripts, and maps the IDE theme to the CSS variables they use.
 */
class WebPanel(
    parent: Disposable,
    private val script: String,
    private val style: String,
    private val title: String,
    private val bodyAttrs: () -> String = { "" },
    private val onMessage: (JsonElement) -> Unit,
) : Disposable {
    private val browser = JBCefBrowser.createBuilder().setOffScreenRendering(false).build()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private var savedState: String = "null"

    @Volatile
    private var loaded = false

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, this)
        Disposer.register(this, browser)
        Disposer.register(this, query)
        query.addHandler { raw ->
            try {
                val msg = JsonParser.parseString(raw)
                val o = if (msg.isJsonObject) msg.asJsonObject else null
                if (o?.get("type")?.asString == "__setState") {
                    savedState = o.get("state")?.toString() ?: "null"
                } else {
                    ApplicationManager.getApplication().invokeLater { onMessage(msg) }
                }
            } catch (_: Exception) {
            }
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) loaded = true
            }
        }, browser.cefBrowser)
        val bus = ApplicationManager.getApplication().messageBus.connect(this)
        bus.subscribe(LafManagerListener.TOPIC, LafManagerListener { applyTheme() })
        bus.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { applyTheme() })
        load()
    }

    private fun resource(name: String): String =
        WebPanel::class.java.getResourceAsStream("/web/$name")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    private fun load() {
        loaded = false
        val post = query.inject("s")
        val shim = """
            (function () {
              var state = $savedState;
              window.acquireVsCodeApi = function () {
                return {
                  postMessage: function (m) { var s = JSON.stringify(m); $post },
                  getState: function () { return state; },
                  setState: function (v) { state = v; var s = JSON.stringify({ type: '__setState', state: v }); $post }
                };
              };
              window.__cwReceive = function (m) { window.dispatchEvent(new MessageEvent('message', { data: m })); };
              window.__cwTheme = function (css, cls) {
                document.getElementById('cw-theme').textContent = css;
                document.body.classList.remove('vscode-dark', 'vscode-light');
                document.body.classList.add(cls);
              };
            })();
        """.trimIndent()
        val html = """<!DOCTYPE html>
<html lang="${L10n.language}">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${escape(title)}</title>
<style id="cw-theme">${ThemeCss.rootCss()}</style>
<style>html,body{background:${String.format("#%06x", ThemeCss.background().rgb and 0xffffff)};}</style>
<style>${resource(style)}</style>
</head>
<body class="${ThemeCss.bodyClass()}" ${bodyAttrs()}>
<main id="app" aria-busy="true"></main>
<script>$shim</script>
<script>${resource(script).replace("</script", "<\\/script")}</script>
</body>
</html>"""
        browser.loadHTML(html)
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun applyTheme() {
        val css = gson.toJson(ThemeCss.rootCss() + "html,body{background:" + String.format("#%06x", ThemeCss.background().rgb and 0xffffff) + "}")
        exec("window.__cwTheme && window.__cwTheme($css, '${ThemeCss.bodyClass()}');")
    }

    /** Sends a message to the page (as `window.postMessage` would in VS Code). */
    fun post(msg: Any) {
        exec("window.__cwReceive && window.__cwReceive(${gson.toJson(msg)});")
    }

    private fun exec(js: String) {
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    /** Reloads the page (e.g. after the language changed). */
    fun reload() = load()

    fun focus() {
        browser.component.requestFocusInWindow()
    }

    override fun dispose() {}

    companion object {
        /** NaN/Infinity become JS identifiers, which is fine inside a script. */
        val gson: Gson = GsonBuilder().serializeSpecialFloatingPointValues().create()

        /** The icon font for the status bar previews as a data: URL. */
        fun iconFontDataUrl(): String {
            val bytes = WebPanel::class.java.getResourceAsStream("/web/contingent-icons.woff")?.use { it.readBytes() } ?: return ""
            return "data:font/woff;base64," + Base64.getEncoder().encodeToString(bytes)
        }
    }
}
