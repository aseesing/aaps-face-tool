package app.aaps.facetool.editor.server

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import kotlin.concurrent.thread

/**
 * AI help stays outside the editor (design decision 9): an agent changes the project folder with its
 * own tools, and the page must hear of it. A change of a design file from outside gives the event
 * `changed`; a file of the editor's own folder `.facetool` gives none.
 */
class OutsideChangeTest : ServerTestBase() {

    @Test
    fun aChangeOfTheFolderFromOutsideReachesThePage() {
        val project = open("D2")
        val port = server.start()
        try {
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/events?token=${server.apiToken}&project=" + URLEncoder.encode(project.path, "UTF-8"))).GET().build()
            val stream = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream()).body()
            val events = Collections.synchronizedList(ArrayList<String>())
            thread(isDaemon = true) { runCatching { stream.bufferedReader().forEachLine { line -> if (line.startsWith("event: ")) events += line.removePrefix("event: ") } } }
            fun changes(): Int = events.count { it == "changed" }
            fun waitFor(count: Int): Int {
                val limit = System.currentTimeMillis() + 4000
                while (changes() < count && System.currentTimeMillis() < limit) Thread.sleep(50)
                return changes()
            }
            // The watcher has looked at the folder once before anything changes
            Thread.sleep(1000)
            assertThat(changes()).isEqualTo(0)

            // The editor's own files are not the design
            File(project, ".facetool/out/render").mkdirs()
            File(project, ".facetool/out/render/x.png").writeBytes(byteArrayOf(1, 2, 3))
            Thread.sleep(1200)
            assertThat(changes()).isEqualTo(0)

            // An agent edits the JSON file with its own tools
            val json = File(project, "CustomWatchface.json")
            json.writeText(json.readText().replace("\"midColor\": \"#A3C4A8\"", "\"midColor\": \"#FF00FF\""))
            assertThat(json.readText()).contains("#FF00FF")
            assertThat(waitFor(1)).isEqualTo(1)

            // An agent adds a resource file
            File(project, "Extra.png").writeBytes(File(project, "CustomWatchface.png").takeIf { it.isFile }?.readBytes() ?: byteArrayOf(1))
            assertThat(waitFor(2)).isEqualTo(2)

            // A command of the CLI in the same folder (another process in real use) is such a change too
            run("set", mapOf("path" to JsonPrimitive("midColor"), "value" to JsonPrimitive("#00FF00")), project)
            assertThat(waitFor(3)).isEqualTo(3)
            stream.close()
        } finally {
            server.stop()
        }
    }
}
