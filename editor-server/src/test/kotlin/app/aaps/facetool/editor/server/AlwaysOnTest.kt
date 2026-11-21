package app.aaps.facetool.editor.server

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

/**
 * The scene key `ambient` in the engine preview: `render`, `wake` and `snapshot` draw the always-on
 * face of the engine (`CwfPreviewRenderer.ambient`). That the picture is the picture of a new face is
 * checked by [RenderIdentityBase].
 */
class AlwaysOnTest : ServerTestBase() {

    private fun sets(vararg pairs: String): Map<String, JsonElement> = mapOf("set" to JsonArray(pairs.map { JsonPrimitive(it) }))

    private fun warnings(answer: JsonObject): List<String> = (answer.getValue("warnings") as JsonArray).map { (it as JsonPrimitive).content }

    @Test
    fun renderDrawsTheAlwaysOnFace() {
        val project = open("Aurora_Town")
        fun picture(name: String, vararg pairs: String): Pair<ByteArray, JsonObject> {
            val file = File(temp, "$name.png")
            val answer = run("render", sets(*pairs) + ("out" to JsonPrimitive(file.path)), project)
            return file.readBytes() to answer
        }

        val (rest, restAnswer) = picture("rest")
        val (ambient, ambientAnswer) = picture("ambient", "ambient=true")
        assertThat(ambient.contentEquals(rest)).isFalse()
        assertThat(text(ambientAnswer, "text")).contains("always-on")
        assertThat(text(restAnswer, "text")).contains("at rest")
        assertThat(warnings(ambientAnswer)).isEmpty()

        // Always-on has no wake: a moment of the wake is the always-on face, and the answer says so
        val (ambientWake, wakeAnswer) = picture("ambient-wake", "ambient=true", "wake=2.5")
        assertThat(ambientWake.contentEquals(ambient)).isTrue()
        assertThat(warnings(wakeAnswer).single()).contains("always-on has no wake animation")

        // A wake frame of a design with text layers has no warning any more (AAPS 95741bc6c0)
        val (wake, plainWake) = picture("wake", "wake=2.5")
        assertThat(wake.contentEquals(ambient)).isFalse()
        assertThat(warnings(plainWake)).isEmpty()

        // After always-on the face at rest is the same picture as before it
        assertThat(picture("rest-again").first.contentEquals(rest)).isTrue()
    }

    @Test
    fun wakeAndSnapshotKnowAlwaysOn() {
        val project = open("Aurora_Town")
        val wake = run("wake", mapOf("fps" to JsonPrimitive(1), "from" to JsonPrimitive(0), "to" to JsonPrimitive(2)), project)
        assertThat(warnings(wake)).isEmpty()
        val ambientWake = run("wake", mapOf("fps" to JsonPrimitive(1), "from" to JsonPrimitive(0), "to" to JsonPrimitive(2)) + sets("ambient=true"), project)
        assertThat(warnings(ambientWake).single()).contains("always-on has no wake animation")

        val snapshot = run("snapshot", mapOf("name" to JsonPrimitive("moments")), project)
        val files = (snapshot.getValue("files") as JsonArray).associate { text(it as JsonObject, "moment") to File(text(it, "file")) }
        assertThat(files.keys).containsExactly("rest", "wake-1", "wake-2", "wake-3", "night", "always-on").inOrder()
        val ambient = File(temp, "ambient.png")
        run("render", sets("ambient=true") + ("out" to JsonPrimitive(ambient.path)), project)
        assertThat(files.getValue("always-on").readBytes().contentEquals(ambient.readBytes())).isTrue()
    }
}
