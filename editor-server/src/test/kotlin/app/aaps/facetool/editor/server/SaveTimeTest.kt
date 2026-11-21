package app.aaps.facetool.editor.server

import android.graphics.Bitmap
import app.aaps.facetool.editor.model.DesignZip
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * A change and a save in the server record the real time, not the frozen time of the engine's clock.
 *
 * The engine's clock (`SnapshotClock`) replaces `Clock.systemUTC()` on the server's thread, so the
 * history and the zip of a save made in the server had the time of the last picture (2026-11-17 09:08).
 */
class SaveTimeTest : ServerTestBase() {

    @Test
    fun aSaveInTheServerRecordsTheRealTime() {
        val project = open("D2")
        // A picture first: it moves the engine's clock to the time of the scene, 17 Nov 2026 10:08
        run("render", emptyMap(), project)
        assertThat(engineClock.now).isLessThan(Instant.parse("2026-11-17T12:00:00Z").toEpochMilli())

        // This class is not instrumented by Robolectric, so this is the time of the computer
        val before = System.currentTimeMillis()
        run("set", mapOf("path" to JsonPrimitive("metadata.comment"), "value" to JsonPrimitive("a test")), project)
        // A new file: a save gives it the time of the save (a file of the source zip keeps the date it had there)
        val star = File(temp, "SaveTimeStar.png").apply { outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        run("res add", mapOf("file" to JsonPrimitive(star.path)), project)
        val zip = File(temp, "saved.zip")
        run("save", mapOf("out" to JsonPrimitive(zip.path), "keep-preview" to JsonPrimitive(true)), project)
        val after = System.currentTimeMillis()

        // The history entries of the change and of the save
        val entries = (run("history", emptyMap(), project).getValue("entries") as JsonArray).map { it as JsonObject }
        assertThat(entries.size).isAtLeast(3)
        for (entry in entries) {
            val time = Instant.parse(text(entry, "time")).toEpochMilli()
            assertThat(time).isAtLeast(before - 1000)
            assertThat(time).isAtMost(after + 1000)
        }

        // The date of the new file in the zip, which is a local time (a zip keeps it to 2 seconds)
        val date = DesignZip.read(zip.readBytes()).single { it.name.startsWith("SaveTimeStar") }.time!!
        val time = date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertThat(time).isAtLeast(before - 3000)
        assertThat(time).isAtMost(after + 3000)
    }
}
