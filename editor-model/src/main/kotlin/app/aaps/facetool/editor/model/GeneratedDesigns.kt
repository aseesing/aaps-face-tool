package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The designs that the Kotlin generators of `aaps-watchfaces` make (design section 6.6).
 * [names] are the `metadata.filename` values of those designs.
 */
class GeneratedDesigns(val names: Set<String>) {

    /** True when a generator makes [design]. It is matched by `metadata.filename`, so a copy under another zip name is still known. */
    fun isGenerated(design: JsonObject): Boolean = ((design["metadata"] as? JsonObject)?.get("filename") as? JsonPrimitive)?.content in names

    companion object {

        /** The text `open` and `save` print for such a design. */
        const val WARNING = "This design is made by a generator in aaps-watchfaces; a new build of the designs overwrites this zip. Change the generator to keep a change."

        /** Reads the list from text in the form of `generated.json`. */
        fun parse(text: String): GeneratedDesigns =
            GeneratedDesigns((AndroidJson.parseObject(text, "generated.json")["designs"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }.toSet())

        /** The list that comes with the editor: the resource `generated.json`. */
        fun standard(): GeneratedDesigns =
            parse(GeneratedDesigns::class.java.getResourceAsStream("generated.json")!!.use { it.readBytes().decodeToString() })
    }
}
