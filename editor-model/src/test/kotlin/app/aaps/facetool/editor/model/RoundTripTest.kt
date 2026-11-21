package app.aaps.facetool.editor.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Test 12.2 (bytes) of the design: `open` then `save` of each design zip without an edit gives the
 * same JSON bytes and the same bytes of every resource, with the same names in the same order.
 * The zips come from the system property `wff.zips`; without it the test is skipped.
 */
class RoundTripTest {

    private fun zips(): List<File> {
        val dir = System.getProperty("wff.zips")?.let { File(it) }
        assumeTrue("wff.zips is not set", dir != null && dir.isDirectory)
        val zips = dir!!.listFiles { file -> file.name.endsWith(".zip") }.orEmpty().sortedBy { it.name }
        assumeTrue("no zips in $dir", zips.isNotEmpty())
        return zips
    }

    @Test
    fun `open then save gives the same files for every design zip`() {
        val test = TestProjects()
        var sameZipBytes = 0
        val zips = zips()
        for (zip in zips) {
            val dir = File(test.root, "p/${zip.name.removeSuffix(".zip")}")
            test.ok(null, "open", zip.path, "--dir", dir.path)
            val out = File(test.root, "out/${zip.name}")
            val saved = test.run(dir, "save", "--out", out.path)
            assertWithMessage("${zip.name}: ${saved.text}").that(saved.exitCode).isEqualTo(0)

            val before = DesignZip.read(zip.readBytes())
            val after = DesignZip.read(out.readBytes())
            assertWithMessage("${zip.name}: names and order").that(after.map { it.name }).isEqualTo(before.map { it.name })
            for ((a, b) in before.zip(after)) {
                if (a.name == ResourceNames.DESIGN_JSON) assertWithMessage("${zip.name}: JSON text").that(b.bytes.decodeToString()).isEqualTo(a.bytes.decodeToString())
                assertWithMessage("${zip.name}: bytes of ${a.name}").that(b.bytes.contentEquals(a.bytes)).isTrue()
                assertWithMessage("${zip.name}: time of ${a.name}").that(b.time).isEqualTo(a.time)
            }
            // The folder holds exactly the files of the zip, and the JSON in the 4 space form
            assertThat(test.files(dir).keys).containsExactlyElementsIn(before.map { it.name })
            val folderJson = File(dir, ResourceNames.DESIGN_JSON).readText()
            assertThat(folderJson).isEqualTo(AndroidJson.write(AndroidJson.parse(folderJson)))
            if (out.readBytes().contentEquals(zip.readBytes())) sameZipBytes++
        }
        println("RoundTripTest: ${zips.size} zips, $sameZipBytes of them with the same zip bytes after save")
    }

    @Test
    fun `every design zip is known as made by a generator`() {
        val generated = GeneratedDesigns.standard()
        for (zip in zips()) {
            val json = DesignZip.read(zip.readBytes()).first { it.name == ResourceNames.DESIGN_JSON }.bytes.decodeToString()
            assertWithMessage(zip.name).that(generated.isGenerated(AndroidJson.parseObject(json, zip.name))).isTrue()
        }
        assertThat(generated.names).hasSize(12)
    }

    @Test
    fun `info and the lists read every design zip`() {
        val test = TestProjects()
        for (zip in zips()) {
            val dir = File(test.root, "p/${zip.name.removeSuffix(".zip")}")
            test.ok(null, "open", zip.path, "--dir", dir.path)
            assertThat(test.ok(dir, "info").text("filename")).isEqualTo(zip.name.removeSuffix(".zip"))
            for (kind in DesignLists.KINDS) test.ok(dir, "list", kind)
            // No resource that the JSON names is missing in a real design
            val project = ProjectFolder(dir)
            val errors = Projects(test.config, test.clock).check(project, project.design(), 1).filter { it.severity == "error" }
            assertWithMessage(zip.name).that(errors.map { it.text }).isEmpty()
        }
    }
}
