package app.aaps.facetool.editor.server

import java.io.File

/**
 * What the server knows about itself (design 5.4): the AAPS commit it draws with, the commit this
 * repository was tested with, and whether the build is newer than the server.
 *
 * @param aapsCommit the commit of the AAPS checkout at the start of the server, or null when unknown
 * @param pinnedCommit the commit in `aaps-commit.txt`, or null when the file is missing
 * @param stampFile `editor-server/build/editor/stamp.txt`, written new by each build of the class path
 */
class ServerFacts(val aapsCommit: String?, val pinnedCommit: String?, private val stampFile: File?, val startedAtMs: Long) {

    private val stampAtStart: String? = stamp()

    /** The stamp of the build the server runs, for caches. */
    val buildStamp: String get() = stampAtStart ?: "none"

    private fun stamp(): String? = stampFile?.takeIf { it.isFile }?.readText()?.trim()

    val isPinned: Boolean get() = aapsCommit != null && pinnedCommit != null && aapsCommit.startsWith(pinnedCommit)

    /** True when the server's code is older than the build: it must be started again. */
    val restartNeeded: Boolean get() = stamp() != stampAtStart

    /** The warnings every answer carries. */
    fun warnings(): List<String> = listOfNotNull(
        "The AAPS checkout is on ${aapsCommit?.take(10) ?: "an unknown commit"}, not on the tested ${pinnedCommit?.take(10)}: the preview may differ from the watch".takeIf { !isPinned },
        "restart needed: the server is older than the build. Run facetool stop, the next command starts the new one".takeIf { restartNeeded }
    )

    companion object {

        /** The facts of the checkout [checkout] of this repository with the AAPS checkout [aapsDir]. */
        fun of(checkout: File?, aapsDir: File?, nowMs: Long): ServerFacts {
            val head = aapsDir?.let { dir ->
                runCatching { ProcessBuilder("git", "-C", dir.path, "rev-parse", "HEAD").redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
            }
            val pinned = checkout?.let { File(it, "aaps-commit.txt") }?.takeIf { it.isFile }?.readLines()?.firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
            return ServerFacts(head, pinned, checkout?.let { File(it, "editor-server/build/editor/stamp.txt") }, nowMs)
        }
    }
}
