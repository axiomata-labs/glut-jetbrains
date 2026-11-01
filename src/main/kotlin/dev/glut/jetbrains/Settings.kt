package dev.glut.jetbrains

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * Settings ▸ Tools ▸ Glut: the program Glut is started as. Kept for the IDE, never in a project: a project's files
 * never choose the program that starts here.
 */
object Settings {
    private const val COMMAND = "dev.glut.command"

    fun command(): String = PropertiesComponent.getInstance().getValue(COMMAND, "glut").ifBlank { "glut" }

    fun setCommand(command: String) = PropertiesComponent.getInstance().setValue(COMMAND, command.trim(), "glut")

    /** The places `glut` is installed to (`cargo install`, Homebrew, `scripts/install.sh`'s link), first found first. */
    private val PLACES = listOf(".cargo/bin", "/opt/homebrew/bin", "/usr/local/bin")

    /**
     * The program to start: the setting as given where it names a path or another program; plain `glut` resolved to
     * where it is installed — never left to the terminal's PATH, which a project's own environment (a virtualenv,
     * `node_modules/.bin`) may lead first.
     */
    fun program(): String {
        val command = command()
        if (command != "glut") return command
        val home = System.getProperty("user.home")
        return PLACES.map { if (it.startsWith("/")) java.nio.file.Path.of(it, "glut") else java.nio.file.Path.of(home, it, "glut") }
            .firstOrNull { java.nio.file.Files.isExecutable(it) }
            ?.toString()
            ?.let { "'" + it.replace("'", "'\\''") + "'" }
            ?: command
    }
}

class GlutConfigurable : Configurable {
    private var field: JBTextField? = null

    override fun getDisplayName() = "Glut"

    override fun createComponent(): JComponent {
        val input = JBTextField(Settings.command())
        field = input
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Program Glut starts as:", input)
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
    }

    override fun isModified() = field?.text?.trim() != Settings.command()

    override fun apply() {
        field?.text?.let(Settings::setCommand)
    }

    override fun reset() {
        field?.text = Settings.command()
    }

    override fun disposeUIResources() {
        field = null
    }
}
