package dev.glut.jetbrains

/**
 * The protocol's tools: what Glut may ask of the editor (docs/editor-protocol.md in Glut). `openFiles` and
 * `diagnostics` are listed for its model; the rest Glut calls itself. Nothing here knows the platform: an [Editor]
 * answers, so the same tools are tested without an IDE.
 */
data class Position(val line: Int, val character: Int) {
    fun json() = mapOf("line" to line, "character" to character)
}

data class Selection(val start: Position, val end: Position, val text: String)

data class ActiveFile(val path: String, val cursor: Position, val selection: Selection?)

data class OpenFile(val path: String, val active: Boolean, val dirty: Boolean)

data class Buffer(val path: String, val text: String, val dirty: Boolean)

data class Diagnostic(val line: Int, val character: Int, val severity: String, val message: String, val source: String?)

data class DiffRequest(
    val id: String,
    val path: String,
    val oldText: String?,
    val newText: String,
    val title: String,
    val editable: Boolean,
)

/** How the owner answered a diff; `text` is theirs where they changed the right side and accepted. */
data class DiffAnswer(val outcome: String, val text: String? = null) {
    companion object {
        val CLOSED = DiffAnswer("closed")
        val REJECTED = DiffAnswer("rejected")
    }
}

/** What the editor does for the protocol. */
interface Editor {
    fun activeFile(): ActiveFile?
    fun openFiles(): List<OpenFile>
    /** The loaded document of exactly `path`, never one opened for it. */
    fun buffer(path: String): Buffer?
    /** The diagnostics of a loaded document of exactly `path`, with its path. */
    fun diagnostics(path: String): Pair<String, List<Diagnostic>>?
    /** Shows the diff; `answer` is called once the owner decided or the diff went. */
    fun openDiff(request: DiffRequest, answer: (DiffAnswer) -> Unit)
    /** Closes the diff `id`. */
    fun closeDiff(id: String)
    /** Glut wrote these files. */
    fun changed(paths: List<String>)
    /** A word of Glut's to the owner. */
    fun notify(message: String)
}

/** The largest note `notify` shows, in characters. */
private const val MOST_NOTIFIED = 300

/** Glut's words as plain words: control characters as spaces, at most [MOST_NOTIFIED] characters, never cut inside one. */
fun plain(message: String): String {
    val spaced = message.map { if (it.code < 0x20 || it.code in 0x7f..0x9f) ' ' else it }.joinToString("")
    val points = spaced.codePoints().limit(MOST_NOTIFIED.toLong()).toArray()
    return String(points, 0, points.size)
}

/** The diffs waiting for the owner, by id, with the connection that asked: its end closes them. */
class Diffs(private val editor: Editor) {
    /** Each diff by its id: an entry of its own, so a late answer of one shown before under the same id is dropped. */
    private class Entry(val connection: Int)

    private val open = HashMap<String, Entry>()

    fun show(request: DiffRequest, respond: (Any?, String?) -> Unit, connection: Int) {
        if (synchronized(open) { request.id in open }) close(request.id)
        val entry = Entry(connection)
        synchronized(open) { open[request.id] = entry }
        editor.openDiff(request) { answer ->
            val mine = synchronized(open) { if (open[request.id] === entry) open.remove(request.id) else null }
            if (mine != null) {
                val out = linkedMapOf<String, Any?>("outcome" to answer.outcome)
                if (answer.outcome == "accepted" && answer.text != null) out["text"] = answer.text
                respond(text(out), null)
            }
        }
    }

    /** Closes the diff `id`: its question answers `closed`. Whether there was one. */
    fun close(id: String): Boolean {
        val had = synchronized(open) { id in open }
        editor.closeDiff(id)
        return had
    }

    /** The connection `connection` ended: nobody waits for its diffs' answers. */
    fun ended(connection: Int) {
        val gone = synchronized(open) { open.filterValues { it.connection == connection }.keys.toList() }
        gone.forEach(::close)
    }

    fun closeAll() {
        synchronized(open) { open.keys.toList() }.forEach(::close)
    }
}

private fun text(value: Any?): Map<String, Any?> = mapOf("content" to listOf(mapOf("type" to "text", "text" to Json.write(value))))

private fun refusal(why: String): Map<String, Any?> =
    mapOf("isError" to true, "content" to listOf(mapOf("type" to "text", "text" to why)))

