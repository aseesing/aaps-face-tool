package app.aaps.facetool.editor.server

import android.app.Application
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.facetool.snapshot.SnapshotClock
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPausedSystemClock
import java.io.File
import java.lang.management.ManagementFactory

/**
 * The render server of the face editor (design 4.3): a Robolectric test that does not end. The engine
 * needs Android's graphics, and Robolectric gives them on the JVM only inside a test. It runs only
 * with `-Deditor.serve=true`; facetool starts it with `JUnitCore` (see the build file).
 *
 * System properties: `editor.checkout` (the `aaps-face-tool` checkout, as the CLI names it),
 * `aaps.dir` (the AAPS checkout), `editor.cache` (default `~/.cache/aaps-face-editor`),
 * `editor.editing` (the default place of project folders).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// As the parity tests: the engine is instrumented so that its clock is the frozen one; xhdpi is the density of a round watch
@Config(sdk = [35], application = Application::class, instrumentedPackages = ["app.aaps.wear"], qualifiers = "xhdpi")
class EditorServerMain {

    @Test
    fun serve() {
        assumeTrue("Runs only as the editor server, with -Deditor.serve=true", System.getProperty("editor.serve") == "true")
        val home = System.getProperty("user.home")
        val clock = SnapshotClock()
        clock.start()
        try {
            EditorServer(
                app = RuntimeEnvironment.getApplication(), logger = mock<AAPSLogger>(), clock = EngineClock(clock) { ShadowPausedSystemClock.reset() },
                checkout = System.getProperty("editor.checkout"), aapsDir = System.getProperty("aaps.dir")?.let { File(it) },
                editingRoot = File(System.getProperty("editor.editing") ?: "$home/Projects/aaps-watchfaces/editing"),
                cacheRoot = File(System.getProperty("editor.cache") ?: "$home/.cache/aaps-face-editor")
            ).serve(ManagementFactory.getRuntimeMXBean().startTime)
        } finally {
            clock.stop()
        }
    }
}
