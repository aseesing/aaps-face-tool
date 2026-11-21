package app.aaps.facetool.editor.server

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches project folders for the GUI (design section 10): when a file of the design changes on disk,
 * [onChange] is called with the folder. It looks at the folders every [intervalMs]; the `.facetool`
 * folder is left out. (The JDK `WatchService` also only polls on macOS, and more slowly.)
 */
class FolderWatcher(private val intervalMs: Long = 400, private val onChange: (File) -> Unit) {

    private val stamps = ConcurrentHashMap<String, String>()

    @Volatile
    private var thread: Thread? = null

    /** Starts to watch [dir]. Watching a folder twice changes nothing. */
    fun watch(dir: File) {
        val path = dir.canonicalPath
        if (stamps.putIfAbsent(path, stamp(File(path))) != null) return
        if (thread == null) synchronized(this) {
            if (thread == null) thread = Thread({ loop() }, "facetool-watch").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        thread?.interrupt()
    }

    private fun loop() {
        try {
            while (true) {
                Thread.sleep(intervalMs)
                for ((path, old) in stamps) {
                    val now = stamp(File(path))
                    if (now == old) continue
                    stamps[path] = now
                    runCatching { onChange(File(path)) }
                }
            }
        } catch (_: InterruptedException) {
            // The server stops
        }
    }

    /** The names, sizes and times of the design's files. */
    private fun stamp(dir: File): String =
        dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
}
