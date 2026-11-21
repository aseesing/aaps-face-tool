package app.aaps.facetool.editor.server

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

/**
 * `render --batch`: many pictures in one call from the one kept face. Each picture must be the PNG that
 * a single `render` with the same scene writes, byte for byte.
 */
class RenderBatchTest : ServerTestBase() {

    /** The scenes of the test: the keys in the batch file, and the same scene as `--set` pairs of a single render. */
    private val scenes = listOf(
        """{}""" to listOf(),
        """{"sgv": 250, "delta": 6.0, "direction": "↑", "out": "high.png"}""" to listOf("sgv=250", "delta=6.0", "direction=↑"),
        """{"wake": 2.5, "out": "wake"}""" to listOf("wake=2.5"),
        """{"options": {"moon": 1}}""" to listOf("options.moon=1"),
        """{"options.moon": 2, "sgv": 58}""" to listOf("options.moon=2", "sgv=58"),
        """{"time": "2026-12-25T18:30:00", "weather": "rain", "out": "sub/christmas.png"}""" to listOf("time=2026-12-25T18:30:00", "weather=rain"),
        // The first scene again, after the others: a picture does not depend on the pictures before it
        """{"out": "again.png"}""" to listOf()
    )

    private fun files(answer: JsonObject): List<File> = (answer.getValue("files") as JsonArray).map { File(text(it as JsonObject, "file")) }

    @Test
    fun aBatchGivesThePixelsOfSingleRenders() {
        val project = open("Aurora_Town")
        val batch = File(temp, "scenes.json").apply { writeText(scenes.joinToString(",\n", "[", "]") { it.first }) }
        val dir = File(temp, "pictures")
        val answer = run("render", mapOf("batch" to JsonPrimitive(batch.path), "out" to JsonPrimitive(dir.path)), project)
        val files = files(answer)
        assertThat(files.map { it.relativeTo(dir).path }).containsExactly("000.png", "high.png", "wake.png", "003.png", "004.png", "sub/christmas.png", "again.png").inOrder()
        assertThat((answer.getValue("seconds") as JsonPrimitive).content.toDouble()).isGreaterThan(0.0)
        assertThat(text(answer, "text")).contains("7 pictures in ")
        assertThat(text(answer, "dir")).isEqualTo(dir.path)

        scenes.forEachIndexed { i, (_, sets) ->
            val single = File(temp, "single/$i.png")
            run("render", mapOf<String, JsonElement>("set" to JsonArray(sets.map { JsonPrimitive(it) }), "out" to JsonPrimitive(single.path)), project)
            assertWithMessage("picture %s of the batch (%s) is not the picture of a single render", i, sets).that(files[i].readBytes().contentEquals(single.readBytes())).isTrue()
        }
        // The scenes are not all the same picture: the default, the high glucose, the wake and Christmas differ.
        // The high glucose shows in a text layer of Aurora Town: a face that was fed a second time kept the
        // first glucose there, so each new set of values now gets a new face (design section 4.3).
        assertThat(listOf(0, 1, 2, 5).map { sha256(files[it].readBytes()) }.toSet()).hasSize(4)
        assertThat(files[6].readBytes().contentEquals(files[0].readBytes())).isTrue()

        // The flags and the scene of the command hold for every picture
        val flags = run("render", mapOf("batch" to JsonPrimitive(batch.path), "out" to JsonPrimitive(File(temp, "grid").path), "grid" to JsonPrimitive(true), "set" to JsonArray(listOf(JsonPrimitive("size=200")))), project)
        val single = File(temp, "single/grid.png")
        run("render", mapOf<String, JsonElement>("set" to JsonArray(listOf(JsonPrimitive("size=200"), JsonPrimitive("sgv=250"), JsonPrimitive("delta=6.0"), JsonPrimitive("direction=↑"))), "grid" to JsonPrimitive(true), "out" to JsonPrimitive(single.path)), project)
        assertThat(files(flags)[1].readBytes().contentEquals(single.readBytes())).isTrue()
        // Without --out the pictures go to the out folder of the project
        val default = run("render", mapOf("batch" to JsonPrimitive(batch.path)), project)
        assertThat(files(default)[0].path).isEqualTo(File(project, ".facetool/out/render/batch/000.png").path)
    }

    @Test
    fun aWrongBatchFileDrawsNothing() {
        val project = open("D2")
        val dir = File(temp, "pictures")
        fun refused(content: String?): String {
            val batch = File(temp, "batch.json")
            if (content == null) batch.delete() else batch.writeText(content)
            val answer = answer("render", mapOf("batch" to JsonPrimitive(batch.path), "out" to JsonPrimitive(dir.path)), project)
            assertWithMessage("%s", content).that(answer.exitCode).isEqualTo(2)
            assertThat(dir.exists()).isFalse()
            return text(answer.json, "text")
        }
        assertThat(refused(null)).contains("is missing")
        assertThat(refused("""{"sgv": 250}""")).contains("JSON list of scenes")
        assertThat(refused("[]")).contains("no scene")
        assertThat(refused("""[{"sgv": 250}, 5]""")).contains("Entry 1")
        assertThat(refused("""[{"sgv": 250}, {"glucose": 5}]""")).contains("'glucose' is no scene key")
        assertThat(refused("""[{"sgv": 250}, {"sgv": "high"}]""")).contains("needs a number")
        assertThat(refused("""[{"out": "a.png"}, {"sgv": 250, "out": "a"}]""")).contains("the same picture")
    }
}
