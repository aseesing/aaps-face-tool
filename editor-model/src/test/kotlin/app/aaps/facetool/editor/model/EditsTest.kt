package app.aaps.facetool.editor.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

/** Test 12.4 of the design: each edit operation and `apply` on a small design, and undo and redo give the same bytes back. */
class EditsTest {

    private val test = TestProjects()
    private val dir = test.open()

    private fun at(path: String): JsonElement? = JsonPaths.get(ProjectFolder(dir).design(), JsonPaths.parse(path))

    private fun layers(): List<String> = (test.ok(dir, "list", "layers")["items"] as List<*>).map { (it as JsonElement).let { item -> AndroidJson.parseObject(item.compact(), "item").text("summary") } }

    /** Runs the command, then checks that undo gives the bytes of before and redo the bytes of after. */
    private fun edit(vararg words: String) {
        val before = test.files(dir)
        val order = ProjectFolder(dir).order()
        val answer = test.ok(dir, *words)
        assertWithMessage(words.joinToString(" ")).that(answer["changed"]).isEqualTo(JsonPrimitive(true))
        val after = test.files(dir)
        val orderAfter = ProjectFolder(dir).order()
        assertThat(after).isNotEqualTo(before)
        assertThat(test.ok(dir, "undo")["done"]).isEqualTo(JsonPrimitive(true))
        assertWithMessage("undo of ${words.joinToString(" ")}").that(test.files(dir)).isEqualTo(before)
        assertThat(ProjectFolder(dir).order()).isEqualTo(order)
        assertThat(test.ok(dir, "redo")["done"]).isEqualTo(JsonPrimitive(true))
        assertWithMessage("redo of ${words.joinToString(" ")}").that(test.files(dir)).isEqualTo(after)
        assertThat(ProjectFolder(dir).order()).isEqualTo(orderAfter)
    }

    @Test
    fun `set changes a value, keeps the place of the key and the form of the file`() {
        edit("set", "sgv.width", "151")
        assertThat(File(dir, "CustomWatchface.json").readText()).isEqualTo(test.smallDesign.replace("\"width\": 150", "\"width\": 151"))
        edit("set", "sgv.fontColor", "--text", "#FF0000")
        assertThat(at("sgv.fontColor")).isEqualTo(JsonPrimitive("#FF0000"))
        edit("set", "sgv.rect", """{"left": 0.50, "top": 1}""")
        assertThat(at("sgv")!!.compact()).isEqualTo("""{"width":151,"visibility":"visible","font":"Face","fontColor":"#FF0000","rect":{"left":0.50,"top":1}}""")
        edit("set", "animation.layers[1].count", "120")
        edit("set", "animation.layers[4]", """{"view": "time"}""")
        assertThat(layers()).containsExactly("image Star", "shape circle x120", "text clock()", "view sgv", "view time").inOrder()
        edit("set", "looks[0].when", "\"v_sgv > 180\"")
        assertThat(at("looks")!!.compact()).isEqualTo("""[{"when":"v_sgv > 180"}]""")
        edit("set", "animation.functions[\"bg()\"]", "\"glucose(v_sgv)\"")
        assertThat(at("animation.functions[\"bg()\"]")).isEqualTo(JsonPrimitive("glucose(v_sgv)"))
    }

    @Test
    fun `set keeps a switch of the metadata as a JSON boolean or as a text`() {
        // true and false are JSON: the switch is a boolean in the file, as the engine reads it
        edit("set", "metadata.key_show_complication1", "true")
        assertThat(at("metadata.key_show_complication1")).isEqualTo(JsonPrimitive(true))
        assertThat(File(dir, "CustomWatchface.json").readText()).contains("\"key_show_complication1\": true\n")
        edit("set", "metadata.key_show_complication1", "false")
        assertThat(at("metadata.key_show_complication1")).isEqualTo(JsonPrimitive(false))
        // The text form of older designs stays a text
        edit("set", "metadata.key_show_date", "\"true\"")
        assertThat(at("metadata.key_show_date")).isEqualTo(JsonPrimitive("true"))
        assertThat(File(dir, "CustomWatchface.json").readText()).contains("\"key_show_date\": \"true\"\n")
    }

