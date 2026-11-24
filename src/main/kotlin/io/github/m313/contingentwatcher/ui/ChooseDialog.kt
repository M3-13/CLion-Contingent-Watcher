package io.github.m313.contingentwatcher.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/** A simple list chooser (e.g. the model for btw). */
class ChooseDialog(project: Project?, title: String, private val message: String, items: List<String>, initial: Int) : DialogWrapper(project) {
    private val list = JBList(items).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectedIndex = initial.coerceIn(0, maxOf(0, items.size - 1))
        ensureIndexIsVisible(selectedIndex)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && selectedIndex >= 0) doOKAction()
            }
        })
    }

    val selectedIndex: Int get() = list.selectedIndex

    init {
        this.title = title
        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    override fun createCenterPanel(): JComponent = panel {
        row { text(message, maxLineLength = 60) }
        row {
            cell(JBScrollPane(list).apply { preferredSize = JBUI.size(380, 260) }).align(AlignX.FILL).align(AlignY.FILL)
        }.resizableRow()
    }
}
