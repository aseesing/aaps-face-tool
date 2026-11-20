package app.aaps.watchfaces.wff

import android.app.Application
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.watchfaces.snapshot.SnapshotClock
import app.aaps.watchfaces.snapshot.SnapshotEngine
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Makes the Watch Face Format face APK of one design zip, and the files to try it on an emulator with
 * data. See `docs/wff-apk.md`. Runs only when asked for:
 *
 * `./gradlew testDebugUnitTest -PwffOut=build/wff` (every zip of `cwf-designs`, one test JVM per zip)
 *
 * Settings (Gradle properties): `wffZips` the folder with the zips (default `cwf-designs`), `wffOut`
 * where the output goes (one folder per design), `wffClient` the application id of the wear app
 * (default `info.nightscout.androidaps`), `wffOnly` one zip name.
 *
 * Per design: `lint.txt`, the folder `face` that Pack reads, `face.apk`, `validator.txt`, `token.txt`
 * when the face passed, and `scene`: the stores of the watch app with fixed values of now, and
 * `reference.png`, the face as our own engine draws it with those values.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The engine is instrumented so that its System.currentTimeMillis() reads the frozen clock, see SnapshotClock
// xhdpi: the density of a round Wear OS watch, so the graph has the line widths of the watch
@Config(sdk = [35], application = Application::class, instrumentedPackages = ["app.aaps.wear"], qualifiers = "xhdpi")
class WffApkTest {

    private val clock = SnapshotClock()
    private val realNow = System.currentTimeMillis()

    @Before
    fun setUp() = clock.start()

    @After
    fun tearDown() = clock.stop()

    @Test
    fun build() {
        val only = System.getProperty("wff.only")
        assumeTrue("Runs only with -PwffOut=..., see the class doc", System.getProperty("wff.out") != null && only != null && only != "-")
        val zip = File(System.getProperty("wff.zips") ?: "cwf-designs", only!!)
        val dir = File(System.getProperty("wff.out"), zip.nameWithoutExtension)
        val app = RuntimeEnvironment.getApplication()
        // The clock starts at the real time, so the values of the scene are fresh on the emulator
        clock.setTo(realNow)
        val cache = File(System.getProperty("user.home"), ".cache/aaps-wff")
        val pack = File(cache, "pack/target/release/pack-cli").takeIf { it.isFile } ?: File(cache, "pack/target/release/pack-cli.exe")
        check(pack.isFile) { "Pack is not built: run tools/build-pack.sh (docs/wff-apk.md)" }
        val baker = WffBaker(SnapshotEngine(app), clock, SIZE)
        val builder = WffApkBuilder(app, baker, System.getProperty("wff.client") ?: "info.nightscout.androidaps", pack, File(cache, "dev-key.pem"))
        // The scene first, while the clock is still at the real time: baking moves the clock on
        val design = ZipWatchfaceFormat.loadCustomWatchface(zip.readBytes(), zip.name, authorization = true)?.cwfData ?: error("${zip.name} is not a design zip")
        val scene = File(app.cacheDir, "scene")
        baker.scene(design, scene, app.filesDir) { name -> app.getSharedPreferences(name, 0).all }
        val result = builder.build(zip, dir)
        println(result.lint)
        scene.copyRecursively(File(dir, "scene"), overwrite = true)
        File(dir, "result.txt").writeText(if (result.token != null) "valid, ${result.apk!!.length() / 1024} KB" else if (result.apk != null) "built, NOT valid" else "not built")
        check(result.token != null) { "${zip.name}: no valid face, see ${File(dir, "lint.txt")} and validator.txt" }
    }

    private companion object {

        /** The pictures are baked at the size of the watch's screen. */
        const val SIZE = 450
    }
}