    @Test
    fun `set refuses a bad path, a bad value and an index too far`() {
        assertThat(test.run(dir, "set", "animation.layers[9].x", "1").exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(test.run(dir, "set", "animation.layers[", "1").exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(test.run(dir, "set", "metadata.name.x", "1").exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(test.run(dir, "set", "sgv.width", "wide").exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(test.ok(dir, "history").text("text")).isEqualTo("No changes yet.")
        // The same value again changes nothing and adds no entry
        assertThat(test.ok(dir, "set", "sgv.width", "150")["changed"]).isEqualTo(JsonPrimitive(false))
    }

    @Test
    fun `unset removes a key or a list entry`() {
        edit("unset", "sgv.font")
        assertThat(at("sgv")!!.compact()).isEqualTo("""{"width":150,"visibility":"visible","fontColor":"#4FD98A"}""")
        edit("unset", "animation.layers[0]")
        assertThat(layers()).containsExactly("shape circle x12", "text clock()", "view sgv").inOrder()
        assertThat(test.run(dir, "unset", "sgv.nothing").exitCode).isEqualTo(ExitCodes.WRONG_USE)
    }

    @Test
    fun `apply does a list of edits in one step`() {
        val patch = File(test.root, "patch.json").apply {
            writeText(
                """
                [
                    {"op": "set", "path": "animation.durationMs", "value": 9000},
                    {"op": "unset", "path": "sgv.fontColor"},
                    {"op": "insert", "path": "animation.layers[1]", "value": {"use": "rain"}},
                    {"op": "insert", "path": "animation.fps", "value": 24},
                    {"op": "move", "from": "animation.layers[4]", "to": "animation.layers[0]"}
                ]
                """
            )
        }
        edit("apply", patch.path)
        assertThat(layers()).containsExactly("view sgv", "image Star", "use rain", "shape circle x12", "text clock()").inOrder()
        assertThat(at("animation.durationMs")).isEqualTo(JsonPrimitive(9000))
        assertThat(at("animation.fps")).isEqualTo(JsonPrimitive(24))
        assertThat(at("sgv.fontColor")).isNull()
        // One step: one history entry
        assertThat(test.ok(dir, "history")["position"]).isEqualTo(JsonPrimitive(1))
    }

    @Test
    fun `apply is all or none and reads standard input`() {
        val before = test.files(dir)
        test.stdin = """[{"op": "set", "path": "sgv.width", "value": 1}, {"op": "unset", "path": "sgv.nothing"}]"""
        val failed = test.run(dir, "apply", "-")
        assertThat(failed.exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(failed.text).contains("Edit 1 of the patch")
        assertThat(test.files(dir)).isEqualTo(before)
        test.stdin = """[{"op": "set", "path": "sgv.width", "value": 1}]"""
        edit("apply", "-")
        assertThat(at("sgv.width")).isEqualTo(JsonPrimitive(1))
        test.stdin = """{"op": "set"}"""
        assertThat(test.run(dir, "apply", "-").exitCode).isEqualTo(ExitCodes.WRONG_USE)
        test.stdin = """[{"op": "paint", "path": "a"}]"""
        assertThat(test.run(dir, "apply", "-").exitCode).isEqualTo(ExitCodes.WRONG_USE)
    }

    @Test
    fun `layer add, move, copy and remove`() {
        edit("layer", "add", """{"use": "rain"}""")
        assertThat(layers()).containsExactly("image Star", "shape circle x12", "text clock()", "view sgv", "use rain").inOrder()
        edit("layer", "add", """{"shape": "rect"}""", "--at", "0")
        assertThat(layers()).containsExactly("shape rect", "image Star", "shape circle x12", "text clock()", "view sgv", "use rain").inOrder()
        edit("layer", "move", "5", "1")
        assertThat(layers()).containsExactly("shape rect", "use rain", "image Star", "shape circle x12", "text clock()", "view sgv").inOrder()
        edit("layer", "move", "0", "5")
        assertThat(layers()).containsExactly("use rain", "image Star", "shape circle x12", "text clock()", "view sgv", "shape rect").inOrder()
        edit("layer", "copy", "1")
        assertThat(layers()).containsExactly("use rain", "image Star", "image Star", "shape circle x12", "text clock()", "view sgv", "shape rect").inOrder()
        edit("layer", "copy", "0", "--at", "7")
        assertThat(layers().last()).isEqualTo("use rain")
        edit("layer", "remove", "2")
        edit("layer", "remove", "6")
        assertThat(layers()).containsExactly("use rain", "image Star", "shape circle x12", "text clock()", "view sgv", "shape rect").inOrder()
        assertThat(test.run(dir, "layer", "remove", "6").exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(test.run(dir, "layer", "add", "5").exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(test.run(dir, "layer", "move", "0", "9").exitCode).isEqualTo(ExitCodes.WRONG_USE)
    }

    @Test
    fun `layer add makes the layer list of a design without one`() {
        val plain = test.open(test.zip("Plain.zip", """{"metadata": {"name": "Plain", "filename": "Plain"}}"""))
        test.ok(plain, "layer", "add", """{"shape": "circle"}""")
        assertThat(ProjectFolder(plain).design().compact()).isEqualTo("""{"metadata":{"name":"Plain","filename":"Plain"},"animation":{"layers":[{"shape":"circle"}]}}""")
    }

    @Test
    fun `res add, replace, remove and rename`() {
        val moon = File(test.root, "Moon.png").apply { writeBytes(test.png(10, 10)) }
        edit("res", "add", moon.path)
        assertThat(File(dir, "Moon.png").isFile).isTrue()
        edit("res", "add", moon.path, "--name", "Sun")
        assertThat(test.run(dir, "res", "add", moon.path).text).contains("is there already")
        assertThat(test.run(dir, "res", "add", test.zip("Second.zip").path).text).contains("never takes parts from a second zip")
        val hidden = File(test.root, "Hidden.png").apply { writeBytes(test.zip("Third.zip").readBytes()) }
        assertThat(test.run(dir, "res", "add", hidden.path).exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(test.run(dir, "res", "add", File(dir, "LICENCE.txt").path).exitCode).isEqualTo(ExitCodes.WRONG_USE)

        val jpg = File(test.root, "New.jpg").apply { writeBytes(ByteArray(20) { 7 }) }
        edit("res", "replace", "Star", jpg.path)
        assertThat(File(dir, "Star.png").exists()).isFalse()
        assertThat(File(dir, "Star.jpg").readBytes().contentEquals(jpg.readBytes())).isTrue()
        // The new file keeps the place of the old one in the zip
        assertThat(DesignZip.read(ProjectFolder(dir).zipBytes()).map { it.name }).containsExactly("CustomWatchface.json", "Star.jpg", "Face.ttf", "LICENCE.txt", "Moon.png", "Sun.png").inOrder()

        edit("res", "rename", "Face", "Letters")
        assertThat(File(dir, "Letters.ttf").isFile).isTrue()
        assertThat(at("sgv.font")).isEqualTo(JsonPrimitive("Letters"))
        assertThat(at("animation.layers[2].font")).isEqualTo(JsonPrimitive("Letters"))
        assertThat(DesignZip.read(ProjectFolder(dir).zipBytes()).map { it.name }).containsExactly("CustomWatchface.json", "Star.jpg", "Letters.ttf", "LICENCE.txt", "Moon.png", "Sun.png").inOrder()
        assertThat(test.run(dir, "res", "rename", "Letters", "Moon").exitCode).isEqualTo(ExitCodes.WRONG_USE)

        edit("res", "remove", "Sun")
        assertThat(test.ok(dir, "res", "remove", "Star.jpg").text("text")).contains("The JSON still names it at: animation.layers[0].image")
        assertThat(test.run(dir, "res", "remove", "Star").exitCode).isEqualTo(ExitCodes.WRONG_USE)
    }

    @Test
    fun `rename lists other texts with the old name and does not change them`() {
        test.ok(dir, "set", "metadata.comment", "\"Star\"")
        val answer = test.ok(dir, "res", "rename", "Star", "Sparkle")
        assertThat(answer["uses"]).isEqualTo(JsonPrimitive(1))
        assertThat(answer["otherTexts"]!!.compact()).isEqualTo("""["metadata.comment"]""")
        assertThat(at("metadata.comment")).isEqualTo(JsonPrimitive("Star"))
        assertThat(at("animation.layers[0].image")).isEqualTo(JsonPrimitive("Sparkle"))
    }

    @Test
    fun `undo walks back to the design at open and redo forward again`() {
        val atOpen = test.files(dir)
        test.ok(dir, "set", "sgv.width", "1")
        test.ok(dir, "layer", "remove", "0")
        test.ok(dir, "res", "rename", "Face", "Letters")
        test.ok(dir, "save", "--out", File(test.root, "mid.zip").path)
        test.ok(dir, "res", "remove", "Star")
        val atEnd = test.files(dir)
        assertThat(test.ok(dir, "history").text("text").lines().map { it.substringAfterLast("  ") }).containsExactly(
            "facetool set sgv.width 1", "facetool layer remove 0", "facetool res rename Face Letters", "facetool save --out ${File(test.root, "mid.zip").path}", "facetool res remove Star"
        ).inOrder()
        repeat(4) { assertThat(test.ok(dir, "undo")["done"]).isEqualTo(JsonPrimitive(true)) }
        assertThat(test.ok(dir, "undo")["done"]).isEqualTo(JsonPrimitive(false))
        assertThat(test.files(dir)).isEqualTo(atOpen)
        assertThat(test.ok(dir, "info")["changedSinceOpen"]).isEqualTo(JsonPrimitive(false))
        repeat(4) { assertThat(test.ok(dir, "redo")["done"]).isEqualTo(JsonPrimitive(true)) }
        assertThat(test.ok(dir, "redo")["done"]).isEqualTo(JsonPrimitive(false))
        assertThat(test.files(dir)).isEqualTo(atEnd)

        // A new change after an undo drops what could be done again
        test.ok(dir, "undo")
        test.ok(dir, "set", "sgv.width", "2")
        assertThat(test.ok(dir, "redo")["done"]).isEqualTo(JsonPrimitive(false))
        assertThat(test.ok(dir, "history")["position"]).isEqualTo(JsonPrimitive(5))
    }

    @Test
    fun `a change from outside gets its own entry and undo takes it back`() {
        val atOpen = test.files(dir)
        File(dir, "CustomWatchface.json").writeText(test.smallDesign.replace("6000", "7000"))
        File(dir, "Star.png").writeBytes(test.png(9, 9))
        File(dir, "Extra.png").writeBytes(test.png(2, 2))
        val outside = test.files(dir)
        val answer = test.ok(dir, "set", "sgv.width", "1")
        assertThat(answer.text("text")).contains("changed outside the editor")
        assertThat(test.ok(dir, "history").text("text").lines().map { it.substringAfterLast("  ") }).containsExactly("changed outside the editor", "facetool set sgv.width 1").inOrder()
        test.ok(dir, "undo")
        assertThat(test.files(dir)).isEqualTo(outside)
        test.ok(dir, "undo")
        assertThat(test.files(dir)).isEqualTo(atOpen)
        assertThat(File(dir, "Extra.png").exists()).isFalse()

        // An outside change with no command after it: undo takes it back too
        test.ok(dir, "redo")
        test.ok(dir, "redo")
        File(dir, "CustomWatchface.json").writeText(test.smallDesign)
        test.ok(dir, "undo")
        assertThat(at("sgv.width")).isEqualTo(JsonPrimitive(1))
    }

    @Test
    fun `show and the lists read the design`() {
        assertThat(test.ok(dir, "show", "metadata.created_at").text("text")).isEqualTo("\"21\\/11\\/26\"")
        assertThat(test.ok(dir, "show", "animation.layers[0]").text("text")).isEqualTo("{\n    \"image\": \"Star\",\n    \"x\": 0.10\n}")
        assertThat(test.ok(dir, "show").text("text")).isEqualTo(test.smallDesign)
        assertThat(test.run(dir, "show", "nothing.here").exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(test.ok(dir, "list", "layers").text("text")).isEqualTo("L0    image Star\nL1    shape circle x12\nL2    text clock()\nL3    view sgv")
        assertThat(test.ok(dir, "list", "views").text("text")).isEqualTo("sgv  visible font Face")
        assertThat(test.ok(dir, "list", "fonts").text("text")).isEqualTo("Face  file Face.ttf, named 2 times")
        assertThat(test.ok(dir, "list", "resources").text("text")).isEqualTo(
            "Face.ttf  font, 64 B, named 2 times\nLICENCE.txt  other, 11 B, kept\nStar.png  picture, ${File(dir, "Star.png").length()} B, 4x3, named 1 times"
        )
        assertThat(test.ok(dir, "list", "templates").text("text")).isEqualTo("no templates")
        assertThat(test.run(dir, "list", "things").exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(test.run(null, "show").exitCode).isEqualTo(ExitCodes.WRONG_USE)
    }
}
