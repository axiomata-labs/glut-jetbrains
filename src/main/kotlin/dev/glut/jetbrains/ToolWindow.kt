package dev.glut.jetbrains

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

/** The tool window's id, as plugin.xml names it. */
private const val TOOL_WINDOW_ID = "Glut"

/**
 * Glut's flame in the IDE's side bar: its window holds the buttons, and opening it brings Glut's terminal to the
 * front.
 */
class GlutToolWindow : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val glut = project.service<Glut>()
        val content = panel {
            row { text("Glut runs in the IDE's terminal; this flame brings it to the front.") }
            row { button("Open Glut") { glut.open() } }
            separator()
            row {
                text("What you select in a file goes to Glut with your next message by itself. To name it in your text:")
            }
            row {
                button("Send Selection to Glut") {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    if (editor?.selectionModel?.hasSelection() == true) sendSelection(project, editor)
                }
                button("Add File to Glut") {
                    val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                    file?.let(glut::reference)?.let { glut.typeIntoGlut("@$it ") }
                }
            }
        }.apply { border = JBUI.Borders.empty(4, 8) }
        // The form stands at the window's top, not stretched over its height.
        val top = JPanel(BorderLayout()).apply { add(content, BorderLayout.NORTH) }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(top, "", false))
    }
}

/** A click on the flame shows the window and brings Glut to the front, as the editors' buttons do. */
class GlutToolWindowShown(private val project: Project) : ToolWindowManagerListener {
    override fun toolWindowShown(toolWindow: ToolWindow) {
        if (toolWindow.id == TOOL_WINDOW_ID) {
            project.service<Glut>().open()
        }
    }
}
