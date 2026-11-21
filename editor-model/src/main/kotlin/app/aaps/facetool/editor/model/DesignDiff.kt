package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One difference of two designs. [op] is `change`, `add`, `remove` or `move`. [path] is the place
 * in the first design, [pathAfter] the place in the second one when it is another.
 */
class JsonChange(val op: String, val path: String, val pathAfter: String?, val before: JsonElement?, val after: JsonElement?)

/** One resource that differs. [op] is `change`, `add` or `remove`. */
class ResourceChange(val op: String, val name: String, val before: ByteArray?, val after: ByteArray?)

/**
 * The diff of two designs (design section 8.2): the JSON by path, the resources by hash.
 * Entries of a list are matched by content first, so a layer that was added or moved does not
 * show every later layer as changed. A moved entry is one line.
 */
object DesignDiff {

    fun json(a: JsonElement, b: JsonElement): List<JsonChange> = mutableListOf<JsonChange>().also { compare(a, b, emptyList(), emptyList(), it) }

    fun resources(a: Map<String, ByteArray>, b: Map<String, ByteArray>): List<ResourceChange> = (a.keys + b.keys).sorted().mapNotNull { name ->
        val before = a[name]
        val after = b[name]
        when {
            before == null                 -> ResourceChange("add", name, null, after)
            after == null                  -> ResourceChange("remove", name, before, null)
            !before.contentEquals(after) -> ResourceChange("change", name, before, after)
            else                           -> null
        }
    }

    /** The diff as text, one line per difference. */
    fun text(json: List<JsonChange>, resources: List<ResourceChange>): String {
        if (json.isEmpty() && resources.isEmpty()) return "no differences"
        val lines = mutableListOf<String>()
        for (change in json) {
            val now = change.pathAfter?.let { " (now $it)" } ?: ""
            lines += when (change.op) {
                "add"    -> "+ ${change.path}: ${short(change.after)}"
                "remove" -> "- ${change.path}: ${short(change.before)}"
                "move"   -> "> ${change.path} -> ${change.pathAfter}: ${short(change.before)}"
                else     -> "~ ${change.path}$now: ${short(change.before)} -> ${short(change.after)}"
            }
        }
        for (change in resources) {
            lines += when (change.op) {
                "add"    -> "res + ${change.name}: ${describe(change.name, change.after)}"
                "remove" -> "res - ${change.name}: ${describe(change.name, change.before)}"
                else     -> "res ~ ${change.name}: ${describe(change.name, change.before)} -> ${describe(change.name, change.after)}"
            }
        }
        return lines.joinToString("\n")
    }

    fun toJson(change: JsonChange): JsonObject = buildJsonObject {
        put("op", change.op)
        put("path", change.path)
        change.pathAfter?.let { put("pathAfter", it) }
        change.before?.let { put("before", it) }
        change.after?.let { put("after", it) }
    }

    fun toJson(change: ResourceChange): JsonObject = buildJsonObject {
        put("op", change.op)
        put("name", change.name)
        change.before?.let { put("before", facts(change.name, it)) }
        change.after?.let { put("after", facts(change.name, it)) }
    }

    private fun facts(name: String, bytes: ByteArray): JsonObject = buildJsonObject {
        put("bytes", bytes.size)
        put("hash", Hashes.sha256(bytes))
        DesignLists.pictureSize(name, bytes)?.let { put("width", it.first); put("height", it.second) }
    }

    private fun describe(name: String, bytes: ByteArray?): String {
        if (bytes == null) return ""
        return "${bytes.size} B" + (DesignLists.pictureSize(name, bytes)?.let { ", ${it.first}x${it.second}" } ?: "")
    }

    private fun short(element: JsonElement?): String {
        val text = element?.let { AndroidJson.compact(it).replace("\\/", "/") } ?: ""
        return if (text.length > 100) text.take(97) + "..." else text
    }

