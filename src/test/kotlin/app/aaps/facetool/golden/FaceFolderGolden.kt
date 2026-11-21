package app.aaps.facetool.golden

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.security.MessageDigest

/**
 * The golden hash lists of the face folders the harness makes (`docs/face-editor-design.md`, 12.1):
 * one text file per face in [goldenDir], with the SHA-256 of every file of the face folder. A change
 * of the harness that should change no output must leave every list as it is.
 *
 * @param write true to write the lists new instead of checking them (`-PwffGoldenWrite`)
 */
class FaceFolderGolden(private val goldenDir: File, private val write: Boolean) {

    /** The hash list of the face folder [face]: one line per file, sorted by path. */
    fun hashes(face: File): String =
        face.walkTopDown().filter { it.isFile }.map { it.relativeTo(face).invariantSeparatorsPath to sha256(it.readBytes()) }
            .sortedBy { it.first }.joinToString("") { (path, hash) -> "$hash  $path\n" }

    /** Checks the face folder [face] against the list [name], or writes the list. */
    fun check(name: String, face: File) {
        val golden = File(goldenDir, "$name.txt")
        val now = hashes(face)
        if (write) {
            golden.parentFile?.mkdirs()
            golden.writeText(now)
            return
        }
        assertWithMessage("no golden list %s: write it with -PwffGoldenWrite", golden).that(golden.isFile).isTrue()
        val then = golden.readText()
        if (now == then) return
        val before = then.lines().toSet()
        val after = now.lines().toSet()
        val changed = (after - before).map { "now  $it" } + (before - after).map { "was  $it" }
        assertWithMessage("the face folder %s differs from its golden list %s:\n%s", face, golden, changed.joinToString("\n")).that(now).isEqualTo(then)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {

        /** The lists of this repository, written new when the run has `-PwffGoldenWrite`. */
        fun ofRepository(): FaceFolderGolden = FaceFolderGolden(File("src/test/golden/faces"), System.getProperty("wff.golden.write") == "true")
    }
}
