package app.aaps.facetool.editor.server

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

/**
 * A switch of the metadata (`key_show_...`) may be a JSON boolean or the text of older designs.
 * The engine reads both forms the same, and the editor keeps the form it is given.
 */
class SwitchFormTest : ServerTestBase() {

    private fun picture(project: File): String = sha256(File(text(run("render", emptyMap(), project), "file")).readBytes())

    private fun findings(project: File): JsonElement = answer("lint", mapOf("no-wff" to JsonPrimitive(true)), project).json.getValue("findings")

    private fun set(project: File, key: String, value: JsonPrimitive) = run("set", mapOf("path" to JsonPrimitive("metadata.$key"), "value" to value), project)

    private fun metadata(project: File): JsonObject = run("show", mapOf("path" to JsonPrimitive("metadata")), project).getValue("value") as JsonObject

    @Test
    fun aBooleanSwitchShowsTheSameFaceAsTheText() {
        // D2 has its switches as text; slot 1 is on
        val project = open("D2")
        assertThat(metadata(project)["key_show_complication1"]).isEqualTo(JsonPrimitive("true"))
        val withText = picture(project)
        val lint = findings(project)

        set(project, "key_show_complication1", JsonPrimitive(false))
        assertThat(metadata(project)["key_show_complication1"]).isEqualTo(JsonPrimitive(false))
        val off = picture(project)
        assertThat(off).isNotEqualTo(withText)
        set(project, "key_show_complication1", JsonPrimitive("false"))
        assertThat(picture(project)).isEqualTo(off)

        set(project, "key_show_complication1", JsonPrimitive(true))
        assertThat(metadata(project)["key_show_complication1"]).isEqualTo(JsonPrimitive(true))
        assertThat(picture(project)).isEqualTo(withText)

        // Every switch of the design as a boolean: the same face, and the lint says nothing new
        val switches = metadata(project).filter { (key, value) -> key.startsWith("key_show_") && value is JsonPrimitive && value.isString }
        assertThat(switches.size).isGreaterThan(5)
        switches.forEach { (key, value) -> set(project, key, JsonPrimitive((value as JsonPrimitive).content == "true")) }
        assertThat(metadata(project).filter { it.key.startsWith("key_show_") }.values.all { it is JsonPrimitive && !it.isString }).isTrue()
        assertThat(picture(project)).isEqualTo(withText)
        assertThat(findings(project) as JsonArray).isEqualTo(lint)
    }
}
