package dev.glut.jetbrains

import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The plugin as Glut meets it, without an IDE: the server, the lock file and the tools against a stand-in editor. */
class ProtocolTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    /** An editor that holds one unsaved document and keeps the diffs it was shown. */
    private class StandIn : Editor {
        val diffs = ConcurrentHashMap<String, Pair<DiffRequest, (DiffAnswer) -> Unit>>()
        val told = mutableListOf<String>()
        override fun activeFile() = ActiveFile(
            "/w/a.kt",
            Position(3, 1),
            Selection(Position(2, 1), Position(4, 9), "fun a()"),
        )
        override fun openFiles() = listOf(OpenFile("/w/a.kt", active = true, dirty = true))
        override fun buffer(path: String) = if (path == "/w/a.kt") Buffer(path, "unsaved\n", true) else null
        override fun diagnostics(path: String) =
            if (path == "/w/a.kt") path to listOf(Diagnostic(1, 2, "error", "no", "kotlin")) else null
        override fun openDiff(request: DiffRequest, answer: (DiffAnswer) -> Unit) {
            diffs[request.id] = request to answer
        }
        override fun closeDiff(id: String) {
            diffs.remove(id)?.second?.invoke(DiffAnswer.CLOSED)
        }
        override fun changed(paths: List<String>) {}
        override fun notify(message: String) {
            told.add(message)
        }
    }

    private lateinit var editor: StandIn
    private lateinit var server: Server
    private val http = HttpClient.newHttpClient()
    private var id = 0

    @BeforeTest
    fun serve() {
        editor = StandIn()
        val diffs = Diffs(editor)
        server = Server(secret, { message, respond, connection ->
            handle(editor, diffs, "0.1.0", "PyCharm", message, respond, connection)
        }, { connection -> diffs.ended(connection) }, idleMs = 300)
        server.start()
    }

    @AfterTest
    fun stop() = server.stop()

    private fun call(method: String, params: Map<String, Any?> = emptyMap()): Map<*, *> {
        id++
        val body = Json.write(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/mcp"))
            .header("Authorization", "Bearer $secret")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val answer = Json.parse(http.send(request, HttpResponse.BodyHandlers.ofString()).body()) as Map<*, *>
        return answer["result"] as Map<*, *>
    }

    private fun tool(name: String, args: Map<String, Any?> = emptyMap()): Map<*, *> {
        val result = call("tools/call", mapOf("name" to name, "arguments" to args))
        val text = ((result["content"] as List<*>)[0] as Map<*, *>)["text"] as String
        return Json.parse(text) as Map<*, *>
    }

    /** What the server answers bytes sent as they are, on a connection of their own. */
    private fun raw(bytes: String): String = Socket("127.0.0.1", server.port).use { socket ->
        socket.soTimeout = 2000
        socket.getOutputStream().write(bytes.toByteArray())
        runCatching { String(socket.getInputStream().readAllBytes()) }.getOrDefault("")
    }

    @Test
    fun `the file Glut finds the editor by is the owner's alone, and a dead editor's goes`() {
        val home = Files.createTempDirectory("glut-jetbrains-")
        val file = LockFile.write(4711, secret, "PyCharm", listOf("/w"), home)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(LockFile.dir(home))))
        val written = Json.parse(Files.readString(file)) as Map<*, *>
        assertEquals(1L, written["version"])
        assertEquals(ProcessHandle.current().pid(), written["pid"])
        assertEquals(listOf("/w"), written["workspaceFolders"])
        assertEquals(secret, written["authToken"])
        val dead = LockFile.dir(home).resolve("4712.json")
        Files.writeString(dead, """{"pid": 4194321}""")
        LockFile.sweep(home)
        assertTrue(Files.exists(file), "a live editor's file stays")
        assertFalse(Files.exists(dead), "a dead one's goes")
        LockFile.remove(file)
        assertFalse(Files.exists(file))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(LockFile.token()))
    }

    @Test
    fun `Glut hears the editor's place, its buffer and its diagnostics behind the token`() {
        assertEquals("PyCharm", (call("initialize")["serverInfo"] as Map<*, *>)["name"])
        val listed = (call("tools/list")["tools"] as List<*>).map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("openFiles", "diagnostics"), listed)
        val active = tool("getContext")["activeFile"] as Map<*, *>
        assertEquals("fun a()", (active["selection"] as Map<*, *>)["text"])
        assertEquals(mapOf("loaded" to true, "path" to "/w/a.kt", "text" to "unsaved\n", "dirty" to true), tool("readBuffer", mapOf("path" to "/w/a.kt")))
        assertEquals(mapOf("loaded" to false), tool("readBuffer", mapOf("path" to "/w/a.kt.local")))
        val found = tool("diagnostics", mapOf("path" to "/w/a.kt"))["diagnostics"] as List<*>
        assertEquals("error", (found[0] as Map<*, *>)["severity"])
    }

    @Test
    fun `a stranger on the port is refused by the head, before any body is read`() {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        assertTrue(raw("POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.length}\r\n\r\n$body").startsWith("HTTP/1.1 401"))
        assertTrue(raw("GET / HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $secret\r\n\r\n").startsWith("HTTP/1.1 404"))
        assertTrue(
            raw("POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $secret\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
                .startsWith("HTTP/1.1 400"),
        )
        assertTrue(raw("POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $secret\r\nContent-Length: nan\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(raw("POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $secret\r\n" + "X: y\r\n".repeat(4000) + "\r\n").startsWith("HTTP/1.1 413"))
        assertEquals(emptyMap<Any, Any>(), call("ping"))
    }

    @Test
    fun `a diff answers once the owner decided, with their own text, and Glut closes one its card answered`() {
        val waiting = Thread.ofVirtual().start {
            val answer = tool("openDiff", mapOf("id" to "ask-1", "path" to "/w/a.kt", "oldText" to "a\n", "newText" to "b\n", "title" to "a.kt"))
            assertEquals(mapOf("outcome" to "accepted", "text" to "mine\n"), answer)
        }
        Thread.sleep(200)
        val (request, answer) = editor.diffs.getValue("ask-1")
        assertTrue(request.editable)
        answer(DiffAnswer("accepted", "mine\n"))
        waiting.join(3000)

        var closed: Map<*, *>? = null
        val renamed = Thread.ofVirtual().start {
            closed = tool("openDiff", mapOf("id" to "ask-2", "path" to "/w/a.kt", "oldText" to "a\n", "newText" to "b\n", "editable" to false))
        }
        Thread.sleep(200)
        assertFalse(editor.diffs.getValue("ask-2").first.editable, "a rename's diff takes none")
        assertEquals(mapOf("closed" to true), tool("closeDiff", mapOf("id" to "ask-2")))
        renamed.join(3000)
        assertEquals(mapOf("outcome" to "closed"), closed)
    }

    @Test
    fun `a diff waits for the owner past the idle time and closes when its connection ends`() {
        val body = Json.write(
            mapOf(
                "jsonrpc" to "2.0", "id" to 9, "method" to "tools/call",
                "params" to mapOf("name" to "openDiff", "arguments" to mapOf("id" to "ask-3", "path" to "/w/a.kt", "newText" to "x")),
            ),
        )
        val socket = Socket("127.0.0.1", server.port)
        socket.getOutputStream().write(
            "POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $secret\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray(),
        )
        // Three idle times pass: the diff still waits for the owner.
        Thread.sleep(1000)
        assertTrue(editor.diffs.containsKey("ask-3"))
        socket.close()
        Thread.sleep(300)
        assertFalse(editor.diffs.containsKey("ask-3"), "nobody waits for its answer any more")

        tool("notify", mapOf("message" to "answer it in Glut\u001b[2J"))
        assertEquals(listOf("answer it in Glut [2J"), editor.told)
        assertEquals(300, plain("…".repeat(400)).length)
    }

    @Test
    fun `a connection with nothing waiting is closed once idle`() {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 3000
            val started = System.currentTimeMillis()
            assertEquals(-1, socket.getInputStream().read(), "closed by the editor")
            assertTrue(System.currentTimeMillis() - started < 2000)
        }
    }

    @Test
    fun `json reads and writes what the protocol sends`() {
        val text = """{"a":[1,2.5,"x\né",true,null],"b":{"c":"\"q\""}}"""
        val parsed = Json.parse(text) as Map<*, *>
        assertEquals(listOf(1L, 2.5, "x\né", true, null), parsed["a"])
        assertEquals(parsed, Json.parse(Json.write(parsed)))
        assertTrue(runCatching { Json.parse("[".repeat(100) + "]".repeat(100)) }.isFailure, "too deep is no message")
        assertTrue(runCatching { Json.parse("{} x") }.isFailure)
    }
}
