package dev.glut.jetbrains

import java.nio.file.Path
import kotlin.test.Test

/**
 * Serves the protocol with a stand-in editor under a home of its own for Glut's live test, when `GLUT_SERVE_HOME`
 * names one:
 *   GLUT_SERVE_HOME=<home> ./gradlew test --tests '*LiveTest*'   then
 *   GLUT_IDE_HOME=<home> cargo test -p glut-tui --lib ide::live -- --ignored
 */
class LiveTest {
    @Test
    fun `serves Glut for a while`() {
        val home = System.getenv("GLUT_SERVE_HOME") ?: return
        val shown = java.util.concurrent.ConcurrentHashMap<String, (DiffAnswer) -> Unit>()
        val editor = object : Editor {
            override fun activeFile() = ActiveFile("/w/a.kt", Position(1, 1), null)
            override fun openFiles() = listOf(OpenFile("/w/a.kt", active = true, dirty = true))
            override fun buffer(path: String) = if (path == "/w/a.kt") Buffer(path, "unsaved\n", true) else null
            override fun diagnostics(path: String): Pair<String, List<Diagnostic>>? = null
            override fun openDiff(request: DiffRequest, answer: (DiffAnswer) -> Unit) {
                shown[request.id] = answer
            }
            override fun closeDiff(id: String) {
                shown.remove(id)?.invoke(DiffAnswer.CLOSED)
            }
            override fun changed(paths: List<String>) {}
            override fun notify(message: String) {}
        }
        val diffs = Diffs(editor)
        val secret = LockFile.token()
        val server = Server(secret, { message, respond, connection ->
            handle(editor, diffs, VERSION, "PyCharm", message, respond, connection)
        }, { diffs.ended(it) })
        server.start()
        val file = LockFile.write(server.port, secret, "PyCharm", listOf("/w"), Path.of(home))
        Thread.sleep(25_000)
        LockFile.remove(file)
        server.stop()
    }
}
