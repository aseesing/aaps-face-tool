package app.aaps.facetool.editor.server

import android.app.Application
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.facetool.editor.model.CommandAnswer
import app.aaps.facetool.snapshot.SnapshotClock
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPausedSystemClock
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The base of the server tests: a server without the render loop, whose commands draw on the test
 * thread, and a temp folder for project folders. The tests need the design zips (`designsDir`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, instrumentedPackages = ["app.aaps.wear"], qualifiers = "xhdpi")
abstract class ServerTestBase {

    protected val clock = SnapshotClock()
    protected val engineClock = EngineClock(clock) { ShadowPausedSystemClock.reset() }
    protected val app: Application get() = RuntimeEnvironment.getApplication()
    protected val logger: AAPSLogger = mock()
    protected lateinit var temp: File
    protected lateinit var server: EditorServer

    @Before
    fun startServer() {
        assumeTrue("Needs the design zips: set designsDir in local.properties", System.getProperty("wff.zips") != null)
        temp = Files.createTempDirectory("facetool-server-test").toFile()
        clock.start()
        server = EditorServer(
            app, logger, engineClock, checkout = System.getProperty("editor.checkout"), aapsDir = System.getProperty("aaps.dir")?.let { File(it) },
            editingRoot = File(temp, "editing"), cacheRoot = File(temp, "cache"), designsDir = File(System.getProperty("wff.zips")), renderThread = RenderThread(direct = true)
        )
    }

    @After
    fun stopServer() {
        clock.stop()
        if (::temp.isInitialized) temp.deleteRecursively()
    }

    protected fun zip(design: String): File = File(System.getProperty("wff.zips"), "$design.zip")

    /** Opens the zip of [design] as a project folder in the temp folder. */
    protected fun open(design: String): File {
        val dir = File(temp, "projects/$design")
        run("open", mapOf("zip" to JsonPrimitive(zip(design).path), "dir" to JsonPrimitive(dir.path)), null)
        return dir
    }

    /** Runs a command that must end with exit code 0. */
    protected fun run(command: String, args: Map<String, JsonElement>, project: File?): JsonObject {
        val answer = answer(command, args, project)
        assertWithMessage("%s %s: %s", command, args, answer.json).that(answer.exitCode).isEqualTo(0)
        return answer.json
    }

    protected fun answer(command: String, args: Map<String, JsonElement>, project: File?): CommandAnswer = server.commands.run(command, JsonObject(args), project)

    protected fun text(json: JsonObject, key: String): String = (json.getValue(key) as JsonPrimitive).content

    protected fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    protected companion object {

        /** The 12 designs of the designs repository. */
        val DESIGNS = listOf(
            "Aurora_Town", "Aurora_Town_mgdl_AAPS4", "Aurora_Town_mmol_AAPS4", "Cyberpunk_Tech", "Cyberpunk_Weather", "Cyberpunk_Yellow", "D2",
            "Grand_Complication_Day", "Grand_Complication_Day_AAPS4", "Grand_Complication_Night", "Grand_Complication_Night_AAPS4", "Wilds_Weather"
        )
    }
}
