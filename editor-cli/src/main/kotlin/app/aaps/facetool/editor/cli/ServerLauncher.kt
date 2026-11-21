package app.aaps.facetool.editor.cli

import app.aaps.facetool.editor.model.ExitCodes
import app.aaps.facetool.editor.model.FacetoolException
import java.io.File

/** A started server process. */
interface ServerProcess {

    fun alive(): Boolean
}

/** Starts the render server. The tests give their own, so no real server starts there. */
interface ServerLauncher {

    /** Starts the server in the background, with its output in [log]. */
    fun startBackground(checkout: File, log: File): ServerProcess

    /** Runs the server in the foreground until it ends. Gives its exit code. */
    fun runForeground(checkout: File): Int
}

/**
 * Starts the server as a Java process, without Gradle (design section 4.3): the Java of
 * `editor-server/build/editor/java.txt` with the arguments of `classpath.txt`, as a JUnit run of
 * `EditorServerMain` that does not end. The Gradle task `:editor-server:editorClasspath` writes both files.
 */
class ProcessServerLauncher : ServerLauncher {

    override fun startBackground(checkout: File, log: File): ServerProcess {
        log.parentFile.mkdirs()
        val nohup = File("/usr/bin/nohup").takeIf { it.canExecute() }?.let { listOf(it.path) } ?: emptyList()
        val process = ProcessBuilder(nohup + command(checkout))
            .directory(checkout)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
        return object : ServerProcess {
            override fun alive(): Boolean = process.isAlive
        }
    }

    override fun runForeground(checkout: File): Int = ProcessBuilder(command(checkout)).directory(checkout).inheritIO().start().waitFor()

    /** The command line of the server. Ends with exit code 4 when the build did not write the class path yet. */
    fun command(checkout: File): List<String> {
        val java = File(checkout, "$BUILD_DIR/java.txt")
        val classpath = File(checkout, "$BUILD_DIR/classpath.txt")
        if (!java.isFile || !classpath.isFile) throw FacetoolException(
            "The server is not built: ${classpath.path} is missing. Run in ${checkout.path}: ./gradlew :editor-server:editorClasspath " +
                "(on this Mac through the lock script: sh ~/.claude/bin/gradle-locked.sh ${checkout.path} <log file> :editor-server:editorClasspath)",
            ExitCodes.SERVER_FAILED
        )
        return listOf(
            java.readLines().first().trim(), "@$BUILD_DIR/classpath.txt", "-Deditor.serve=true", "-Deditor.checkout=${checkout.path}",
            "org.junit.runner.JUnitCore", "app.aaps.facetool.editor.server.EditorServerMain"
        )
    }

    companion object {

        const val BUILD_DIR = "editor-server/build/editor"
    }
}
