package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.AndroidJson
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Open and New of the page (design section 10): the list of zips and recent projects, a dropped zip,
 * and the rule that opening never writes over a project folder with edits.
 */
class OpenNewTest : ServerTestBase() {

    private val http = HttpClient.newHttpClient()
    private var port = 0

    private fun places(): JsonObject {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/projects")).header("X-Facetool-Token", server.apiToken).GET().build()
        return AndroidJson.parseObject(http.send(request, HttpResponse.BodyHandlers.ofString()).body(), "projects")
    }

    private fun design(places: JsonObject, name: String): JsonObject = (places.getValue("designs") as JsonArray).map { it as JsonObject }.first { text(it, "name") == name }

    private fun upload(name: String, bytes: ByteArray, token: String? = server.apiToken): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/upload?name=" + URLEncoder.encode(name, "UTF-8"))).POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
        token?.let { request.header("X-Facetool-Token", it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun uploaded(name: String, bytes: ByteArray): JsonObject = AndroidJson.parseObject(upload(name, bytes).body(), "upload")

    private fun flag(json: JsonObject, key: String): Boolean = text(json, key) == "true"

    @Test
    fun openNeverWritesOverAProjectWithEdits() {
        port = server.start()
        try {
            val root = File(temp, "editing")
            val first = places()
            assertThat(text(first, "root")).isEqualTo(root.path)
            assertThat((first.getValue("designs") as JsonArray).map { text(it as JsonObject, "name") }).containsExactlyElementsIn(DESIGNS)
            assertThat((first.getValue("recent") as JsonArray)).isEmpty()
            val d2 = design(first, "D2")
            assertThat(text(d2, "zip")).isEqualTo(zip("D2").path)
            assertThat(text(d2, "dir")).isEqualTo(File(root, "D2").path)
            assertThat(flag(d2, "project")).isFalse()

            // Open, as the page does: the command `open` with the zip and the folder of the list
            val opened = run("open", mapOf("zip" to d2.getValue("zip"), "dir" to d2.getValue("dir")), null)
            assertThat(flag(opened, "alreadyOpen")).isFalse()
            val project = File(root, "D2")
            run("set", mapOf("path" to JsonPrimitive("midColor"), "value" to JsonPrimitive("#123456")), project)
            val edited = File(project, "CustomWatchface.json").readText()
            assertThat(edited).contains("#123456")

            // The list now says that D2 has a project, and names a free folder for a second copy
            val again = design(places(), "D2")
            assertThat(flag(again, "project")).isTrue()
            assertThat(text(again, "freeDir")).isEqualTo(File(root, "D2_2").path)

            // The same zip into the same folder: nothing is unpacked, the edit stays
            assertThat(flag(run("open", mapOf("zip" to d2.getValue("zip"), "dir" to d2.getValue("dir")), null), "alreadyOpen")).isTrue()
            assertThat(File(project, "CustomWatchface.json").readText()).isEqualTo(edited)
            // Another zip into that folder is refused
            assertThat(answer("open", mapOf("zip" to JsonPrimitive(zip("Cyberpunk_Tech").path), "dir" to d2.getValue("dir")), null).exitCode).isEqualTo(2)
            assertThat(File(project, "CustomWatchface.json").readText()).isEqualTo(edited)

            // A new copy goes to the free folder
            run("open", mapOf("zip" to d2.getValue("zip"), "dir" to again.getValue("freeDir")), null)
            assertThat(File(root, "D2_2/CustomWatchface.json").readText()).doesNotContain("#123456")
            assertThat(File(project, "CustomWatchface.json").readText()).isEqualTo(edited)
            assertThat(text(design(places(), "D2"), "freeDir")).isEqualTo(File(root, "D2_3").path)

            // New: a second design with the same name is refused, the first stays
            val made = run("new", mapOf("name" to JsonPrimitive("My Face"), "dir" to JsonPrimitive(File(root, "My_Face").path)), null)
            assertThat(text(made, "dir")).isEqualTo(File(root, "My_Face").path)
            val madeJson = File(root, "My_Face/CustomWatchface.json").readText()
            val second = answer("new", mapOf("name" to JsonPrimitive("My Face"), "dir" to JsonPrimitive(File(root, "My_Face").path)), null)
            assertThat(second.exitCode).isEqualTo(2)
            assertThat(text(second.json, "text")).contains("is not empty")
            assertThat(File(root, "My_Face/CustomWatchface.json").readText()).isEqualTo(madeJson)
        } finally {
            server.stop()
        }
    }

    @Test
    fun aDroppedZipIsKeptFirstAndNeverWrittenOver() {
        port = server.start()
        try {
            val root = File(temp, "editing")
            val d2 = zip("D2").readBytes()
            assertThat(upload("Dropped.zip", d2, token = null).statusCode()).isEqualTo(403)
            assertThat(File(root, "Dropped.zip").exists()).isFalse()

            val place = uploaded("Dropped.zip", d2)
            assertThat(text(place, "zip")).isEqualTo(File(root, "Dropped.zip").path)
            assertThat(File(root, "Dropped.zip").readBytes()).isEqualTo(d2)
            assertThat(text(place, "dir")).isEqualTo(File(root, "Dropped").path)
            assertThat(flag(place, "project")).isFalse()
            run("open", mapOf("zip" to place.getValue("zip"), "dir" to place.getValue("dir")), null)

            // The same zip again: kept once, and the answer says that its project is there
            val same = uploaded("Dropped.zip", d2)
            assertThat(text(same, "zip")).isEqualTo(File(root, "Dropped.zip").path)
            assertThat(flag(same, "project")).isTrue()

            // Another zip with the same name gets a new name
            val other = uploaded("Dropped.zip", zip("Cyberpunk_Tech").readBytes())
            assertThat(text(other, "zip")).isEqualTo(File(root, "Dropped_2.zip").path)
            assertThat(text(other, "dir")).isEqualTo(File(root, "Dropped_2").path)
            assertThat(flag(other, "project")).isFalse()
            assertThat(File(root, "Dropped.zip").readBytes()).isEqualTo(d2)

            // A name with a path or odd letters is made plain; a file that is no design zip is refused
            assertThat(text(uploaded("../../My design (2).zip", d2), "zip")).isEqualTo(File(root, "My_design_2.zip").path)
            val junk = uploaded("Junk.zip", "not a zip".toByteArray())
            assertThat(junk).containsKey("error")
            assertThat(File(root, "Junk.zip").exists()).isFalse()
        } finally {
            server.stop()
        }
    }

    @Test
    fun aProjectThePageShowsIsARecentProject() {
        val project = open("D2")
        port = server.start()
        try {
            // The page listens to the events of its project
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/events?token=${server.apiToken}&project=" + URLEncoder.encode(project.path, "UTF-8"))).GET().build()
            val stream = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            stream.body().use { it.read() }
            val recent = (places().getValue("recent") as JsonArray).map { it as JsonObject }
            assertThat(recent.map { text(it, "dir") }).containsExactly(project.canonicalPath)
            assertThat(recent.map { text(it, "name") }).containsExactly("D2")
            assertThat(File(temp, "cache/recent.json").isFile).isTrue()
            // A folder that is gone is left out
            project.deleteRecursively()
            assertThat((places().getValue("recent") as JsonArray)).isEmpty()
        } finally {
            server.stop()
        }
    }
}
