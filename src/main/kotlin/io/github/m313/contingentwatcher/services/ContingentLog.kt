package io.github.m313.contingentwatcher.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The plugin's own log (counterpart of the VS Code output channel): which
 * provider, URL and HTTP status were used. Tokens are never logged.
 * Kept in memory (last lines) and mirrored to idea.log.
 */
@Service(Service.Level.APP)
class ContingentLog {
    private val lines = ArrayDeque<String>()
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private fun add(level: String, msg: String) {
        val line = "${LocalDateTime.now().format(fmt)} [$level] $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun debug(msg: String) {
        add("debug", msg)
        LOG.debug(msg)
    }

    fun info(msg: String) {
        add("info", msg)
        LOG.info(msg)
    }

    fun warn(msg: String) {
        add("warning", msg)
        LOG.warn(msg)
    }

    fun error(msg: String) {
        add("error", msg)
        // Not LOG.error: that would show the IDE's "internal error" balloon.
        LOG.warn(msg)
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    companion object {
        private const val MAX_LINES = 1000
        private val LOG = Logger.getInstance("#io.github.m313.contingentwatcher")

        val instance: ContingentLog
            get() = ApplicationManager.getApplication().getService(ContingentLog::class.java)
    }
}