    private fun compare(a: JsonElement, b: JsonElement, pathA: List<PathStep>, pathB: List<PathStep>, out: MutableList<JsonChange>) {
        if (a == b) return
        when {
            a is JsonObject && b is JsonObject -> {
                for ((key, value) in a) {
                    val other = b[key]
                    if (other == null) out += JsonChange("remove", JsonPaths.format(pathA + PathStep.Key(key)), null, value, null)
                    else compare(value, other, pathA + PathStep.Key(key), pathB + PathStep.Key(key), out)
                }
                for ((key, value) in b) if (key !in a) out += JsonChange("add", JsonPaths.format(pathB + PathStep.Key(key)), null, null, value)
            }

            a is JsonArray && b is JsonArray   -> lists(a, b, pathA, pathB, out)

            else                               -> {
                val before = JsonPaths.format(pathA)
                val after = JsonPaths.format(pathB)
                out += JsonChange("change", before, after.takeIf { it != before }, a, b)
            }
        }
    }

    private fun lists(a: List<JsonElement>, b: List<JsonElement>, pathA: List<PathStep>, pathB: List<PathStep>, out: MutableList<JsonChange>) {
        val pairs = if (a.size.toLong() * b.size > 4_000_000L) (0 until minOf(a.size, b.size)).filter { a[it] == b[it] }.map { it to it } else same(a, b)
        val restA = (a.indices - pairs.map { it.first }.toSet()).toMutableList()
        val restB = (b.indices - pairs.map { it.second }.toSet()).toMutableList()
        fun at(path: List<PathStep>, index: Int): String = JsonPaths.format(path + PathStep.Index(index))

        // The same content at another place: a move
        val moves = mutableListOf<Pair<Int, Int>>()
        for (i in restA.toList()) {
            val j = restB.firstOrNull { b[it] == a[i] } ?: continue
            moves += i to j
            restA -= i
            restB -= j
        }
        // What is left between the same two fixed entries: a changed entry, when the two are alike
        val changed = mutableListOf<Pair<Int, Int>>()
        val fixedA = pairs.map { it.first }
        val fixedB = pairs.map { it.second }
        fun gap(fixed: List<Int>, index: Int): Int = fixed.count { it < index }
        for (i in restA.toList()) {
            val j = restB.filter { gap(fixedB, it) == gap(fixedA, i) && alike(a[i], b[it]) > 0 }.maxByOrNull { alike(a[i], b[it]) } ?: continue
            changed += i to j
            restA -= i
            restB -= j
        }

        val lines = mutableListOf<Pair<Int, () -> Unit>>()
        for ((i, j) in moves) lines += i to { out += JsonChange("move", at(pathA, i), at(pathB, j), a[i], null) }
        for ((i, j) in changed) lines += i to { compare(a[i], b[j], pathA + PathStep.Index(i), pathB + PathStep.Index(j), out) }
        for (i in restA) lines += i to { out += JsonChange("remove", at(pathA, i), null, a[i], null) }
        lines.sortedBy { it.first }.forEach { it.second() }
        for (j in restB) out += JsonChange("add", at(pathB, j), null, null, b[j])
    }

    /** How alike two list entries are. 0: not alike, they are shown as one removed and one added entry. */
    private fun alike(a: JsonElement, b: JsonElement): Int = when {
        a is JsonObject && b is JsonObject -> a.keys.count { it in b } + 2 * a.entries.count { b[it.key] == it.value }
        a is JsonArray && b is JsonArray   -> 1
        a is JsonPrimitive && b is JsonPrimitive -> 1
        else                               -> 0
    }

    /** The longest run of entries that are the same in both lists, in order: pairs of indexes. */
    private fun same(a: List<JsonElement>, b: List<JsonElement>): List<Pair<Int, Int>> {
        val length = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            length[i][j] = if (a[i] == b[j]) length[i + 1][j + 1] + 1 else maxOf(length[i + 1][j], length[i][j + 1])
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j]                         -> {
                    pairs += i to j
                    i++
                    j++
                }

                length[i + 1][j] >= length[i][j + 1] -> i++
                else                                 -> j++
            }
        }
        return pairs
    }
}
