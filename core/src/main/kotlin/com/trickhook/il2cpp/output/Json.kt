package com.trickhook.il2cpp.output

import java.io.Writer

class JsonWriter(private val out: Writer) {

    private val emptyStack = ArrayDeque<Boolean>()
    private var indent = 0
    private var afterName = false

    fun beginObject(): JsonWriter {
        separate()
        out.write(OPEN_BRACE)
        indent++
        emptyStack.addFirst(true)
        return this
    }

    fun endObject(): JsonWriter {
        val wasEmpty = emptyStack.removeFirst()
        indent--
        if (!wasEmpty) writeIndent()
        out.write(CLOSE_BRACE)
        return this
    }

    fun beginArray(): JsonWriter {
        separate()
        out.write(OPEN_BRACKET)
        indent++
        emptyStack.addFirst(true)
        return this
    }

    fun endArray(): JsonWriter {
        val wasEmpty = emptyStack.removeFirst()
        indent--
        if (!wasEmpty) writeIndent()
        out.write(CLOSE_BRACKET)
        return this
    }

    fun name(name: String): JsonWriter {
        separate()
        writeEscaped(name)
        out.write(NAME_SEPARATOR)
        afterName = true
        return this
    }

    fun value(text: String?): JsonWriter {
        separate()
        if (text == null) out.write(NULL) else writeEscaped(text)
        return this
    }

    fun value(number: Long): JsonWriter {
        separate()
        out.write(java.lang.Long.toUnsignedString(number))
        return this
    }

    fun nullValue(): JsonWriter {
        separate()
        out.write(NULL)
        return this
    }

    fun property(name: String, text: String?): JsonWriter = name(name).value(text)

    fun property(name: String, number: Long): JsonWriter = name(name).value(number)

    private fun separate() {
        if (afterName) {
            afterName = false
            return
        }
        if (emptyStack.isEmpty()) return
        if (emptyStack[0]) emptyStack[0] = false else out.write(COMMA)
        writeIndent()
    }

    private fun writeIndent() {
        out.write(NEWLINE)
        var level = indent
        while (level >= INDENT_CHUNKS) {
            out.write(INDENT_BLOCK)
            level -= INDENT_CHUNKS
        }
        while (level > 0) {
            out.write(INDENT_UNIT)
            level--
        }
    }

    private fun writeEscaped(text: String) {
        out.write(QUOTE)
        var plainStart = 0
        for (i in text.indices) {
            val ch = text[i]
            val escape = escapeFor(ch)
            if (escape != null) {
                if (i > plainStart) out.write(text, plainStart, i - plainStart)
                out.write(escape)
                plainStart = i + 1
            }
        }
        if (text.length > plainStart) out.write(text, plainStart, text.length - plainStart)
        out.write(QUOTE)
    }

    private fun escapeFor(ch: Char): String? = when {
        ch == '\b' -> "\\b"
        ch == '\u000C' -> "\\f"
        ch == '\n' -> "\\n"
        ch == '\r' -> "\\r"
        ch == '\t' -> "\\t"
        ch == '"' -> "\\u0022"
        ch == '\\' -> "\\\\"
        ch.code < 0x20 || ch.code > 0x7E -> unicodeEscape(ch)
        ch == '<' || ch == '>' || ch == '&' || ch == '\'' || ch == '+' || ch == '`' -> unicodeEscape(ch)
        else -> null
    }

    private fun unicodeEscape(ch: Char): String {
        val code = ch.code
        val buffer = CharArray(6)
        buffer[0] = '\\'
        buffer[1] = 'u'
        buffer[2] = HEX[(code ushr 12) and 0xF]
        buffer[3] = HEX[(code ushr 8) and 0xF]
        buffer[4] = HEX[(code ushr 4) and 0xF]
        buffer[5] = HEX[code and 0xF]
        return String(buffer)
    }

    private companion object {
        const val OPEN_BRACE = "{"
        const val CLOSE_BRACE = "}"
        const val OPEN_BRACKET = "["
        const val CLOSE_BRACKET = "]"
        const val COMMA = ","
        const val QUOTE = "\""
        const val NULL = "null"
        const val NEWLINE = "\n"
        const val NAME_SEPARATOR = ": "
        const val INDENT_UNIT = "  "
        const val INDENT_CHUNKS = 8
        const val INDENT_BLOCK = "                "
        val HEX = "0123456789ABCDEF".toCharArray()
    }
}
