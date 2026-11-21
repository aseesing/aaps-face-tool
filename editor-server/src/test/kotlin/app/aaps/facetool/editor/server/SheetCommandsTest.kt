package app.aaps.facetool.editor.server

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

/**
 * The picture commands of step 6 on two designs: `wake`, `options`, `looks`, `pick`, `snapshot` and
 * `diff --render`. Their pictures are checked by hash against a golden file per design, their texts
 * are in the same file.
 *
 * @param editPath a place in the design where the value [editValue] changes the rest picture, for the diff
 */
abstract class SheetCommandsBase(private val design: String, private val editPath: String, private val editValue: JsonElement) : ServerTestBase() {

    @Test
    fun picturesAndTexts() {
        val project = open(design)
        val lines = StringBuilder()
        fun hash(path: String) = sha256(File(path).readBytes())

        val wake = run("wake", mapOf("fps" to JsonPrimitive(2), "from" to JsonPrimitive(0), "to" to JsonPrimitive(3)), project)
        val frames = (wake.getValue("frames") as JsonArray).map { (it as JsonPrimitive).content }
        assertThat(frames).hasSize(7)
        lines.append("wake sheet ${hash(text(wake, "file"))}\n")
        frames.forEachIndexed { i, frame -> lines.append("wake frame $i ${hash(frame)}\n") }
        val options = run("options", emptyMap(), project)
        lines.append("options ${options["file"]?.let { hash((it as JsonPrimitive).content) } ?: "none"}\n")
        lines.append(text(options, "text").replace(temp.path, "<temp>")).append('\n')

        val looks = run("looks", emptyMap(), project)
        lines.append("looks ${looks["file"]?.let { hash((it as JsonPrimitive).content) } ?: "none"}\n")
        lines.append(text(looks, "text").replace(temp.path, "<temp>")).append('\n')

        for ((x, y) in listOf(200 to 100, 200 to 200, 5 to 5)) {
            val pick = run("pick", mapOf("x" to JsonPrimitive(x), "y" to JsonPrimitive(y)), project)
            lines.append("pick $x $y\n").append(text(pick, "text")).append('\n')
        }

        val snapshot = run("snapshot", mapOf("name" to JsonPrimitive("before")), project)
        for (entry in snapshot.getValue("files") as JsonArray) {
            entry as JsonObject
            lines.append("snapshot ${text(entry, "moment")} ${hash(text(entry, "file"))}\n")
        }

        // The same design twice: no pixel differs. After an edit: some do.
        val same = run("diff", mapOf("a" to JsonPrimitive(zip(design).path), "b" to JsonPrimitive(project.path), "render" to JsonPrimitive(true)), project)
        assertThat((same.getValue("changedPercent") as JsonPrimitive).content.toDouble()).isEqualTo(0.0)
        run("set", mapOf("path" to JsonPrimitive(editPath), "value" to editValue), project)
        val changed = run("diff", mapOf("a" to JsonPrimitive(zip(design).path), "b" to JsonPrimitive(project.path), "render" to JsonPrimitive(true)), project)
        assertThat((changed.getValue("changedPercent") as JsonPrimitive).content.toDouble()).isGreaterThan(0.0)
        lines.append("diff ${hash(text(changed, "file"))}\n")
        lines.append(text(changed, "text").replace(temp.path, "<temp>").replace(zip(design).path, "<zip>")).append('\n')

        GoldenFile("sheets/$design.txt").check(lines.toString())
    }
}

class SheetCommandsAuroraTest : SheetCommandsBase("Aurora_Town", "animation.layers", JsonArray(emptyList()))
class SheetCommandsD2Test : SheetCommandsBase("D2", "midColor", JsonPrimitive("#FF00FF"))
