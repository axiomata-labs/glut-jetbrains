package dev.glut.jetbrains

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * The file Glut finds this editor by: ~/.glut/ide/<port>.json with the port, the folders and a fresh token — the folder
 * 0700, the file 0600, written under another name and renamed into place, removed when the project closes. A file an
 * editor left behind when it ended without closing is swept at start.
 */
object LockFile {
    fun dir(home: Path = Path.of(System.getProperty("user.home"))): Path = home.resolve(".glut").resolve("ide")

    /** A token from the system's random source: 16 bytes as 32 hex characters. */
    fun token(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Removes the files of editors whose process is gone. */
    fun sweep(home: Path? = null) {
        val folder = home?.let(::dir) ?: dir()
        val names = runCatching { Files.list(folder).use { it.toList() } }.getOrDefault(emptyList())
        for (file in names) {
            if (!Regex("^\\d+\\.json$").matches(file.fileName.toString())) continue
            val pid = runCatching {
                (Json.parse(Files.readString(file)) as? Map<*, *>)?.get("pid") as? Number
            }.getOrNull()?.toLong()
            val alive = pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            if (!alive) runCatching { Files.deleteIfExists(file) }
        }
    }

    /** Writes the file for `port`, naming `ide` and its `folders`; returns its path. */
    fun write(port: Int, secret: String, ide: String, folders: List<String>, home: Path? = null): Path {
        val folder = home?.let(::dir) ?: dir()
        val closed = PosixFilePermissions.fromString("rwx------")
        Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(closed))
        // An existing folder keeps its mode: closed to everyone else, or Glut does not read it.
        Files.setPosixFilePermissions(folder, closed)
        val file = folder.resolve("$port.json")
        val temporary = folder.resolve("$port.json.${ProcessHandle.current().pid()}.tmp")
        val text = Json.write(
            linkedMapOf(
                "version" to 1,
                "port" to port,
                "pid" to ProcessHandle.current().pid(),
                "ideName" to ide,
                "workspaceFolders" to folders,
                "authToken" to secret,
            ),
        )
        Files.deleteIfExists(temporary)
        // Created new, never through a file or a link already there, readable by the owner alone from its first byte.
        Files.newByteChannel(
            temporary,
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        ).use { it.write(java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))) }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return file
    }

    fun remove(file: Path?) {
        if (file != null) runCatching { Files.deleteIfExists(file) }
    }
}
