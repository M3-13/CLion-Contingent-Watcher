package io.github.m313.contingentwatcher.ui

import com.intellij.ui.jcef.JBCefApp

/** Whether the embedded browser can be used. */
object Jcef {
    /**
     * False when the IDE has no JCEF or its "Web Browser (JCEF)" plugin is
     * disabled (since 2026.x the classes are then missing entirely).
     */
    fun isSupported(): Boolean = try {
        JBCefApp.isSupported()
    } catch (_: Throwable) {
        false
    }
}
