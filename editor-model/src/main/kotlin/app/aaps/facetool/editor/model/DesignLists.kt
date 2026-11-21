package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** The lists of `facetool list` (design section 6.3): one object per entry, with a `summary` text. */
object DesignLists {

    val KINDS: List<String> = listOf("layers", "views", "templates", "options", "looks", "resources", "fonts")

    /** Top level objects of a design that are not views. */
    private val notViews = setOf("metadata", "animation", "dynData", "dynPref", "colourLooks", "complicationStyle")

    fun list(kind: String, design: JsonObject, resources: Map<String, ByteArray>): List<JsonObject> = when (kind) {
        "layers"    -> layers(design)
        "views"     -> views(design)
        "templates" -> templates(design)
        "options"   -> options(design)
        "looks"     -> looks(design)
        "resources" -> resources(design, resources)
        "fonts"     -> fonts(design, resources)
        else        -> wrongUse("Unknown list '$kind'. Lists: ${KINDS.joinToString(", ")}")
    }

    /** The source of a layer in a few words: `image Aurora`, `shape circle x120`, `text clock()`, `use rain`, `view sgv`. */
    fun layerSummary(layer: JsonElement): String {
        if (layer !is JsonObject) return "not an object"
        val count = text(layer["count"])?.let { " x$it" } ?: ""
        val target = if ("use" in layer) text(layer["view"])?.let { " view $it" } ?: text(layer["image"])?.let { " image $it" } ?: "" else ""
        return when {
            "use" in layer   -> "use ${text(layer["use"])}$target"
            "image" in layer -> "image ${text(layer["image"])}$count"
            "shape" in layer -> "shape ${text(layer["shape"])}$count"
            "text" in layer  -> "text ${text(layer["text"])}"
            "view" in layer  -> "view ${text(layer["view"])}"
            else             -> "keys ${layer.keys.take(4).joinToString(", ")}"
        }
    }

    fun layers(design: JsonObject): List<JsonObject> = DesignEdits.layers(design).mapIndexed { i, layer ->
        buildJsonObject {
            put("index", i)
            put("path", "${DesignEdits.LAYERS}[$i]")
            put("summary", layerSummary(layer))
            (layer as? JsonObject)?.let { fields -> listOf("slot", "group", "blend").forEach { key -> text(fields[key])?.let { put(key, it) } } }
        }
    }

    fun views(design: JsonObject): List<JsonObject> = design.entries.filter { it.value is JsonObject && it.key !in notViews }.map { (name, value) ->
        val view = value as JsonObject
        val parts = listOfNotNull(
            text(view["visibility"]),
            if ("width" in view && "height" in view) "${text(view["width"])}x${text(view["height"])}" else null,
            if ("leftmargin" in view && "topmargin" in view) "at ${text(view["leftmargin"])},${text(view["topmargin"])}" else null,
            text(view["font"])?.let { "font $it" },
            text(view["textsize"])?.let { "size $it" },
            text(view["background"])?.let { "background $it" },
            text(view["dynData"])?.let { "dynData $it" }
        )
        buildJsonObject {
            put("name", name)
            put("path", name)
            put("summary", parts.joinToString(" ").ifEmpty { "empty" })
        }
    }

    fun templates(design: JsonObject): List<JsonObject> = (JsonPaths.get(design, JsonPaths.parse("animation.templates")) as? JsonObject).orEmpty().map { (name, value) ->
        val template = value as? JsonObject
        val params = (template?.get("params") as? JsonObject)?.keys.orEmpty()
        val layers = (template?.get("layers") as? JsonArray)?.size ?: 0
        val used = DesignEdits.layers(design).count { text((it as? JsonObject)?.get("use")) == name }
        buildJsonObject {
            put("name", name)
            put("path", JsonPaths.format(listOf(PathStep.Key("animation"), PathStep.Key("templates"), PathStep.Key(name))))
            put("summary", "$layers layers, used $used times, params: ${params.joinToString(", ").ifEmpty { "none" }}")
        }
    }

    fun options(design: JsonObject): List<JsonObject> = (design["options"] as? JsonArray).orEmpty().mapIndexed { i, value ->
        val option = value as? JsonObject
        val choices = (option?.get("choices") as? JsonArray).orEmpty().mapIndexed { n, choice -> "$n ${text(choice)}" }
        buildJsonObject {
            put("index", i)
            put("name", text(option?.get("name")) ?: "")
            put("path", "options[$i]")
            put("summary", "\"${text(option?.get("title"))}\": ${choices.joinToString(", ")}; default ${text(option?.get("default"))}")
        }
    }

    fun looks(design: JsonObject): List<JsonObject> = (design["looks"] as? JsonArray).orEmpty().mapIndexed { i, value ->
        val look = value as? JsonObject
        val patch = (look?.get("patch") as? JsonObject)?.keys.orEmpty()
        buildJsonObject {
            put("index", i)
            put("path", "looks[$i]")
            put("summary", "when ${text(look?.get("when"))}: patches ${patch.joinToString(", ").ifEmpty { "nothing" }}")
        }
    }

    fun resources(design: JsonObject, resources: Map<String, ByteArray>): List<JsonObject> {
        val uses = ResourceNames.uses(design).groupBy { it.key }
        return resources.map { (name, bytes) ->
            val key = ResourceNames.keyOf(name)
            val kind = when {
                ResourceNames.isImage(name) -> "picture"
                ResourceNames.isFont(name)  -> "font"
                else                        -> "other"
            }
            val used = uses[key]?.size ?: 0
            val use = when {
                kind == "other"                 -> "kept"
                used > 0                        -> "named $used times"
                key in ResourceNames.FIXED_KEYS -> "engine name"
                else                            -> "not named"
            }
            val size = pictureSize(name, bytes)
            buildJsonObject {
                put("name", name)
                put("key", key)
                put("kind", kind)
                put("bytes", bytes.size)
                size?.let { put("width", it.first); put("height", it.second) }
                put("uses", used)
                put("summary", listOfNotNull(kind, "${bytes.size} B", size?.let { "${it.first}x${it.second}" }, use).joinToString(", "))
            }
        }
    }

    fun fonts(design: JsonObject, resources: Map<String, ByteArray>): List<JsonObject> {
        val files = resources.keys.filter { ResourceNames.isFont(it) }.associateBy { ResourceNames.keyOf(it) }
        val named = mutableMapOf<String, Int>()
        fun walk(element: JsonElement, key: String?) {
            when (element) {
                is JsonObject    -> element.forEach { walk(it.value, it.key) }
                is JsonArray     -> element.forEach { walk(it, key) }
                is JsonPrimitive -> if ((key == "font" || key == "fontTitle") && element.isString && element.content.isNotEmpty() && !element.content.contains('$'))
                    named[element.content] = (named[element.content] ?: 0) + 1
            }
        }
        walk(design, null)
        return (named.keys + files.keys).distinct().map { name ->
            val where = when {
                name in files                                    -> "file ${files[name]}"
                name.lowercase() in ResourceNames.BUILT_IN_FONTS -> "built in"
                else                                             -> "missing"
            }
            buildJsonObject {
                put("name", name)
                put("uses", named[name] ?: 0)
                put("source", where)
                put("summary", "$where, named ${named[name] ?: 0} times")
            }
        }
    }

    /** The width and height of a PNG or JPEG picture, or null. */
    fun pictureSize(name: String, bytes: ByteArray): Pair<Int, Int>? {
        if (ResourceNames.extensionOf(name) !in setOf("png", "jpg", "jpeg")) return null
        return runCatching {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
                try {
                    reader.input = input
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
    }

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.content
}
