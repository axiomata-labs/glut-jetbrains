package dev.glut.jetbrains

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Computable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xml.util.XmlStringUtil
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The largest selection handed over, in bytes: the protocol's 16 KB. */
private const val MOST_SELECTED = 16 * 1024

/** `text` cut to at most `bytes` bytes of UTF-8, never inside a character. */
fun cut(text: String, bytes: Int): String {
    if (text.toByteArray(Charsets.UTF_8).size <= bytes) return text
    val out = StringBuilder()
    var size = 0
    var at = 0
    while (at < text.length) {
        val point = text.codePointAt(at)
        val width = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
        if (size + width > bytes) break
        out.appendCodePoint(point)
        size += width
        at += Character.charCount(point)
    }
    return out.toString()
}

/** `text` (as a document holds it, `\n` alone) as the file Glut proposed writes it: its line endings and its BOM. */
fun asProposed(text: String, proposed: String): String {
    var out = text.replace("\r\n", "\n")
    if (proposed.contains("\r\n")) out = out.replace("\n", "\r\n")
    if (proposed.startsWith("﻿") && !out.startsWith("﻿")) out = "﻿$out"
    return out
}

/** Text as the IDE shows it in a notification, as plain words: no markup, no control, bidi or zero-width character. */
fun shownPlainly(text: String): String =
    XmlStringUtil.escapeString(text.replace(Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]"), " "))

/** A path as the disk names it: links resolved where the file is there. */
private fun real(path: Path): Path = runCatching { path.toRealPath() }.getOrDefault(path.toAbsolutePath().normalize())

/**
 * The protocol's [Editor] in a JetBrains IDE: the file the owner works on, the documents the IDE holds (unsaved changes
 * too, never one loaded for Glut), the inspections' findings, and Glut's questions about edits as the IDE's own diffs —
 * the file on the left, Glut's change on the right, which the owner may change before accepting.
 */
class IdeEditor(private val project: Project) : Editor, com.intellij.openapi.Disposable {
    /** One diff shown, until the owner answers it or it goes. */
    private class Shown(
        val file: ChainDiffVirtualFile,
        val right: Document,
        /** The right side as the document took it (its line endings `\n`): the owner changed it if it differs. */
        val loaded: String,
        val newText: String,
        val editable: Boolean,
        val answer: (DiffAnswer) -> Unit,
    )

    private val diffs = ConcurrentHashMap<String, Shown>()
    /** The last editor of a file on disk the owner had in front: theirs, while they type in Glut's terminal. */
    @Volatile
    private var lastFile: VirtualFile? = null

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                    val file = event.newFile
                    if (file != null && file.isInLocalFileSystem) lastFile = file
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    // A diff's tab the owner closed answers nothing — unless it lives on in another window or split.
                    val id = diffs.entries.firstOrNull { it.value.file == file }?.key ?: return
                    ApplicationManager.getApplication().invokeLater({
                        if (!FileEditorManager.getInstance(project).isFileOpen(file)) settle(id, DiffAnswer.CLOSED)
                    }, ModalityState.any())
                }
            },
        )
    }

    override fun dispose() {
        diffs.keys.toList().forEach(::closeDiff)
    }

    /** `block` on the IDE's event thread, waited for: the editors' state is read there. */
    private fun <T> onEdt(block: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return block()
        var out: Result<T>? = null
        application.invokeAndWait({ out = runCatching(block) }, ModalityState.any())
        return out!!.getOrThrow()
    }

    private fun currentEditor(): com.intellij.openapi.editor.Editor? {
        val manager = FileEditorManager.getInstance(project)
        val selected = manager.selectedTextEditor
        val file = selected?.let { FileDocumentManager.getInstance().getFile(it.document) }
        if (selected != null && file != null && file.isInLocalFileSystem) return selected
        val last = lastFile ?: return null
        return manager.getEditors(last).filterIsInstance<TextEditor>().firstOrNull()?.editor
    }

    override fun activeFile(): ActiveFile? = onEdt {
        val editor = currentEditor() ?: return@onEdt null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@onEdt null
        val caret = editor.caretModel.logicalPosition
        val selection = editor.selectionModel
        val selected = if (selection.hasSelection()) {
            val start = editor.offsetToLogicalPosition(selection.selectionStart)
            val end = editor.offsetToLogicalPosition(selection.selectionEnd)
            Selection(
                Position(start.line + 1, start.column + 1),
                Position(end.line + 1, end.column + 1),
                cut(selection.selectedText ?: "", MOST_SELECTED),
            )
        } else {
            null
        }
        ActiveFile(file.path, Position(caret.line + 1, caret.column + 1), selected)
    }

    override fun openFiles(): List<OpenFile> = onEdt {
        val active = currentEditor()?.let { FileDocumentManager.getInstance().getFile(it.document)?.path }
        FileEditorManager.getInstance(project).openFiles
            .filter { it.isInLocalFileSystem }
            .map { OpenFile(it.path, it.path == active, FileDocumentManager.getInstance().isFileModified(it)) }
            .sortedByDescending { it.active }
    }

    /** The open file of exactly `path` — matched by its real path, never by a part of its name — and its document. */
    private fun opened(path: String): Pair<VirtualFile, Document>? {
        val asked = runCatching { Path.of(path) }.getOrNull() ?: return null
        if (!asked.isAbsolute) return null
        val wanted = real(asked)
        val file = FileEditorManager.getInstance(project).openFiles.firstOrNull {
            it.isInLocalFileSystem && real(it.toNioPath()) == wanted
        } ?: return null
        // A document the IDE holds already: none is loaded for Glut.
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
        return file to document
    }

    override fun buffer(path: String): Buffer? = onEdt {
        val (file, document) = opened(path) ?: return@onEdt null
        // A document holds `\n` alone; the file's own line endings, as Glut would read it on disk.
        val separator = FileDocumentManager.getInstance().getLineSeparator(file, project)
        val text = if (separator == "\n") document.text else document.text.replace("\n", separator)
        Buffer(file.path, text, FileDocumentManager.getInstance().isDocumentUnsaved(document))
    }

    override fun diagnostics(path: String): Pair<String, List<Diagnostic>>? = onEdt {
        val (file, document) = opened(path) ?: return@onEdt null
        val found = ApplicationManager.getApplication().runReadAction(Computable {
            val markup = DocumentMarkupModel.forDocument(document, project, false) ?: return@Computable emptyList()
            markup.allHighlighters.mapNotNull { HighlightInfo.fromRangeHighlighter(it) }
                .filter { it.description != null && it.severity >= HighlightSeverity.WEAK_WARNING }
                .map { info ->
                    val offset = info.startOffset.coerceIn(0, document.textLength)
                    val line = document.getLineNumber(offset)
                    Diagnostic(
                        line = line + 1,
                        character = offset - document.getLineStartOffset(line) + 1,
                        severity = when {
                            info.severity >= HighlightSeverity.ERROR -> "error"
                            info.severity >= HighlightSeverity.WARNING -> "warning"
                            else -> "info"
                        },
                        message = info.description,
                        source = info.inspectionToolId,
                    )
                }
        })
        file.path to found
    }

    override fun openDiff(request: DiffRequest, answer: (DiffAnswer) -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            try {
                show(request, answer)
            } catch (_: Exception) {
                // A diff that could not be shown asks nothing.
                answer(DiffAnswer.CLOSED)
            }
        }, ModalityState.any())
    }

    private fun show(request: DiffRequest, answer: (DiffAnswer) -> Unit) {
        val name = Path.of(request.path).fileName?.toString() ?: "file"
        val type = FileTypeManager.getInstance().getFileTypeByFileName(name)
        val factory = DiffContentFactory.getInstance()
        val left = factory.create(project, StringUtil.convertLineSeparators(request.oldText ?: ""), type)
        val loaded = StringUtil.convertLineSeparators(request.newText)
        val document = EditorFactory.getInstance().createDocument(loaded)
        // A rename's file takes no change of the owner's: it replaces the old name or nothing.
        document.setReadOnly(!request.editable)
        val right = factory.create(project, document, type)
        left.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        val title = request.title.ifEmpty { name }
        val diff = SimpleDiffRequest("Glut: $title", left, right, "Now", if (request.editable) "Glut's change — yours to change" else "Glut's change")
        val id = request.id
        diff.putUserData(
            DiffUserDataKeys.CONTEXT_ACTIONS,
            listOf(
                object : AnAction("Accept Glut's Change", null, AllIcons.Actions.Checked) {
                    override fun actionPerformed(event: AnActionEvent) = answer(id, true)
                },
                object : AnAction("Refuse Glut's Change", null, AllIcons.Actions.Cancel) {
                    override fun actionPerformed(event: AnActionEvent) = answer(id, false)
                },
            ),
        )
        val file = ChainDiffVirtualFile(SimpleDiffRequestChain(diff), "Glut: $title")
        diffs[id] = Shown(file, document, loaded, request.newText, request.editable, answer)
        // The owner types in Glut's terminal: the diff shows without the focus, so no key meant for Glut lands in it.
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, false)
        notifyOwner("Glut asks about a change of $name.")
    }

    /** The owner answers the diff `id` with its toolbar's buttons. */
    fun answer(id: String, accepted: Boolean) {
        val shown = diffs[id] ?: return
        if (!accepted) return settle(id, DiffAnswer.REJECTED)
        val now = shown.right.text
        val text = if (shown.editable && now != shown.loaded) asProposed(now, shown.newText) else null
        settle(id, DiffAnswer("accepted", text))
    }

    private fun settle(id: String, answer: DiffAnswer) {
        val shown = diffs.remove(id) ?: return
        shown.answer(answer)
        ApplicationManager.getApplication().invokeLater({
            FileEditorManager.getInstance(project).closeFile(shown.file)
        }, ModalityState.any())
    }

    override fun closeDiff(id: String) = settle(id, DiffAnswer.CLOSED)

    override fun changed(paths: List<String>) {
        // The IDE reads the files again where no unsaved change stands.
        LocalFileSystem.getInstance().refreshNioFiles(paths.mapNotNull { runCatching { Path.of(it) }.getOrNull() })
    }

    override fun notify(message: String) = notifyOwner("Glut: $message")

    private fun notifyOwner(message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Glut")
            .createNotification(shownPlainly(message), NotificationType.INFORMATION)
            .notify(project)
    }
}
