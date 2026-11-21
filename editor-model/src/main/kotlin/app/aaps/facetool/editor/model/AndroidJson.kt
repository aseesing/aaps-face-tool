package app.aaps.facetool.editor.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads JSON and writes it in the form of Android's `JSONObject.toString(4)` (design section 6.2).
 *
 * The design zips hold JSON as Android writes it: 4 spaces, keys in the order they were added,
 * `/` written as `\/`. This writer gives the same bytes, so a design saved by the editor and the
 * same design from a generator do not differ. Numbers stay as they are written (`0.10` stays `0.10`).
 */
object AndroidJson {

    private val reader = Json

    /** Reads [text]. Throws [IllegalArgumentException] when it is not JSON. */
    fun parse(text: String): JsonElement = reader.parseToJsonElement(text).also { check(it) }

    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?")

    /** The reader takes any bare word as a value. Only numbers, `true`, `false` and `null` are JSON. */
    private fun check(element: JsonElement) {
        when (element) {
            is JsonObject    -> element.values.forEach { check(it) }
            is JsonArray     -> element.forEach { check(it) }
            is JsonNull      -> Unit
            is JsonPrimitive -> if (!element.isString && element.content != "true" && element.content != "false" && !number.matches(element.content))
                throw IllegalArgumentException("'${element.content}' is not a JSON value (a text needs quotes)")
        }
    }

    /** Reads [text] as a JSON object, or ends with exit code 3. [what] names the text in the message. */
    fun parseObject(text: String, what: String): JsonObject {
        val element = try {
            parse(text)
        } catch (e: IllegalArgumentException) {
            unreadable("$what is not JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        return element as? JsonObject ?: unreadable("$what is not a JSON object")
    }

    /** Writes [element] with [indent] spaces per level, as `JSONObject.toString(indent)`. 0 gives the compact form. */
    fun write(element: JsonElement, indent: Int = 4): String {
        val out = StringBuilder()
        write(element, " ".repeat(indent), 0, out)
        return out.toString()
    }

    /** Writes [element] on one line, as `JSONObject.toString()`. */
    fun compact(element: JsonElement): String = write(element, 0)

    private fun write(element: JsonElement, indent: String, depth: Int, out: StringBuilder) {
        when (element) {
            is JsonNull      -> out.append("null")
            is JsonPrimitive -> if (element.isString) string(element.content, out) else out.append(element.content)

            is JsonObject    -> {
                out.append('{')
                var first = true
                for ((key, value) in element) {
                    if (!first) out.append(',')
                    first = false
                    newline(indent, depth + 1, out)
                    string(key, out)
                    out.append(if (indent.isEmpty()) ":" else ": ")
                    write(value, indent, depth + 1, out)
                }
                if (!first) newline(indent, depth, out)
                out.append('}')
            }

            is JsonArray     -> {
                out.append('[')
                var first = true
                for (value in element) {
                    if (!first) out.append(',')
                    first = false
                    newline(indent, depth + 1, out)
                    write(value, indent, depth + 1, out)
                }
                if (!first) newline(indent, depth, out)
                out.append(']')
            }
        }
    }

    private fun newline(indent: String, depth: Int, out: StringBuilder) {
        if (indent.isEmpty()) return
        out.append('\n')
        repeat(depth) { out.append(indent) }
    }

    /** The escapes of Android's `JSONStringer`. */
    private fun string(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"', '\\', '/' -> out.append('\\').append(c)
                '\t'           -> out.append("\\t")
                '\b'           -> out.append("\\b")
                '\n'           -> out.append("\\n")
                '\r'           -> out.append("\\r")
                '\u000C'       -> out.append("\\f")
                else           -> if (c.code <= 0x1F) out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
        }
        out.append('"')
    }
}
