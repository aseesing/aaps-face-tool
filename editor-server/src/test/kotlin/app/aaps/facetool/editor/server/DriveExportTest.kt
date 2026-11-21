package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.FacetoolException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** An export goes into the Google Drive folder, over a file of the same name in place. */
class DriveExportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun exported(name: String, text: String): File =
        File(temp.root, "project/.facetool/out/export/$name").apply { parentFile.mkdirs(); writeText(text) }

    private fun inode(file: File): Any = Files.getAttribute(file.toPath(), "unix:ino")

    @Test
    fun aFileOfTheSameNameIsWrittenOverInPlace() {
        val drive = temp.newFolder("My Drive", "AAPS", "latest")
        val export = DriveExport(drive)
        val first = export.copy(exported("BG_Reactor.zip", "one"), "BG_Reactor.zip")
        assertThat(first.replaced).isFalse()
        val before = inode(first.file)
        val second = export.copy(exported("BG_Reactor.zip", "two"), "BG_Reactor.zip")
        assertThat(second.replaced).isTrue()
        assertThat(second.file.readText()).isEqualTo("two")
        // The same file, not a new one beside it: Drive keeps it as one file with a new version
        assertThat(inode(second.file)).isEqualTo(before)
        assertThat(drive.list()!!.toList()).containsExactly("BG_Reactor.zip")
    }

    @Test
    fun onlyAnExportedFileAndAPlainName() {
        val export = DriveExport(temp.newFolder("drive"))
        val outside = File(temp.root, "secret.txt").apply { writeText("no") }
        assertThrows(FacetoolException::class.java) { export.copy(outside, "secret.txt") }
        assertThrows(FacetoolException::class.java) { export.copy(exported("a.zip", "a"), "../a.zip") }
        assertThrows(FacetoolException::class.java) { DriveExport(null).copy(exported("b.zip", "b"), "b.zip") }
        assertThrows(FacetoolException::class.java) { DriveExport(File(temp.root, "gone")).copy(exported("c.zip", "c"), "c.zip") }
    }

    @Test
    fun theFolderComesFromLocalPropertiesElseFromDrive() {
        val home = temp.newFolder("home")
        val latest = File(home, "Library/CloudStorage/GoogleDrive-someone/My Drive/AAPS/latest").apply { mkdirs() }
        assertThat(DriveExport.find(null, home)).isEqualTo(latest)
        val properties = File(temp.root, "local.properties").apply { writeText("sdk.dir=/x\ndriveDir=/somewhere/else\n") }
        assertThat(DriveExport.find(properties, home)).isEqualTo(File("/somewhere/else"))
        assertThat(DriveExport.find(null, temp.newFolder("empty-home"))).isNull()
    }
}
