package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.wrongUse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File

/** Reads the arguments of a command (design 5.2: one JSON object, argument name -> value). */
class Args(val json: JsonObject) {

    fun text(name: String): String? = (json[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    fun flag(name: String): Boolean = (json[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.content == "true") } ?: false

    fun number(name: String): Double? = text(name)?.let { it.toDoubleOrNull() ?: (json[name] as JsonPrimitive).doubleOrNull ?: wrongUse("--$name needs a number") }

    fun int(name: String): Int? = number(name)?.toInt()

    fun file(name: String): File? = text(name)?.let { File(it) }
}
