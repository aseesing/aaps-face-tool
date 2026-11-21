package app.aaps.facetool.editor.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class ProjectFolderTest {

    private val test = TestProjects()

    @Test
    fun `open makes exactly the files of the zip and the tool folder`() {
        val dir = test.open()
        assertThat(dir).isEqualTo(File(test.config.editingRoot, "Small").absoluteFile)
        assertThat(dir.list()!!.toList()).containsExactly("CustomWatchface.json", "Star.png", "Face.ttf", "LICENCE.txt", ".facetool")
        assertThat(File(dir, "CustomWatchface.json").readText()).isEqualTo(test.smallDesign)
        assertThat(File(dir, ".facetool/project.json").isFile).isTrue()
        assertThat(ProjectFolder.find(File(dir, ".facetool/out"))!!.dir).isEqualTo(dir)
        assertThat(ProjectFolder.find(test.root)).isNull()
    }

    @Test
    fun `open again of the same zip is fine, another zip is refused`() {
        val zip = test.zip()
        val dir = test.open(zip)
        val again = test.run(null, "open", zip.path)
        assertThat(again.exitCode).isEqualTo(0)
        assertThat(again.json["alreadyOpen"]).isEqualTo(JsonPrimitive(true))

        val other = test.zip("Other.zip", test.smallDesign.replace("Small", "Other"))
        val refused = test.run(null, "open", other.path, "--dir", dir.path)
        assertThat(refused.exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(refused.text).contains("holds another design")
        // Nothing of the second zip came into the folder
        assertThat(File(dir, "CustomWatchface.json").readText()).isEqualTo(test.smallDesign)

        val plain = File(test.root, "plain").apply { mkdirs() }
        File(plain, "note.txt").writeText("x")
        assertThat(test.run(null, "open", zip.path, "--dir", plain.path).exitCode).isEqualTo(ExitCodes.WRONG_USE)
    }

    @Test
    fun `a zip without a design or with bad JSON does not read`() {
        val noDesign = File(test.root, "no.zip").apply { writeBytes(DesignZip.write(listOf(ZipFileEntry("a.txt", ByteArray(1), null)), LocalDateTime.of(2026, 1, 1, 0, 0))) }
        assertThat(test.run(null, "open", noDesign.path).exitCode).isEqualTo(ExitCodes.DESIGN_UNREADABLE)
        assertThat(test.run(null, "open", test.zip("bad.zip", "{\"a\": ").path).exitCode).isEqualTo(ExitCodes.DESIGN_UNREADABLE)
        assertThat(test.run(null, "open", File(test.root, "missing.zip").path).exitCode).isEqualTo(ExitCodes.WRONG_USE)
        val notZip = File(test.root, "text.zip").apply { writeText("hello") }
        assertThat(test.run(null, "open", notZip.path).exitCode).isEqualTo(ExitCodes.DESIGN_UNREADABLE)
    }

    @Test
    fun `save keeps unknown files, the order and the old zip`() {
        val zip = test.zip()
        val old = zip.readBytes()
        val dir = test.open(zip)
        test.ok(dir, "set", "animation.durationMs", "9000")
        val saved = test.ok(dir, "save")
        assertThat(saved.text("engineCheck")).isEqualTo("skipped")
        val entries = DesignZip.read(zip.readBytes())
        assertThat(entries.map { it.name }).containsExactly("CustomWatchface.json", "Star.png", "Face.ttf", "LICENCE.txt").inOrder()
        assertThat(entries[0].bytes.decodeToString()).isEqualTo(test.smallDesign.replace("6000", "9000"))
        // The old zip is kept under the number of the history entry of the save
        assertThat(File(dir, ".facetool/history/2.zip").readBytes().contentEquals(old)).isTrue()
        // A second open still knows the folder: the project now belongs to the new zip
        assertThat(test.ok(null, "open", zip.path)["alreadyOpen"]).isEqualTo(JsonPrimitive(true))
    }

    @Test
    fun `a zip with compact JSON stays compact and the folder gets the 4 space form`() {
        val compact = AndroidJson.compact(AndroidJson.parse(test.smallDesign))
        val zip = test.zip("Compact.zip", compact)
        val dir = test.open(zip)
        assertThat(File(dir, "CustomWatchface.json").readText()).isEqualTo(test.smallDesign)
        val out = File(test.root, "out.zip")
        test.ok(dir, "save", "--out", out.path)
        assertThat(DesignZip.read(out.readBytes())[0].bytes.decodeToString()).isEqualTo(compact)
    }

    @Test
    fun `save stops on a missing resource and on a zip over the limit, force writes`() {
        val dir = test.open()
        val out = File(test.root, "out.zip")
        test.ok(dir, "res", "remove", "Star")
        val stopped = test.run(dir, "save", "--out", out.path)
        assertThat(stopped.exitCode).isEqualTo(ExitCodes.CHECK_ERRORS)
        assertThat(stopped.text).contains("'Star' is named but there is no such file")
        assertThat(stopped.text).contains("animation.layers[0].image")
        assertThat(out.exists()).isFalse()
        val forced = test.run(dir, "save", "--out", out.path, "--force")
        assertThat(forced.exitCode).isEqualTo(ExitCodes.CHECK_ERRORS)
        assertThat(out.isFile).isTrue()

        test.ok(dir, "undo")
        val small = LocalCommands(EditorConfig(test.config.editingRoot, test.config.generated, zipLimitBytes = 100), test.clock)
        val tooBig = small.run("save", JsonObject(mapOf("out" to JsonPrimitive(out.path))), dir)
        assertThat(tooBig.exitCode).isEqualTo(ExitCodes.CHECK_ERRORS)
        assertThat(tooBig.text).contains("over the limit of 100 bytes")
    }

    @Test
    fun `save as writes the new zip and later saves go there, it does not write over another zip`() {
        val zip = test.zip()
        val old = zip.readBytes()
        val dir = test.open(zip)
        val other = File(test.root, "Other.zip").apply { writeBytes(old) }
        val refused = test.run(dir, "save", "--as", other.path)
        assertThat(refused.exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(refused.text).contains("is there already")
        assertThat(test.run(dir, "save", "--as", other.path, "--out", other.path).exitCode).isEqualTo(ExitCodes.WRONG_USE)

        val renamed = File(test.root, "Renamed.zip")
        test.ok(dir, "set", "metadata.name", "\"Renamed\"")
        val saved = test.ok(dir, "save", "--as", renamed.path)
        assertThat(saved.text("text")).contains("From now on Save writes to")
        assertThat(DesignZip.read(renamed.readBytes())[0].bytes.decodeToString()).contains("\"name\": \"Renamed\"")
        // A plain save now writes the new zip; the old one stays as it was
        test.ok(dir, "set", "animation.durationMs", "9000")
        test.ok(dir, "save")
        assertThat(DesignZip.read(renamed.readBytes())[0].bytes.decodeToString()).contains("9000")
        assertThat(zip.readBytes().contentEquals(old)).isTrue()
        assertThat(test.ok(dir, "info").text("sourceZip")).isEqualTo(renamed.canonicalPath)
    }

    @Test
    fun `save uses the engine check when there is one`() {
        val dir = test.open()
        val withEngine = LocalCommands(test.config, test.clock, engineCheck = { listOf("the engine says no") })
        val answer = withEngine.run("save", JsonObject(emptyMap()), dir)
        assertThat(answer.exitCode).isEqualTo(ExitCodes.CHECK_ERRORS)
        assertThat(answer.text).contains("the engine says no")
        assertThat(answer.json.text("engineCheck")).isEqualTo("done")
    }

    @Test
    fun `a design of a generator gets the warning at open and save`() {
        val zip = test.zip("Copy_Of_It.zip", test.smallDesign.replace("\"filename\": \"Small\"", "\"filename\": \"Made_By_Generator\""))
        val opened = test.run(null, "open", zip.path)
        assertThat(opened.text).contains("made by a generator")
        val dir = File(opened.json.text("dir"))
        assertThat(test.run(dir, "save").text).contains("made by a generator")
        assertThat(test.ok(dir, "info")["generated"]).isEqualTo(JsonPrimitive(true))
    }

    @Test
    fun `a design that does not read gives exit code 3`() {
        val dir = test.open()
        File(dir, "CustomWatchface.json").writeText("{ \"a\": oops }")
        assertThat(test.run(dir, "show").exitCode).isEqualTo(ExitCodes.DESIGN_UNREADABLE)
        assertThat(test.run(dir, "save").exitCode).isEqualTo(ExitCodes.DESIGN_UNREADABLE)
    }

    @Test
    fun `info tells the facts and whether the folder changed`() {
        val dir = test.open()
        val info = test.ok(dir, "info")
        assertThat(info.text("name")).isEqualTo("Small")
        assertThat(info["counts"]!!.compact()).isEqualTo("""{"layers":4,"views":1,"templates":0,"options":0,"looks":0}""")
        assertThat(info["wakeMs"]).isEqualTo(JsonPrimitive(6000))
        assertThat(info["wakeFps"]).isEqualTo(JsonPrimitive(24))
        assertThat(info["changedSinceOpen"]).isEqualTo(JsonPrimitive(false))
        assertThat(info["generated"]).isEqualTo(JsonPrimitive(false))
        File(dir, "Star.png").writeBytes(test.png(8, 8))
        assertThat(test.ok(dir, "info")["changedSinceOpen"]).isEqualTo(JsonPrimitive(true))
    }

    @Test
    fun `new makes an empty design that saves`() {
        val answer = test.ok(null, "new", "My Face", "--size", "400")
        val dir = File(answer.text("dir"))
        assertThat(dir.name).isEqualTo("My_Face")
        val design = ProjectFolder(dir).design()
        assertThat(JsonPaths.get(design, JsonPaths.parse("metadata.name"))).isEqualTo(JsonPrimitive("My Face"))
        assertThat(DesignEdits.layers(design)).isEmpty()
        assertThat(test.run(dir, "save").exitCode).isEqualTo(ExitCodes.WRONG_USE)
        val out = File(test.root, "My_Face.zip")
        test.ok(dir, "save", "--out", out.path)
        assertThat(DesignZip.read(out.readBytes()).map { it.name }).containsExactly("CustomWatchface.json", "CustomWatchface.png").inOrder()
        test.ok(dir, "save")
    }

    @Test
    fun `the lock waits, reports a long lock and breaks it only with force`() {
        val clock = TestClock()
        val file = File(test.root, "lock/.facetool/lock")
        val lock = FolderLock(file, clock, limitMs = 30_000, sleep = { clock.plus(it) })
        assertThat(lock.with("first", false) { file.isFile }).isTrue()
        assertThat(file.exists()).isFalse()

        // A lock of another command that never ends
        file.parentFile.mkdirs()
        file.writeText("${clock.millis()}\nfacetool set a 1\n")
        val error = assertThrows(FacetoolException::class.java) { lock.with("second", false) { } }
        assertThat(error.message).contains("locked for 30 s by: facetool set a 1")
        assertThat(file.exists()).isTrue()
        assertThat(lock.with("third", true) { "done" }).isEqualTo("done")
        assertThat(file.exists()).isFalse()

        // The lock is free again after an error in the work
        assertThrows(IllegalStateException::class.java) { lock.with("fourth", false) { error("fails") } }
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `the project gives its scene, its out folder, its zip and a hash`() {
        val dir = test.open()
        val project = ProjectFolder(dir)
        assertThat(project.scene()).isEqualTo(Scenes.defaults())
        assertThat(project.outDir("wff render")).isEqualTo(File(dir, ".facetool/out/wff-render"))
        assertThat(project.outDir("render").isDirectory).isTrue()
        assertThat(DesignZip.read(project.zipBytes()).map { it.name }).containsExactly("CustomWatchface.json", "Star.png", "Face.ttf", "LICENCE.txt").inOrder()
        assertThat(project.zipBytes().contentEquals(project.zipBytes())).isTrue()
        val hash = project.hash()
        test.ok(dir, "set", "sgv.width", "151")
        assertThat(project.hash()).isNotEqualTo(hash)
        test.ok(dir, "undo")
        assertThat(project.hash()).isEqualTo(hash)
        assertThat(project.resources().keys).containsExactly("Star.png", "Face.ttf", "LICENCE.txt")
    }
}
