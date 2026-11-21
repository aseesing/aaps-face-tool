package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The edit operations on a design JSON (design section 8.2). Every function gives a new design
 * and leaves the old one as it is, so a list of edits is all or none.
 */
object DesignEdits {

    /** The path of the layer list. */
    const val LAYERS = "animation.layers"

    fun set(design: JsonObject, path: String, value: JsonElement): JsonObject = JsonPaths.set(design, steps(path), value) as JsonObject

    fun unset(design: JsonObject, path: String): JsonObject = JsonPaths.remove(design, steps(path)) as JsonObject

    fun insert(design: JsonObject, path: String, value: JsonElement): JsonObject = JsonPaths.insert(design, steps(path), value) as JsonObject

    /** Takes the value at [from] out and adds it at [to]. [to] is read after the value is out. */
    fun move(design: JsonObject, from: String, to: String): JsonObject {
        val value = JsonPaths.get(design, steps(from)) ?: wrongUse("$from: nothing is there")
        return insert(unset(design, from), to, value)
    }

    /**
     * A list of edits in one step: `[{"op": "set", "path": ..., "value": ...}, {"op": "unset", "path": ...},
     * {"op": "insert", "path": ..., "value": ...}, {"op": "move", "from": ..., "to": ...}]`.
     */
    fun apply(design: JsonObject, patch: JsonArray): JsonObject {
        var result = design
        patch.forEachIndexed { i, entry ->
            val edit = entry as? JsonObject ?: wrongUse("Edit $i of the patch is not an object")
            fun text(key: String): String = (edit[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: wrongUse("Edit $i of the patch needs \"$key\" as a text")
            fun value(): JsonElement = edit["value"] ?: wrongUse("Edit $i of the patch needs \"value\"")
            result = try {
                when (val op = text("op")) {
                    "set"    -> set(result, text("path"), value())
                    "unset"  -> unset(result, text("path"))
                    "insert" -> insert(result, text("path"), value())
                    "move"   -> move(result, text("from"), text("to"))
                    else     -> wrongUse("unknown op \"$op\" (set, unset, insert, move)")
                }
            } catch (e: FacetoolException) {
                throw FacetoolException("Edit $i of the patch: ${e.message}. Nothing was changed.", e.exitCode)
            }
        }
        return result
    }

    /** The layers of the design, in paint order. Empty when it has none. */
    fun layers(design: JsonObject): List<JsonElement> = JsonPaths.get(design, steps(LAYERS)) as? JsonArray ?: emptyList()

    /** Adds a layer at index [at]; null adds it at the end (on top). Makes the layer list when the design has none. */
    fun layerAdd(design: JsonObject, layer: JsonElement, at: Int?): JsonObject {
        if (layer !is JsonObject) wrongUse("A layer is a JSON object")
        val count = layers(design).size
        return insert(design, "$LAYERS[${checkIndex(at ?: count, count + 1)}]", layer)
    }

    /** Moves the layer [from] so that it gets the index [to]. */
    fun layerMove(design: JsonObject, from: Int, to: Int): JsonObject {
        val count = layers(design).size
        return move(design, "$LAYERS[${checkIndex(from, count)}]", "$LAYERS[${checkIndex(to, count)}]")
    }

    /** Copies the layer [index] to the index [at]; null puts the copy right after the layer. */
    fun layerCopy(design: JsonObject, index: Int, at: Int?): JsonObject {
        val all = layers(design)
        return insert(design, "$LAYERS[${checkIndex(at ?: (index + 1), all.size + 1)}]", all[checkIndex(index, all.size)])
    }

    fun layerRemove(design: JsonObject, index: Int): JsonObject = unset(design, "$LAYERS[${checkIndex(index, layers(design).size)}]")

    private fun checkIndex(index: Int, count: Int): Int =
        if (index in 0 until count) index else wrongUse(if (count == 0) "The design has no layers" else "Layer $index: the index goes from 0 to ${count - 1}")

    private fun steps(path: String): List<PathStep> = JsonPaths.parse(path)
}
