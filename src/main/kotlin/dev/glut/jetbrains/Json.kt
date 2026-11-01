package dev.glut.jetbrains

/**
 * JSON as the protocol needs it, with no library: objects as maps, arrays as lists, numbers as doubles or longs.
 * Glut's messages are small and of a known shape; this keeps the protocol's core free of the platform, so it is
 * tested on its own.
 */
object Json {
    /** The deepest nesting read: a message of Glut's is a few levels deep, a stranger's thousands would be no message. */
    private const val MOST_DEPTH = 64

    class Malformed(why: String) : Exception(why)

    /** Reads `text` whole. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value(0)
        reader.space()
        if (reader.at < text.length) throw Malformed("text after the value")
        return value
    }

    /** Writes `value`: maps, lists, strings, numbers, booleans and null. */
    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> string(value, out)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Number -> {
                val number = value.toDouble()
                if (number == Math.floor(number) && !number.isInfinite() && Math.abs(number) < 1e15) {
                    out.append(number.toLong())
                } else {
                    out.append(number)
                }
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    string(key.toString(), out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(item, out)
                }
                out.append(']')
            }
            else -> string(value.toString(), out)
        }
    }

    private fun string(text: String, out: StringBuilder) {
        out.append('"')
        for (char in text) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char < ' ' || char == ' ' || char == ' ') {
                    out.append("\\u").append(String.format("%04x", char.code))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
    }

    private class Reader(val text: String) {
        var at = 0

        fun space() {
            while (at < text.length && text[at] in " \t\r\n") at++
        }

        fun value(depth: Int): Any? {
            if (depth > MOST_DEPTH) throw Malformed("too deep")
            space()
            if (at >= text.length) throw Malformed("no value")
            return when (text[at]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> number()
            }
        }

        private fun word(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) throw Malformed("not $word")
            at += word.length
            return value
        }

        private fun obj(depth: Int): Map<String, Any?> {
            at++
            val out = LinkedHashMap<String, Any?>()
            space()
            if (at < text.length && text[at] == '}') {
                at++
                return out
            }
            while (true) {
                space()
                if (at >= text.length || text[at] != '"') throw Malformed("a key")
                val key = string()
                space()
                if (at >= text.length || text[at] != ':') throw Malformed("a colon")
                at++
                out[key] = value(depth + 1)
                space()
                if (at >= text.length) throw Malformed("an end")
                when (text[at++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw Malformed("a comma")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            at++
            val out = ArrayList<Any?>()
            space()
            if (at < text.length && text[at] == ']') {
                at++
                return out
            }
            while (true) {
                out.add(value(depth + 1))
                space()
                if (at >= text.length) throw Malformed("an end")
                when (text[at++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw Malformed("a comma")
                }
            }
        }

        private fun string(): String {
            at++
            val out = StringBuilder()
            while (true) {
                if (at >= text.length) throw Malformed("an end of the string")
                val char = text[at++]
                when (char) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (at >= text.length) throw Malformed("an escape")
                        when (val escaped = text[at++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (at + 4 > text.length) throw Malformed("an escape")
                                out.append(text.substring(at, at + 4).toIntOrNull(16)?.toChar() ?: throw Malformed("an escape"))
                                at += 4
                            }
                            else -> throw Malformed("an escape")
                        }
                    }
                    else -> out.append(char)
                }
            }
        }

        private fun number(): Number {
            val start = at
            while (at < text.length && text[at] in "+-0123456789.eE") at++
            val raw = text.substring(start, at)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw Malformed("a number")
        }
    }
}
