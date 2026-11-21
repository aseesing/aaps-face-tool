package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.imageio.ImageIO

/** A clock for tests: it stands still until [plus] moves it. */
class TestClock(private var now: Instant = Instant.parse("2026-11-21T10:00:00Z")) : Clock() {

    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now

    fun plus(ms: Long) {
        now = now.plusMillis(ms)
    }
}

/** Helpers for the tests: a temp folder, a small design zip, and commands as command lines. */
class TestProjects {

    val root: File = Files.createTempDirectory("facetool-test").toFile()
    val clock = TestClock()
    val config = EditorConfig(editingRoot = File(root, "editing"), generated = GeneratedDesigns(setOf("Made_By_Generator")))
    val table: CommandTable = CommandTable.standard()
    var stdin: String = ""
    val commands = LocalCommands(config, clock, table, stdin = { stdin })

    /** A small design in the JSON form of the zips. */
    val smallDesign: String = """
        {
            "metadata": {
                "name": "Small",
                "filename": "Small",
                "created_at": "21\/11\/26"
            },
            "sgv": {
                "width": 150,
                "visibility": "visible",
                "font": "Face",
                "fontColor": "#4FD98A"
            },
            "animation": {
                "durationMs": 6000,
                "layers": [
                    {
                        "image": "Star",
                        "x": 0.10
                    },
                    {
                        "shape": "circle",
                        "count": 12
                    },
                    {
                        "text": "clock()",
                        "font": "Face"
                    },
                    {
                        "view": "sgv"
                    }
                ]
            }
        }
    """.trimIndent()

    fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out)
        return out.toByteArray()
    }

    /** Writes a zip with [json] and the small resources: `Star.png`, `Face.ttf`, `LICENCE.txt`. */
    fun zip(name: String = "Small.zip", json: String = smallDesign, more: Map<String, ByteArray> = emptyMap()): File {
        val time = LocalDateTime.of(2026, 11, 19, 16, 33, 0)
        val entries = listOf(
            ZipFileEntry(ResourceNames.DESIGN_JSON, json.toByteArray(), time),
            ZipFileEntry("Star.png", png(4, 3), time),
            ZipFileEntry("Face.ttf", ByteArray(64) { it.toByte() }, time),
            ZipFileEntry("LICENCE.txt", "Free to use".toByteArray(), time)
        ) + more.map { ZipFileEntry(it.key, it.value, time) }
        return File(root, name).apply { writeBytes(DesignZip.write(entries, time)) }
    }

    /** Opens the small design and gives its project folder. */
    fun open(zip: File = zip()): File {
        val answer = run(null, "open", zip.path)
        check(answer.exitCode == 0) { answer.text }
        return File(answer.json.text("dir"))
    }

    /** Runs a command given as words, as the CLI does: parse with the table, then [LocalCommands]. */
    fun run(project: File?, vararg words: String): CommandAnswer {
        val parsed = try {
            ArgParser(table, root).parse(words.toList())
        } catch (e: FacetoolException) {
            return CommandAnswer(JsonObject(mapOf("error" to JsonPrimitive(e.message), "text" to JsonPrimitive(e.message))), e.exitCode)
        }
        return commands.run(parsed.spec.name, parsed.args, parsed.project ?: project)
    }

    /** Runs a command that must work, and gives its answer. */
    fun ok(project: File?, vararg words: String): JsonObject {
        val answer = run(project, *words)
        check(answer.exitCode == 0) { "${words.joinToString(" ")}: ${answer.text}" }
        return answer.json
    }

    /** Every file of a folder but `.facetool`: name to hash. */
    fun files(dir: File): Map<String, String> = ProjectFolder(dir).let { mapOf(ResourceNames.DESIGN_JSON to Hashes.sha256(it.designText().toByteArray())) + it.resources().mapValues { e -> Hashes.sha256(e.value) } }
}

val CommandAnswer.text: String get() = json.text("text")

fun JsonObject.text(key: String): String = (this[key] as JsonPrimitive).content

fun JsonElement.compact(): String = AndroidJson.compact(this)
