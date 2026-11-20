package app.aaps.facetool.wff

import android.app.Application
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.facetool.snapshot.SnapshotClock
import app.aaps.facetool.snapshot.SnapshotEngine
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
 * `./gradlew testDebugUnitTest -PwffOut=build/wff` (every zip of the designs repository, one test JVM per zip)
 *
 * Settings (Gradle properties): `wffZips` the folder with the zips (default `cwf-designs` of `designsDir`), `wffOut`
 * where the output goes (one folder per design), `wffClient` the application id of one wear app
 * (default: all of [WffFaceZip.APP_IDS]), `wffOnly` one zip name.
 *
 * Per design: the face zip `<design>-wff.zip` in `wffOut` ([WffFaceZip]), and in the design's folder
 * `lint.txt`, the folder `face` that Pack reads, `face.apk` (WFF 4, the first wear app),
 * `validator.txt`, `token.txt` when the face passed, `faces/<app id>/` with the other faces, and
 * `scene`: the stores of the watch app with fixed values of now, and `reference.png`, the face as our
 * own engine draws it with those values.
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
        val zip = File(System.getProperty("wff.zips") ?: error("Set designsDir in local.properties, or give -PwffZips=..."), only!!)
        val dir = File(System.getProperty("wff.out"), zip.nameWithoutExtension)
        val app = RuntimeEnvironment.getApplication()
        // The clock starts at the real time, so the values of the scene are fresh on the emulator
        clock.setTo(realNow)
        val cache = File(System.getProperty("user.home"), ".cache/aaps-wff")
        val pack = File(cache, "pack/target/release/pack-cli").takeIf { it.isFile } ?: File(cache, "pack/target/release/pack-cli.exe")
        check(pack.isFile) { "Pack is not built: run tools/build-pack.sh (docs/wff-apk.md)" }
        val baker = WffBaker(SnapshotEngine(app), clock, SIZE)
        val appIds = System.getProperty("wff.client")?.let { listOf(it) } ?: WffFaceZip.APP_IDS
        val builder = WffApkBuilder(app, baker, appIds.first(), pack, File(cache, "dev-key.pem"))
        // The scene first, while the clock is still at the real time: baking moves the clock on
        val design = ZipWatchfaceFormat.loadCustomWatchface(zip.readBytes(), zip.name, authorization = true)?.cwfData ?: error("${zip.name} is not a design zip")
        val scene = File(app.cacheDir, "scene")
        baker.scene(design, scene, app.filesDir) { name -> app.getSharedPreferences(name, 0).all }
        val faceZip = File(dir.parentFile, WffFaceZip.nameOf(zip.name))
        faceZip.delete()
        val result = builder.build(zip, dir, WffApkBuilder.FaceZip(appIds, faceZip))
        println(result.lint)
        scene.copyRecursively(File(dir, "scene"), overwrite = true)
        File(dir, "result.txt").writeText(if (result.token != null) "valid, ${result.apk!!.length() / 1024} KB" else if (result.apk != null) "built, NOT valid" else "not built")
        check(result.token != null) { "${zip.name}: no valid face, see ${File(dir, "lint.txt")} and validator.txt" }
        check(result.faceZip != null) { "${zip.name}: the face zip misses a face or its data.zip, see ${File(dir, "lint.txt")}" }
    }

    private companion object {

        /** The pictures are baked at the size of the watch's screen. */
        const val SIZE = 450
    }
}
