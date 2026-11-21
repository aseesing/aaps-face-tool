package app.aaps.facetool.golden

import app.aaps.core.interfaces.logging.AAPSLogger
import org.mockito.kotlin.mock
import android.app.Application
import app.aaps.facetool.snapshot.SnapshotClock
import app.aaps.facetool.snapshot.SnapshotData
import app.aaps.facetool.snapshot.SnapshotEngine
import app.aaps.facetool.wff.WffApkBuilder
import app.aaps.facetool.wff.WffBaker
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import com.google.common.truth.Truth.assertWithMessage
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
import java.time.LocalDateTime

/**
 * The face folder of one design zip of the designs repository, made at a fixed moment and checked
 * against its golden hash list ([FaceFolderGolden]). One class per design, so each design is made in
 * a test JVM of its own, as the real faces are.
 *
 * Runs only in the task `wffGoldenTest`; `-PwffGoldenWrite` writes the lists new.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, instrumentedPackages = ["app.aaps.wear"], qualifiers = "xhdpi")
abstract class DesignFaceGoldenBase(private val design: String) {

    private val clock = SnapshotClock()

    @Before
    fun startClock() {
        assumeTrue("Runs only in the task wffGoldenTest", System.getProperty("wff.golden") == "true")
        clock.start()
    }

    @After
    fun stopClock() = clock.stop()

    @Test
    fun faceFolder() {
        val zip = File(System.getProperty("wff.zips") ?: error("Set designsDir in local.properties"), "$design.zip")
        val dir = File("build/wff-golden/$design")
        val app = RuntimeEnvironment.getApplication()
        clock.setTo(SnapshotClock.epochMs(MOMENT))
        val cache = File(System.getProperty("user.home"), ".cache/aaps-wff")
        val baker = WffBaker(SnapshotEngine(app, mock<AAPSLogger>()), clock, SIZE, SnapshotData(weather = "clear", watchBattery = 64))
        val cwf = ZipWatchfaceFormat.loadCustomWatchface(zip.readBytes(), zip.name, authorization = true)?.cwfData ?: error("${zip.name} is not a design zip")
        // Only the face folder: no Pack and no validator
        val result = WffApkBuilder(app, baker, CLIENT, File(cache, "pack/target/release/pack-cli"), File(cache, "dev-key.pem")).convert(cwf, dir)
        assertWithMessage("%s has no face folder:\n%s", design, result.lint).that(File(dir, "face/res/raw/watchface.xml").isFile).isTrue()
        FaceFolderGolden.ofRepository().check("designs/$design", File(dir, "face"))
    }

    private companion object {

        /** The moment of the parity suite. */
        val MOMENT: LocalDateTime = LocalDateTime.of(2026, 11, 17, 10, 8)
        const val SIZE = 450
        const val CLIENT = "info.nightscout.androidaps"
    }
}

class GoldenAuroraTownTest : DesignFaceGoldenBase("Aurora_Town")
class GoldenAuroraTownMgdlAaps4Test : DesignFaceGoldenBase("Aurora_Town_mgdl_AAPS4")
class GoldenAuroraTownMmolAaps4Test : DesignFaceGoldenBase("Aurora_Town_mmol_AAPS4")
class GoldenCyberpunkTechTest : DesignFaceGoldenBase("Cyberpunk_Tech")
class GoldenCyberpunkWeatherTest : DesignFaceGoldenBase("Cyberpunk_Weather")
class GoldenCyberpunkYellowTest : DesignFaceGoldenBase("Cyberpunk_Yellow")
class GoldenD2Test : DesignFaceGoldenBase("D2")
class GoldenGrandComplicationDayTest : DesignFaceGoldenBase("Grand_Complication_Day")
class GoldenGrandComplicationDayAaps4Test : DesignFaceGoldenBase("Grand_Complication_Day_AAPS4")
class GoldenGrandComplicationNightTest : DesignFaceGoldenBase("Grand_Complication_Night")
class GoldenGrandComplicationNightAaps4Test : DesignFaceGoldenBase("Grand_Complication_Night_AAPS4")
class GoldenWildsWeatherTest : DesignFaceGoldenBase("Wilds_Weather")
