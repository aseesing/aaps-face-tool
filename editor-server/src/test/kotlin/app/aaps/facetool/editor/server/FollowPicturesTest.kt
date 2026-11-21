package app.aaps.facetool.editor.server

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The page follows the pictures that another client (an AI agent) makes of its project. */
class FollowPicturesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sent = mutableListOf<Triple<String, JsonObject, File>>()
    private val follow = FollowPictures { name, data, dir -> sent += Triple(name, data, dir) }

    private fun files(data: JsonObject): List<File> = (data.getValue("files") as JsonArray).map { File((it as JsonPrimitive).content) }

    @Test
    fun aPictureAnywhereIsCopiedUnderTheProjectAndSent() {
        val project = temp.newFolder("project")
        val outside = File(temp.newFolder("pics"), "high.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        follow.seen("render", "facetool render --set sgv=250", project, buildJsonObject { put("file", outside.path) })
        val (name, data, dir) = sent.single()
        assertThat(name).isEqualTo("picture")
        assertThat(dir).isEqualTo(project)
        assertThat((data.getValue("line") as JsonPrimitive).content).isEqualTo("facetool render --set sgv=250")
        val copy = files(data).single()
        // Under .facetool/out, which is the only place the page may read
        assertThat(copy.path).contains("/.facetool/out/follow/")
        assertThat(copy.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun aGifComesFirstThenThePictureThenTheFrames() {
        val project = temp.newFolder("project")
        val pics = temp.newFolder("pics")
        val gif = File(pics, "wake.gif").apply { writeText("gif") }
        val sheet = File(pics, "sheet.png").apply { writeText("png") }
        val frames = (0 until 3).map { File(pics, "f$it.png").apply { writeText("frame $it") } }
        follow.seen("wake", "facetool wake --gif x", project, buildJsonObject { put("gif", gif.path); put("file", sheet.path) })
        assertThat(files(sent.last().second).single().readText()).isEqualTo("gif")
        follow.seen("wake", "facetool wake --frames x", project, buildJsonObject {
            put("frames", buildJsonArray { frames.forEach { add(JsonPrimitive(it.path)) } })
            put("frameMs", 83)
        })
        val data = sent.last().second
        assertThat(files(data).map { it.readText() }).containsExactly("frame 0", "frame 1", "frame 2").inOrder()
        assertThat((data.getValue("frameMs") as JsonPrimitive).content).isEqualTo("83")
        // Only the newest picture is kept
        assertThat(File(project, ".facetool/out/follow").listFiles()!!.size).isEqualTo(3)
    }

    @Test
    fun noEventForOtherCommandsErrorsOrNoProject() {
        val project = temp.newFolder("project")
        val picture = File(temp.root, "p.png").apply { writeText("png") }
        follow.seen("set", "facetool set x 1", project, buildJsonObject { put("file", picture.path) })
        follow.seen("render", "facetool render", project, buildJsonObject { put("file", picture.path); put("error", "broken") })
        follow.seen("render", "facetool render", null, buildJsonObject { put("file", picture.path) })
        follow.seen("render", "facetool render", project, buildJsonObject { put("file", File(temp.root, "missing.png").path) })
        assertThat(sent).isEmpty()
    }
}
