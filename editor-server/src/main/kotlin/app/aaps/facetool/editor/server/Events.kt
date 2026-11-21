package app.aaps.facetool.editor.server

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The server-sent events of the API (design section 9): `changed` (a project folder changed on
 * disk), `rendered`, `lint` and `log` (a command ran, with its CLI line). A listener gets the events
 * of its project and the events of no project.
 */
class Events {

    private class Listener(val project: String?) {

        val queue = LinkedBlockingQueue<String>()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var open = true

    /** Called when a listener for [File] starts: the server then watches that project folder. */
    var onListen: (File) -> Unit = {}

    /** Sends the event [name] with [data] to the listeners of [project] (null: to all). */
    fun send(name: String, data: JsonObject, project: File?) {
        val path = project?.canonicalPath
        val message = "event: $name\ndata: $data\n\n"
        for (listener in listeners) if (path == null || listener.project == null || listener.project == path) listener.queue.offer(message)
    }

    /** Answers one `GET /api/events` and holds it open until the browser goes away or the server stops. */
    fun serve(exchange: HttpExchange, project: String?) {
        val listener = Listener(project?.let { File(it).canonicalPath })
        project?.let { onListen(File(it)) }
        listeners += listener
        try {
            exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, 0)
            val out: OutputStream = exchange.responseBody
            out.write(": facetool events\n\n".toByteArray())
            out.flush()
            while (open) {
                // A comment line every 15 s shows whether the browser is still there
                val message = listener.queue.poll(15, TimeUnit.SECONDS) ?: ": still here\n\n"
                out.write(message.toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
            // The browser closed the page
        } finally {
            listeners -= listener
        }
    }

    fun close() {
        open = false
    }
}