/** What `tools/list` shows Glut's model. */
private val LISTED = listOf(
    mapOf(
        "name" to "openFiles",
        "description" to "The files open in the owner's editor, the active first.",
        "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
    ),
    mapOf(
        "name" to "diagnostics",
        "description" to "A file's diagnostics, as the editor's language servers see its buffer.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf("path" to mapOf("type" to "string")),
            "required" to listOf("path"),
        ),
    ),
)

/** Answers one JSON-RPC message of Glut's. */
fun handle(
    editor: Editor,
    diffs: Diffs,
    version: String,
    ide: String,
    message: Map<String, Any?>,
    respond: (Any?, String?) -> Unit,
    connection: Int,
) {
    @Suppress("UNCHECKED_CAST")
    val params = message["params"] as? Map<String, Any?> ?: emptyMap()
    when (message["method"]) {
        "initialize" -> return respond(
            mapOf(
                "protocolVersion" to (params["protocolVersion"] as? String ?: "2025-06-18"),
                "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
                "serverInfo" to mapOf("name" to ide, "version" to version),
            ),
            null,
        )
        "tools/list" -> return respond(mapOf("tools" to LISTED), null)
        "ping" -> return respond(emptyMap<String, Any>(), null)
        "tools/call" -> {}
        else -> return respond(null, "no method ${message["method"]}")
    }
    @Suppress("UNCHECKED_CAST")
    val args = params["arguments"] as? Map<String, Any?> ?: emptyMap()
    when (params["name"]) {
        "openFiles" -> respond(
            text(mapOf("files" to editor.openFiles().map { mapOf("path" to it.path, "active" to it.active, "dirty" to it.dirty) })),
            null,
        )
        "diagnostics" -> {
            val found = (args["path"] as? String)?.let(editor::diagnostics)
            respond(
                text(
                    if (found == null) {
                        mapOf("diagnostics" to emptyList<Any>())
                    } else {
                        mapOf(
                            "path" to found.first,
                            "diagnostics" to found.second.map {
                                mapOf(
                                    "line" to it.line, "character" to it.character, "severity" to it.severity,
                                    "message" to it.message, "source" to it.source,
                                )
                            },
                        )
                    },
                ),
                null,
            )
        }
        "getContext" -> {
            val active = editor.activeFile()
            respond(
                text(
                    mapOf(
                        "activeFile" to active?.let {
                            mapOf(
                                "path" to it.path,
                                "cursor" to it.cursor.json(),
                                "selection" to it.selection?.let { selected ->
                                    mapOf("start" to selected.start.json(), "end" to selected.end.json(), "text" to selected.text)
                                },
                            )
                        },
                    ),
                ),
                null,
            )
        }
        "readBuffer" -> {
            val buffer = (args["path"] as? String)?.let(editor::buffer)
            respond(
                text(
                    if (buffer == null) {
                        mapOf("loaded" to false)
                    } else {
                        mapOf("loaded" to true, "path" to buffer.path, "text" to buffer.text, "dirty" to buffer.dirty)
                    },
                ),
                null,
            )
        }
        "openDiff" -> {
            val id = when (val raw = args["id"]) {
                is String -> raw
                is Number -> raw.toLong().toString()
                else -> ""
            }
            val path = args["path"] as? String
            val newText = args["newText"] as? String
            if (id.isEmpty() || path == null || newText == null) {
                return respond(null, "openDiff needs id, path and newText")
            }
            diffs.show(
                DiffRequest(
                    id = id,
                    path = path,
                    oldText = args["oldText"] as? String,
                    newText = newText,
                    title = args["title"] as? String ?: "",
                    editable = args["editable"] != false,
                ),
                respond,
                connection,
            )
        }
        "closeDiff" -> respond(text(mapOf("closed" to diffs.close(args["id"]?.toString() ?: ""))), null)
        "changed" -> {
            editor.changed((args["paths"] as? List<*>)?.filterIsInstance<String>() ?: emptyList())
            respond(text(emptyMap<String, Any>()), null)
        }
        "notify" -> {
            (args["message"] as? String)?.let { editor.notify(plain(it)) }
            respond(text(emptyMap<String, Any>()), null)
        }
        else -> respond(refusal("no tool ${params["name"]}"), null)
    }
}
