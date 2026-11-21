package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.AndroidJson
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A save in the server draws the preview picture of the design (the picture the phone shows in its list)
 * new from the face at rest, in the format the design has. `--keep-preview` leaves it as it is.
 */
class PreviewPictureTest : ServerTestBase() {

    /** How many of the pixels of [image] are not near black, in steps of 8 pixels. */
    private fun lit(image: BufferedImage): Int {
        var n = 0
        for (y in 0 until image.height step 8) for (x in 0 until image.width step 8) {
            val rgb = image.getRGB(x, y)
            if ((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF) > 60) n++
        }
        return n
    }

    @Test
    fun aNewDesignGetsAPictureOfItsFace() {
        val project = File(temp, "editing/Preview_Test")
        run("new", mapOf("name" to JsonPrimitive("Preview Test"), "dir" to JsonPrimitive(project.path)), null)
        val preview = File(project, "CustomWatchface.png")
        // A new design starts with a plain black picture
        assertThat(lit(ImageIO.read(preview))).isEqualTo(0)
        // A large clock text that stays at rest
        run(
            "layer add",
            mapOf("json" to AndroidJson.parse("""{"text": "clock()", "fontSize": 120, "x": 40, "y": 260, "color": "#FFFFFF", "rest": 0}""")),
            project
        )
        val out = File(temp, "Preview_Test.zip")
        val saved = run("save", mapOf("out" to JsonPrimitive(out.path)), project)
        assertThat(text(saved, "preview")).isEqualTo("CustomWatchface.png")
        assertThat(text(saved, "text")).contains("Drew the preview picture CustomWatchface.png")
        val drawn = ImageIO.read(preview)
        assertThat(drawn.width).isEqualTo(400)
        assertThat(drawn.height).isEqualTo(400)
        assertThat(lit(drawn)).isGreaterThan(100)

        // Kept as it is with --keep-preview
        preview.writeBytes(File(temp, "black.png").also { ImageIO.write(BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB), "png", it) }.readBytes())
        val kept = run("save", mapOf("out" to JsonPrimitive(out.path), "keep-preview" to JsonPrimitive(true)), project)
        assertThat(kept.containsKey("preview")).isFalse()
        assertThat(lit(ImageIO.read(preview))).isEqualTo(0)
    }

    @Test
    fun aDesignWithAJpegKeepsItsFormat() {
        val project = open("D2")
        val preview = File(project, "CustomWatchface.jpg")
        val before = preview.readBytes()
        val saved = run("save", mapOf("out" to JsonPrimitive(File(temp, "D2_copy.zip").path)), project)
        assertThat(text(saved, "preview")).isEqualTo("CustomWatchface.jpg")
        assertThat(File(project, "CustomWatchface.png").exists()).isFalse()
        val drawn = ImageIO.read(preview)
        assertThat(drawn.width).isEqualTo(400)
        assertThat(lit(drawn)).isGreaterThan(100)
        assertThat(preview.readBytes().contentEquals(before)).isFalse()
        // Undo takes the new picture back: it has its own history entry
        run("undo", emptyMap(), project)
        assertThat(preview.readBytes().contentEquals(before)).isTrue()
    }
}
