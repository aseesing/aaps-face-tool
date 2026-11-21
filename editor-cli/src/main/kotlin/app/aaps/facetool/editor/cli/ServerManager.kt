package app.aaps.facetool.editor.cli

import app.aaps.facetool.editor.model.ExitCodes
import app.aaps.facetool.editor.model.FacetoolException
import java.io.File
import java.time.Clock

/**
 * The life of the server as the CLI sees it (design section 5.4): find a running server, or start
 * one in the background and wait until it answers.
 */
class ServerManager(
    private val files: ServerFiles,
    private val launcher: ServerLauncher,
    private val clock: Clock,
    private val log: (String) -> Unit,
    private val startLimitMs: Long = 120_000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {

    val checkout: File get() = files.checkout

    /** A client of the server when it runs and answers, else null. */
    fun running(): ServerClient? = files.read()?.let { ServerClient(it) }?.takeIf { it.ping() }

    /** A client of the running server. Starts the server when it does not answer, but not with [noStart]. */
    fun ensure(noStart: Boolean): ServerClient {
        running()?.let { return it }
        if (noStart) throw FacetoolException("The server does not run, and --no-start is given. Start it with: facetool serve", ExitCodes.SERVER_FAILED)
        val begin = clock.millis()
        log("Starting the server (log: ${files.log.path}) ...")
        val process = launcher.startBackground(files.checkout, files.log)
        while (clock.millis() - begin < startLimitMs) {
            running()?.let {
                log("The server runs after ${(clock.millis() - begin) / 100 / 10.0} s.")
                return it
            }
            if (!process.alive() && running() == null) throw FacetoolException("The server stopped while it started. ${tail()}", ExitCodes.SERVER_FAILED)
            sleep(250)
        }
        throw FacetoolException("The server did not answer within ${startLimitMs / 1000} s. ${tail()}", ExitCodes.SERVER_FAILED)
    }

    /** Runs the server in the foreground until it ends. */
    fun serve(): Int = launcher.runForeground(files.checkout)

    /** Waits until the server no longer answers. Gives false when it still answers after [limitMs]. */
    fun waitUntilStopped(client: ServerClient, limitMs: Long = 10_000): Boolean {
        val begin = clock.millis()
        while (clock.millis() - begin < limitMs) {
            if (!client.ping()) return true
            sleep(100)
        }
        return false
    }

    private fun tail(): String = if (files.log.isFile) "The end of ${files.log.path}:\n" + files.log.readLines().takeLast(15).joinToString("\n") else "There is no log at ${files.log.path}."
}
