package app.aaps.facetool.editor.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

/**
 * The server draws the same picture of a design whatever it drew before: the hashes of the rest
 * picture and one wake picture of each of the 12 designs are a golden file, and the designs are drawn
 * once in their order and once in the reverse order, each in a test JVM of its own. A design that
 * draws with something another design left behind (a font, a setting) fails here.
 */
abstract class RenderGoldenBase(private val reverse: Boolean) : ServerTestBase() {

    @Test
    fun picturesDoNotDependOnTheDesignsBefore() {
        val lines = HashMap<String, String>()
        for (design in if (reverse) DESIGNS.reversed() else DESIGNS) {
            val project = open(design)
            val rest = File(text(run("render", emptyMap(), project), "file")).readBytes()
            val wake = File(text(run("render", mapOf("set" to JsonArray(listOf(JsonPrimitive("wake=2.5")))), project), "file")).readBytes()
            lines[design] = "$design rest ${sha256(rest)}\n$design wake2.5 ${sha256(wake)}\n"
        }
        GoldenFile("render/designs.txt").check(DESIGNS.joinToString("") { lines.getValue(it) }, mayWrite = !reverse)
    }
}

class RenderGoldenTest : RenderGoldenBase(reverse = false)
class RenderGoldenReverseTest : RenderGoldenBase(reverse = true)
