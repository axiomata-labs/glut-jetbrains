package dev.glut.jetbrains

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * The editor's side of Glut's editor protocol (docs/editor-protocol.md in Glut): MCP over HTTP on loopback, a bearer
 * token on every request. Glut connects; this answers — one JSON-RPC message a request, JSON answers, keep-alive.
 *
 * A request is judged by its head alone before any of its body is read: without the token, or not `POST /mcp`, or with
 * a length that is no plain number or any transfer encoding, it is answered and the connection closed. A connection's
 * end is heard, so the diffs it asked for close with it.
 */
class Server(
    private val token: String,
    private val handle: (message: Map<String, Any?>, respond: (Any?, String?) -> Unit, connection: Int) -> Unit,
    private val closed: (connection: Int) -> Unit,
    /** How long a connection may be idle; shorter in the tests. */
    private val idleMs: Int = IDLE_MS,
) {
    companion object {
        /** The largest request body taken: a file's proposed text, not a disk. */
        const val MAX_BODY = 8 * 1024 * 1024
        /** The largest head of a request. */
        const val MAX_HEAD = 16 * 1024
        /** How many connections at once: Glut opens one for its questions and one for each diff. */
        const val MOST_CONNECTIONS = 32
        /** How long a connection may say nothing, with no question of its own waiting, before it is closed. */
        const val IDLE_MS = 60 * 1000

        private val REASONS = mapOf(
            200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized",
            404 to "Not Found", 413 to "Payload Too Large", 503 to "Service Unavailable",
        )

        /** Compares two strings in time that does not depend on where they differ. */
        fun same(a: String?, b: String): Boolean =
            a != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        /** A request's head: its method, path, headers by lower-case name and body length — or why it is refused. */
        data class Head(val method: String, val path: String, val headers: Map<String, String>, val length: Int)

        fun parseHead(head: String): Pair<Head?, Int> {
            val lines = head.split("\r\n")
            val start = Regex("^([A-Z]+) (\\S+) HTTP/1\\.\\d$").find(lines.first()) ?: return null to 400
            val headers = HashMap<String, String>()
            var lengths = 0
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0) return null to 400
                val name = line.substring(0, colon).lowercase()
                if (name == "content-length") lengths++
                headers[name] = line.substring(colon + 1).trim()
            }
            if ("transfer-encoding" in headers || lengths > 1) return null to 400
            var length = 0
            if (lengths == 1) {
                val digits = headers.getValue("content-length")
                if (!Regex("^\\d{1,9}$").matches(digits)) return null to 400
                length = digits.toInt()
            }
            if (length > MAX_BODY) return null to 413
            return Head(start.groupValues[1], start.groupValues[2], headers, length) to 200
        }
    }

    private val socket = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
    private val connections = AtomicInteger(0)
    private val ids = AtomicInteger(0)
    private val open = java.util.Collections.synchronizedSet(HashSet<Socket>())

    /** The port the system picked. */
    val port: Int get() = socket.localPort

    fun start() {
        Thread.ofPlatform().daemon().name("glut-server").start {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                if (connections.get() >= MOST_CONNECTIONS) {
                    runCatching { reply(client.getOutputStream(), 503, "") }
                    runCatching { client.close() }
                    continue
                }
                connections.incrementAndGet()
                open.add(client)
                val connection = ids.incrementAndGet()
                Thread.ofVirtual().name("glut-connection-$connection").start { serve(client, connection) }
            }
        }
    }

    fun stop() {
        runCatching { socket.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
    }

    private fun reply(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${REASONS[status] ?: ""}\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n"
        synchronized(out) {
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }

    private fun serve(client: Socket, connection: Int) {
        // Questions of this connection still waiting for their answer (a diff waits for the owner): no idleness then.
        val pending = AtomicInteger(0)
        try {
            client.soTimeout = idleMs
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            while (true) {
                val head = try {
                    readHead(input) ?: return
                } catch (_: SocketTimeoutException) {
                    if (pending.get() > 0) continue else return
                }
                if (head == "") {
                    reply(out, 413, "")
                    return
                }
                val (parsed, status) = parseHead(head)
                if (parsed == null) {
                    reply(out, status, "")
                    return
                }
                // The token and the place first, by the head alone: nothing of a stranger's body is read.
                if (!same(parsed.headers["authorization"], "Bearer $token")) {
                    reply(out, 401, "")
                    return
                }
                if (parsed.method != "POST" || parsed.path != "/mcp") {
                    reply(out, 404, "")
                    return
                }
                val body = input.readNBytes(parsed.length)
                if (body.size < parsed.length) return
                val message = runCatching { Json.parse(String(body, Charsets.UTF_8)) }.getOrNull()
                if (message !is Map<*, *>) {
                    reply(out, 400, "")
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val typed = message as Map<String, Any?>
                val id = typed["id"]
                if (id == null) {
                    // A notification: nothing to answer but that it came.
                    runCatching { handle(typed, { _, _ -> }, connection) }
                    reply(out, 202, "")
                    continue
                }
                pending.incrementAndGet()
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                val respond: (Any?, String?) -> Unit = respond@{ result, failure ->
                    if (!answered.compareAndSet(false, true)) return@respond
                    pending.decrementAndGet()
                    val answer = LinkedHashMap<String, Any?>()
                    answer["jsonrpc"] = "2.0"
                    answer["id"] = id
                    if (failure != null) {
                        answer["error"] = mapOf("code" to -32603, "message" to failure)
                    } else {
                        answer["result"] = result ?: emptyMap<String, Any?>()
                    }
                    runCatching { reply(out, 200, Json.write(answer)) }
                }
                try {
                    handle(typed, respond, connection)
                } catch (error: Exception) {
                    respond(null, error.message ?: error.toString())
                }
            }
        } catch (_: IOException) {
            // The connection went.
        } finally {
            runCatching { client.close() }
            open.remove(client)
            connections.decrementAndGet()
            closed(connection)
        }
    }

    /** The head of the next request, without its blank line; `null` at the connection's end, `""` when too long. */
    private fun readHead(input: BufferedInputStream): String? {
        val head = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            head.write(byte)
            matched = if (byte.toByte() == end[matched]) matched + 1 else if (byte.toByte() == end[0]) 1 else 0
            if (matched == 4) {
                val text = head.toString(Charsets.ISO_8859_1)
                return text.substring(0, text.length - 4)
            }
            if (head.size() > MAX_HEAD) return ""
        }
    }
}
