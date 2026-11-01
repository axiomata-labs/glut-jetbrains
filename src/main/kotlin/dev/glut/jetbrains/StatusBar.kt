package dev.glut.jetbrains

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Icon

private const val WIDGET_ID = "dev.glut.status"

/** Glut's flame in the status bar (the toolbar's button takes the same file through plugin.xml). */
internal val GLUT_ICON: Icon = IconLoader.getIcon("/icons/glut.svg", GlutStatus::class.java)

/** *Glut* in the status bar: one click starts Glut, or shows the one there is. */
class GlutStatusFactory : StatusBarWidgetFactory {
    override fun getId() = WIDGET_ID

    override fun getDisplayName() = "Glut"

    override fun createWidget(project: Project): StatusBarWidget = GlutStatus(project)
}

class GlutStatus(private val project: Project) : StatusBarWidget, StatusBarWidget.IconPresentation {
    override fun ID() = WIDGET_ID

    override fun getPresentation() = this

    override fun install(statusBar: StatusBar) = Unit

    override fun getIcon(): Icon = GLUT_ICON

    override fun getTooltipText() = "Open Glut"

    override fun getClickConsumer() = Consumer<MouseEvent> { project.service<Glut>().open() }
}
