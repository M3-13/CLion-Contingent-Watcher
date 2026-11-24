package io.github.m313.contingentwatcher.services

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.util.io.HttpRequests
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection

/** Small HTTP helper on top of the IDE's HttpRequests (respects the IDE proxy settings). */
object Http {
    data class Response(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    const val USER_AGENT = "copilot-contingent-watcher"

    /** e.g. "JetBrains-CL/2025.2.6" */
    val editorVersion: String
        get() = try {
            val info = ApplicationInfo.getInstance()
            "JetBrains-${info.build.productCode}/${info.fullVersion}"
        } catch (_: Throwable) {
            "JetBrains/unknown"
        }

    /** e.g. "copilot-contingent-watcher/0.2.0", read from our plugin.xml. */
    val pluginVersion: String by lazy {
        val xml = try {
            Http::class.java.getResourceAsStream("/META-INF/plugin.xml")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        } catch (_: Throwable) {
            ""
        }
        "copilot-contingent-watcher/" + (Regex("<version>([^<]+)</version>").find(xml)?.groupValues?.get(1)?.trim() ?: "dev")
    }

    fun get(url: String, headers: Map<String, String>, timeoutMs: Int = 15_000): Response =
        HttpRequests.request(url)
            .tuner { c -> headers.forEach { (k, v) -> c.setRequestProperty(k, v) } }
            .userAgent(USER_AGENT)
            .connectTimeout(timeoutMs)
            .readTimeout(timeoutMs)
            .throwStatusCodeException(false)
            .connect { req ->
                val conn = req.connection as HttpURLConnection
                val status = conn.responseCode
                val body = if (status in 200..299) req.readString() else readError(conn)
                Response(status, body)
            }

    private fun readError(conn: HttpURLConnection): String = try {
        conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    } catch (_: Exception) {
        ""
    }

    /**
     * POSTs JSON and hands every line of a successful (streamed) response to `onLine`.
     * `register` receives a callback that aborts the request (used for cancellation).
     * Returns the status and, for errors, the body.
     */
    fun postStream(
        url: String,
        headers: Map<String, String>,
        json: String,
        register: (abort: () -> Unit) -> Unit,
        onLine: (String) -> Boolean,
    ): Response =
        HttpRequests.post(url, "application/json")
            .tuner { c -> headers.forEach { (k, v) -> c.setRequestProperty(k, v) } }
            .userAgent(USER_AGENT)
            .connectTimeout(15_000)
            .readTimeout(120_000)
            .throwStatusCodeException(false)
            .connect { req ->
                val conn = req.connection as HttpURLConnection
                register { runCatching { conn.disconnect() } }
                req.write(json)
                val status = conn.responseCode
                if (status !in 200..299) return@connect Response(status, readError(conn))
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (!onLine(line)) break
                    }
                }
                Response(status, "")
            }
}
