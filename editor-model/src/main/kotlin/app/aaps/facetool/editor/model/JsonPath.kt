package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One step of a JSON path: a key of an object or an index of a list. */
sealed interface PathStep {

    data class Key(val name: String) : PathStep
    data class Index(val index: Int) : PathStep
}

/**
 * JSON paths (design section 6.3): `metadata.name`, `animation.layers[24].x`, `looks[1].patch`.
 * A key that holds a dot or a bracket is written in quotes: `animation.functions["bg()"]`.
 * The empty path is the whole design. All functions give a new tree and leave the old one as it is.
 */
object JsonPaths {

    /** Reads a path text into its steps. */
    fun parse(text: String): List<PathStep> {
        val steps = mutableListOf<PathStep>()
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '.'  -> {
                    if (i == 0 || i == text.length - 1 || text[i + 1] == '.') wrongUse("Bad path '$text': a dot without a key")
                    i++
                }

                '['  -> {
                    val end = if (text.startsWith("[\"", i)) text.indexOf("\"]", i + 2).let { if (it < 0) -1 else it + 1 } else text.indexOf(']', i)
                    if (end < 0) wrongUse("Bad path '$text': '[' without ']'")
                    val inner = text.substring(i + 1, end)
                    if (inner.startsWith("\"")) steps += PathStep.Key(inner.substring(1, inner.length - 1))
                    else steps += PathStep.Index(inner.toIntOrNull()?.takeIf { it >= 0 } ?: wrongUse("Bad path '$text': '$inner' is not an index"))
                    i = end + 1
                }

                else -> {
                    var end = i
                    while (end < text.length && text[end] != '.' && text[end] != '[') end++
                    steps += PathStep.Key(text.substring(i, end))
                    i = end
                }
            }
        }
        return steps
    }

    /** Writes steps as a path text. */
    fun format(steps: List<PathStep>): String {
        val out = StringBuilder()
        for (step in steps) {
            when (step) {
                is PathStep.Index -> out.append('[').append(step.index).append(']')

                is PathStep.Key   ->
                    if (step.name.isEmpty() || step.name.any { it == '.' || it == '[' || it == ']' }) out.append("[\"").append(step.name).append("\"]")
                    else {
                        if (out.isNotEmpty()) out.append('.')
                        out.append(step.name)
                    }
            }
        }
        return out.toString()
    }

    /** The value at [steps], or null when there is none. */
    fun get(root: JsonElement, steps: List<PathStep>): JsonElement? {
        var at: JsonElement = root
        for (step in steps) {
            at = when (step) {
                is PathStep.Key   -> (at as? JsonObject)?.get(step.name)
                is PathStep.Index -> (at as? JsonArray)?.getOrNull(step.index)
            } ?: return null
        }
        return at
    }

    /**
     * Sets the value at [steps]. A key that is there keeps its place; a new key goes to the end.
     * In a list the index must be there, or be the size of the list (the value is added).
     * Objects on the way that are missing are made, and a list entry with the size of its list as index.
     */
    fun set(root: JsonElement, steps: List<PathStep>, value: JsonElement): JsonElement = change(root, steps, 0) { parent, last ->
        when (last) {
            is PathStep.Key   -> JsonObject(LinkedHashMap(asObject(parent, steps)).apply { put(last.name, value) })

            is PathStep.Index -> {
                val list = asArray(parent, steps)
                if (last.index > list.size) wrongUse("${format(steps)}: the list has only ${list.size} entries")
                JsonArray(list.toMutableList().apply { if (last.index == size) add(value) else set(last.index, value) })
            }
        }
    }

    /** Removes the key or the list entry at [steps]. It must be there. */
    fun remove(root: JsonElement, steps: List<PathStep>): JsonElement {
        if (steps.isEmpty()) wrongUse("The whole design cannot be removed")
        if (get(root, steps) == null) wrongUse("${format(steps)}: nothing is there")
        return change(root, steps, 0) { parent, last ->
            when (last) {
                is PathStep.Key   -> JsonObject(LinkedHashMap(asObject(parent, steps)).apply { remove(last.name) })
                is PathStep.Index -> JsonArray(asArray(parent, steps).toMutableList().apply { removeAt(last.index) })
            }
        }
    }

    /**
     * Adds a value at [steps]. In a list the value goes before the entry with that index (the size
     * of the list adds it at the end). In an object the key must be new; it goes to the end.
     */
    fun insert(root: JsonElement, steps: List<PathStep>, value: JsonElement): JsonElement {
        if (steps.isEmpty()) wrongUse("Insert needs a path")
        return change(root, steps, 0) { parent, last ->
            when (last) {
                is PathStep.Key   -> {
                    val map = asObject(parent, steps)
                    if (last.name in map) wrongUse("${format(steps)}: the key is there already, use set")
                    JsonObject(LinkedHashMap(map).apply { put(last.name, value) })
                }

                is PathStep.Index -> {
                    val list = asArray(parent, steps)
                    if (last.index > list.size) wrongUse("${format(steps)}: the list has only ${list.size} entries")
                    JsonArray(list.toMutableList().apply { add(last.index, value) })
                }
            }
        }
    }

    private fun asObject(parent: JsonElement?, steps: List<PathStep>): Map<String, JsonElement> = when (parent) {
        null          -> emptyMap()
        is JsonObject -> parent
        else          -> wrongUse("${format(steps)}: the parent is not an object")
    }

    private fun asArray(parent: JsonElement?, steps: List<PathStep>): List<JsonElement> = when (parent) {
        null         -> emptyList()
        is JsonArray -> parent
        else         -> wrongUse("${format(steps)}: the parent is not a list")
    }

    /** Walks to the parent of the last step and builds the tree again from what [edit] gives for it. */
    private fun change(node: JsonElement?, steps: List<PathStep>, at: Int, edit: (JsonElement?, PathStep) -> JsonElement): JsonElement {
        if (steps.isEmpty()) wrongUse("This needs a path")
        val step = steps[at]
        if (at == steps.size - 1) return edit(node, step)
        return when (step) {
            is PathStep.Key   -> {
                val map = asObject(node, steps.subList(0, at + 1))
                JsonObject(LinkedHashMap(map).apply { put(step.name, change(map[step.name], steps, at + 1, edit)) })
            }

            is PathStep.Index -> {
                val list = asArray(node, steps.subList(0, at + 1))
                if (step.index > list.size) wrongUse("${format(steps.subList(0, at + 1))}: the list has only ${list.size} entries")
                JsonArray(list.toMutableList().apply {
                    if (step.index == size) add(change(null, steps, at + 1, edit)) else set(step.index, change(list[step.index], steps, at + 1, edit))
                })
            }
        }
    }
}
