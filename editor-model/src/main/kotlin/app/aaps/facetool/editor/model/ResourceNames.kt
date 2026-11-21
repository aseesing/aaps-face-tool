package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A place in the design JSON that names a resource: the JSON path and the resource key. */
data class ResourceUse(val path: String, val key: String, val font: Boolean)

/**
 * How a design JSON names its resources. A resource is named by its key: the file name without
 * the extension. These tables copy the names of the AAPS engine (`JsonKeys`, `ResFileMap`,
 * `JsonKeyValues`, `ResFormat`).
 */
object ResourceNames {

    /** The file name of the design JSON in a zip and in a project folder. */
    const val DESIGN_JSON = "CustomWatchface.json"

    /** File names (without extension) the engine loads by itself, without a name in the JSON. */
    val FIXED_KEYS: Set<String> = buildSet {
        add("CustomWatchface")
        for (base in listOf("Background", "CoverChart", "CoverPlate", "HourHand", "MinuteHand", "SecondHand")) {
            add(base); add(base + "High"); add(base + "Low")
        }
        addAll(listOf("ArrowNone", "ArrowDoubleUp", "ArrowSingleUp", "Arrow45Up", "ArrowFlat", "Arrow45Down", "ArrowSingleDown", "ArrowDoubleDown"))
    }

    /** The fonts the engine has built in. */
    val BUILT_IN_FONTS: Set<String> = setOf(
        "sans_serif", "default", "default_bold", "monospace", "serif",
        "roboto_condensed_bold", "roboto_condensed_light", "roboto_condensed_regular", "roboto_slab_light"
    )

    val IMAGE_EXTENSIONS: Set<String> = setOf("png", "jpg", "svg")
    val FONT_EXTENSIONS: Set<String> = setOf("ttf", "otf")

    private val imageKey = Regex("(image\\d*|invalidImage|background|mask)")
    private val fontKey = Regex("(font|fontTitle)")

    /** The key of a file name: the name without its extension. */
    fun keyOf(fileName: String): String = fileName.substringAfterLast('/').substringBeforeLast('.')

    /** The extension of a file name in small letters, or an empty text. */
    fun extensionOf(fileName: String): String = fileName.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun isFont(fileName: String): Boolean = extensionOf(fileName) in FONT_EXTENSIONS

    fun isImage(fileName: String): Boolean = extensionOf(fileName) in IMAGE_EXTENSIONS

    /**
     * Every place in [design] that names a resource file: a text under `image`, `image1`...,
     * `invalidImage`, `background`, `mask`, `font` or `fontTitle`. Left out: empty texts, template
     * parameters (`$name`), engine pictures (`@moon`) and the built-in fonts.
     */
    fun uses(design: JsonElement): List<ResourceUse> {
        val found = mutableListOf<ResourceUse>()
        walk(design, emptyList(), null, found)
        return found
    }

    private fun walk(element: JsonElement, path: List<PathStep>, key: String?, found: MutableList<ResourceUse>) {
        when (element) {
            is JsonObject    -> for ((name, value) in element) walk(value, path + PathStep.Key(name), name, found)
            is JsonArray     -> element.forEachIndexed { i, value -> walk(value, path + PathStep.Index(i), key, found) }

            is JsonPrimitive -> {
                if (!element.isString || key == null) return
                val text = element.content
                if (text.isEmpty() || text.startsWith("@") || text.contains('$')) return
                when {
                    imageKey.matches(key)                                           -> found += ResourceUse(JsonPaths.format(path), text, false)
                    fontKey.matches(key) && text.lowercase() !in BUILT_IN_FONTS -> found += ResourceUse(JsonPaths.format(path), text, true)
                }
            }
        }
    }

    /** The design with every resource name [old] changed to [new], at the places [uses] finds. */
    fun rename(design: JsonObject, old: String, new: String): JsonObject {
        var result: JsonElement = design
        for (use in uses(design)) if (use.key == old) result = JsonPaths.set(result, JsonPaths.parse(use.path), JsonPrimitive(new))
        return result as JsonObject
    }

    /** Paths of texts equal to [key] that [uses] does not count as a resource name (a template parameter, a title). */
    fun otherTexts(design: JsonElement, key: String): List<String> {
        val known = uses(design).map { it.path }.toSet()
        val found = mutableListOf<String>()
        fun walk(element: JsonElement, path: List<PathStep>) {
            when (element) {
                is JsonObject    -> for ((name, value) in element) walk(value, path + PathStep.Key(name))
                is JsonArray     -> element.forEachIndexed { i, value -> walk(value, path + PathStep.Index(i)) }
                is JsonPrimitive -> if (element.isString && element.content == key) JsonPaths.format(path).let { if (it !in known) found += it }
            }
        }
        walk(design, emptyList())
        return found
    }
}
