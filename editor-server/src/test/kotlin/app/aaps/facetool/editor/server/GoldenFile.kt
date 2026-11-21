package app.aaps.facetool.editor.server

import com.google.common.truth.Truth.assertWithMessage
import java.io.File

/**
 * A golden text file of the server tests in `editor-server/src/test/golden/`.
 * `-PeditorGoldenWrite=<start of the file names>` writes those files new (`render/`, or `all`); else a
 * test checks its text against the file.
 */
class GoldenFile(private val name: String) {

    private val file = File("editor-server/src/test/golden/$name")

    fun check(text: String, mayWrite: Boolean = true) {
        val write = System.getProperty("editor.golden.write")
        if (mayWrite && write != null && (write == "all" || name.startsWith(write))) {
            file.parentFile.mkdirs()
            file.writeText(text)
            return
        }
        assertWithMessage("no golden file %s: write it with -PeditorGoldenWrite", file).that(file.isFile).isTrue()
        assertWithMessage("differs from the golden file %s", file).that(text).isEqualTo(file.readText())
    }
}
