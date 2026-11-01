package dev.glut.jetbrains

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The plugin's version, as `build.gradle.kts` names it: Glut hears it in `initialize`. */
const val VERSION = "0.1.0"

/** How long Glut takes to draw its input before a reference pasted into it is kept, in milliseconds. */
private const val GLUT_READY_MS = 800L

/**
 * Glut beside a JetBrains IDE, one per project: it listens on loopback for Glut (docs/editor-protocol.md in Glut),
 * tells it the file, the cursor and the selection the owner works on, hands it the documents as the IDE holds them,
 * and shows its questions about edits as the IDE's own diffs. Glut itself runs in the IDE's terminal (Tools ▸ Glut ▸
 * Open Glut) or anywhere else in the project's folder.
 */
@Service(Service.Level.PROJECT)
class Glut(private val project: Project) : Disposable {
    private var server: Server? = null
    private var file: Path? = null
    private var editor: IdeEditor? = null
    private var diffs: Diffs? = null
    private var terminal: TerminalWidget? = null

    /** Starts listening, once — on a system with POSIX permissions alone, as Glut runs on (its lock file is 0600). */
    @Synchronized
    fun listen() {
        if (server != null) return
        if ("posix" !in java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()) return
        val ide = IdeEditor(project).also { Disposer.register(this, it) }
        val waiting = Diffs(ide)
        val secret = LockFile.token()
        val name = ApplicationNamesInfo.getInstance().fullProductName
        LockFile.sweep()
        val listening = Server(secret, { message, respond, connection ->
            handle(ide, waiting, VERSION, name, message, respond, connection)
        }, { connection -> waiting.ended(connection) })
        listening.start()
        try {
            file = LockFile.write(listening.port, secret, name, folders())
        } catch (error: Exception) {
            listening.stop()
            throw error
        }
        server = listening
        editor = ide
        diffs = waiting
    }

    /** The folders the project works in: its base and its content roots, where they really are. */
    private fun folders(): List<String> {
        val roots = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
            com.intellij.openapi.util.Computable {
                ProjectRootManager.getInstance(project).contentRoots.filter { it.isInLocalFileSystem }.map { it.path }
            },
        )
        return (listOfNotNull(project.basePath) + roots).distinct().map {
            runCatching { Path.of(it).toRealPath().toString() }.getOrDefault(it)
        }
    }

    override fun dispose() {
        diffs?.closeAll()
        LockFile.remove(file)
        server?.stop()
        server = null
    }

    /** Opens Glut in the IDE's terminal, or shows the one there is; whether it was just started. */
    fun open(): Boolean {
        listen()
        // A tab closed without its process ending tells nothing: it is gone once the terminal no longer lists it.
        val running = terminal?.takeIf { it in TerminalToolWindowManager.getInstance(project).terminalWidgets }
        if (running == null) terminal = null
        if (running != null) {
            ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.show()
            running.requestFocus()
            return false
        }
        val port = server?.port ?: return false
        // The owner's own shell, as their terminals start it, finds `glut` on their PATH; it gives way to Glut. The
        // terminal's newer tab builder is still experimental: this one is the public way, deprecated or not.
        @Suppress("DEPRECATION")
        val widget = TerminalToolWindowManager.getInstance(project)
            .createShellWidget(project.basePath ?: System.getProperty("user.home"), "Glut", true, true)
        widget.sendCommandToExecute("GLUT_IDE_PORT=$port exec ${Settings.program()}")
        terminal = widget
        widget.addTerminationCallback({ if (terminal === widget) terminal = null }, this)
        return true
    }

    /**
     * Puts `text` into Glut's input, unsent, and goes there — as a paste, never as keys: a key could answer a question of
     * Glut's that stands open.
     */
    fun typeIntoGlut(text: String) {
        val started = open()
        val paste = Runnable {
            terminal?.ttyConnector?.write("\u001b[200~$text\u001b[201~")
            terminal?.requestFocus()
        }
        if (started) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(paste)
            }, GLUT_READY_MS, TimeUnit.MILLISECONDS)
        } else {
            paste.run()
        }
    }

    /** Every character a reference cannot carry: space of any kind, controls, formats (bidi, zero-width). */
    private val unfit = Regex("[\\s\\p{Z}\\p{C}]")

    /** `file` as Glut names it — below the project's folder, else whole — or `null` for a name a reference cannot carry. */
    fun reference(file: VirtualFile): String? {
        if (!file.isInLocalFileSystem) return null
        val base = project.basePath?.let { Path.of(it) }
        val path = Path.of(file.path)
        val name = if (base != null && path.startsWith(base)) base.relativize(path).toString() else path.toString()
        // A space, a control, bidi or zero-width character would end the reference, act as a key or hide what it names.
        return if (unfit.containsMatchIn(name)) null else name
    }
}

/** Starts listening when a project opens, so a Glut started anywhere in its folder finds the IDE. */
class GlutStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<Glut>().listen()
    }
}

/** Tools ▸ Glut ▸ Open Glut. */
class OpenGlut : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<Glut>()?.open()
    }
}

/** The selected lines as `@path#L10-22` in Glut's input. */
class SendSelection : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val glut = project.service<Glut>()
        val name = glut.reference(file) ?: return
        val selection = editor.selectionModel
        val first = editor.document.getLineNumber(selection.selectionStart) + 1
        val endOffset = selection.selectionEnd
        val endLine = editor.document.getLineNumber(endOffset)
        // A selection that ends at a line's start does not take that line.
        val last = if (endLine + 1 > first && endOffset == editor.document.getLineStartOffset(endLine)) endLine else endLine + 1
        glut.typeIntoGlut(if (first == last) "@$name#L$first " else "@$name#L$first-$last ")
    }
}

/** The current file (or the one the project view's menu was opened on) as `@path` in Glut's input. */
class AddFile : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabled = event.project != null && file != null && !file.isDirectory
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val glut = project.service<Glut>()
        glut.reference(file)?.let { glut.typeIntoGlut("@$it ") }
    }
}
