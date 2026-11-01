package dev.glut.jetbrains

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/** The tool window's id, as plugin.xml names it. */
private const val TOOL_WINDOW_ID = "Glut"

/**
 * Glut's flame in the IDE's side bar: its window holds the buttons, and opening it brings Glut's terminal to the
 * front.
 */
class GlutToolWindow : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }
        panel.add(JBLabel("<html>Glut runs in the IDE's terminal; this flame brings it to the front.</html>"))
        panel.add(row(button("Open Glut") { project.service<Glut>().open() }))
        panel.add(
            JBLabel(
                "<html>What you select in a file goes to Glut with your next message by itself. To name it in your " +
                    "text:</html>",
            ),
        )
        panel.add(
            row(
                button("Send Selection to Glut") {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    if (editor?.selectionModel?.hasSelection() == true) sendSelection(project, editor)
                },
                button("Add File to Glut") {
                    val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                    val glut = project.service<Glut>()
                    file?.let(glut::reference)?.let { glut.typeIntoGlut("@$it ") }
                },
            ),
        )
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }

    private fun row(vararg buttons: JButton) = JPanel(FlowLayout(FlowLayout.LEFT)).apply { buttons.forEach(::add) }

    private fun button(text: String, onClick: () -> Unit) = JButton(text).apply { addActionListener { onClick() } }
}

/** A click on the flame shows the window and brings Glut to the front, as the editors' buttons do. */
class GlutToolWindowShown(private val project: Project) : ToolWindowManagerListener {
    override fun toolWindowShown(toolWindow: ToolWindow) {
        if (toolWindow.id == TOOL_WINDOW_ID) {
            project.service<Glut>().open()
        }
    }
}
