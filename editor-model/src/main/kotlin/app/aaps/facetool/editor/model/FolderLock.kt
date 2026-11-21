package app.aaps.facetool.editor.model

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.time.Clock

/**
 * The lock file `.facetool/lock` of a project folder (design section 6.1). It stops two commands
 * from writing the folder at the same moment. A command waits for a short lock. A lock older than
 * [limitMs] is reported and never broken without `force`.
 */
class FolderLock(private val file: File, private val clock: Clock, private val limitMs: Long = 30_000, private val sleep: (Long) -> Unit = { Thread.sleep(it) }) {

    /** Runs [work] while this process holds the lock. [owner] is written into the lock file, for the message of a waiting command. */
    fun <T> with(owner: String, force: Boolean, work: () -> T): T {
        take(owner, force)
        try {
            return work()
        } finally {
            file.delete()
        }
    }

    private fun take(owner: String, force: Boolean) {
        file.parentFile.mkdirs()
        while (true) {
            try {
                Files.createFile(file.toPath())
                file.writeText("${clock.millis()}\n$owner\n")
                return
            } catch (_: FileAlreadyExistsException) {
                val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
                val since = lines.firstOrNull()?.toLongOrNull() ?: file.lastModified()
                val age = clock.millis() - since
                if (age > limitMs) {
                    if (force) {
                        file.delete()
                        continue
                    }
                    throw FacetoolException(
                        "The project folder is locked for ${age / 1000} s by: ${lines.getOrNull(1) ?: "an unknown command"}. " +
                            "When that command no longer runs, give --force to break the lock (${file.path}).",
                        ExitCodes.WRONG_USE
                    )
                }
                sleep(100)
            }
        }
    }
}
