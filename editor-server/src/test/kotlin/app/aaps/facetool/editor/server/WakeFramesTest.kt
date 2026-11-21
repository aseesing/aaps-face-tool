package app.aaps.facetool.editor.server

import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * The frames call that Play of the page uses (design section 10): `wake --frames <dir> --no-sheet`
 * gives the same frames as the wake with a sheet, takes a whole wake at the design's own rate, and
 * tells the page of each frame as soon as its file is written.
 */
class WakeFramesTest : ServerTestBase() {

    private fun frames(answer: JsonObject): List<File> = (answer.getValue("frames") as JsonArray).map { File((it as JsonPrimitive).content) }

    /** The pixels of each picture. The frames without a sheet are packed faster, so their bytes differ; their pixels must not. */
    private fun bytes(files: List<File>): List<List<Int>> = files.map { file ->
        val bitmap = BitmapFactory.decodeFile(file.path)
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }.toList() + listOf(bitmap.width, bitmap.height)
    }

    @Test
    fun framesWithoutASheetAreTheFramesOfTheSheet() {
        val project = open("D2")
        val range = mapOf<String, JsonElement>("fps" to JsonPrimitive(4), "from" to JsonPrimitive(0), "to" to JsonPrimitive(2))
        // The first wake of a kept face is left out of the compare. Found while this test was written, and
        // older than it: in D2 the first `wake` after the face is made differs from every later one in some
        // hundred pixels, from 1 s on (the same command twice, both with a sheet). Later wakes are the same.
        run("wake", range, project)
        val sheet = run("wake", range, project)
        val play = run("wake", range + mapOf("no-sheet" to JsonPrimitive(true), "frames" to JsonPrimitive(File(temp, "play").path)), project)
        assertThat(play).doesNotContainKey("file")
        assertThat(frames(play)).hasSize(9)
        assertThat(frames(play).map { it.parentFile }.toSet()).containsExactly(File(temp, "play"))
        assertThat(bytes(frames(play))).isEqualTo(bytes(frames(sheet)))
        // Packed fast: other bytes than Android's PNG writer gives, the same pixels
        assertThat(frames(play)[0].readBytes().toList()).isNotEqualTo(frames(sheet)[0].readBytes().toList())
        assertThat(text(play, "frameMs")).isEqualTo("250")
        assertThat((play.getValue("seconds") as JsonPrimitive).content.toDouble()).isGreaterThan(0.0)

        // A hidden view is not in the frames
        val hidden = run("wake", range + mapOf("no-sheet" to JsonPrimitive(true), "hide" to JsonPrimitive("chart"), "frames" to JsonPrimitive(File(temp, "hidden").path)), project)
        assertThat(bytes(frames(hidden))).isNotEqualTo(bytes(frames(play)))
    }

    @Test
    fun aWholeWakeAtTheRateOfTheDesignFitsWithoutASheet() {
        val project = open("D2")
        val info = run("info", emptyMap(), project)
        val fps = text(info, "wakeFps").toInt()
        val seconds = text(info, "wakeMs").toInt() / 1000
        assertThat(fps).isEqualTo(30)
        // 301 frames at the design's 30 a second: too many for a sheet, fine without one. Small pictures keep the test short.
        val small = JsonArray(listOf(JsonPrimitive("size=120")))
        val all = mapOf<String, JsonElement>("fps" to JsonPrimitive(fps), "from" to JsonPrimitive(0), "to" to JsonPrimitive(seconds + 4), "set" to small)
        val refused = answer("wake", all, project)
        assertThat(refused.exitCode).isEqualTo(2)
        assertThat(text(refused.json, "text")).contains("--no-sheet")
        val play = run("wake", all + mapOf("no-sheet" to JsonPrimitive(true)), project)
        val files = frames(play)
        assertThat(files).hasSize(fps * (seconds + 4) + 1)
        for (file in files) assertWithMessage(file.path).that(file.length()).isGreaterThan(0L)
        assertThat(files.map { it.name }.toSet()).hasSize(files.size)
    }

    @Test
    fun thePageCanStopAFramesCall() {
        val project = open("D2")
        val port = server.start()
        try {
            val dir = File(temp, "play")
            fun cancel(folder: File) {
                val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/cancel?dir=" + URLEncoder.encode(folder.path, "UTF-8")))
                    .header("X-Facetool-Token", server.apiToken).POST(HttpRequest.BodyPublishers.noBody()).build()
                assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200)
            }
            val args = mapOf<String, JsonElement>("fps" to JsonPrimitive(4), "from" to JsonPrimitive(0), "to" to JsonPrimitive(1), "no-sheet" to JsonPrimitive(true), "frames" to JsonPrimitive(dir.path))
            // A stop for another folder changes nothing
            cancel(File(temp, "other"))
            assertThat(frames(run("wake", args, project))).hasSize(5)
            // A stop that waits when the call begins: no frame is made, and the answer says so
            cancel(dir)
            val stopped = run("wake", args, project)
            assertThat(text(stopped, "stopped")).isEqualTo("true")
            assertThat(frames(stopped)).isEmpty()
            // The stop is used up: the next call makes all frames again
            val next = run("wake", args, project)
            assertThat(next).doesNotContainKey("stopped")
            assertThat(frames(next)).hasSize(5)
        } finally {
            server.stop()
        }
    }

    @Test
    fun thePageHearsOfEachFrameWhenItIsWritten() {
        val project = open("D2")
        val port = server.start()
        try {
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/events?token=${server.apiToken}&project=" + URLEncoder.encode(project.path, "UTF-8"))).GET().build()
            val stream = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream()).body()
            val seen = Collections.synchronizedList(ArrayList<String>())
            val reader = thread(isDaemon = true) {
                runCatching { stream.bufferedReader().forEachLine { line -> if (line.startsWith("data: ") && "\"done\"" in line) seen += line.removePrefix("data: ") } }
            }
            val play = run("wake", mapOf("fps" to JsonPrimitive(4), "from" to JsonPrimitive(0), "to" to JsonPrimitive(1), "no-sheet" to JsonPrimitive(true)), project)
            val files = frames(play)
            val limit = System.currentTimeMillis() + 5000
            while (seen.size < files.size && System.currentTimeMillis() < limit) Thread.sleep(20)
            stream.close()
            reader.join(1000)
            assertThat(seen).hasSize(files.size)
            // One event per frame, in the order of the frames, each with the file that is written by then
            files.forEachIndexed { i, file ->
                assertThat(seen[i]).contains("\"done\":${i + 1}")
                assertThat(seen[i]).contains("\"total\":${files.size}")
                assertThat(seen[i]).contains(file.name)
            }
        } finally {
            server.stop()
        }
    }
}
