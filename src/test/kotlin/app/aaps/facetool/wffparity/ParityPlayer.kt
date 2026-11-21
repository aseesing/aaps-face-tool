package app.aaps.facetool.wffparity

import android.app.Application
import android.graphics.Bitmap
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.facetool.player.WffPlayer
import app.aaps.facetool.player.WffScene
import app.aaps.facetool.player.WffSlots
import app.aaps.facetool.snapshot.SnapshotClock
import app.aaps.facetool.snapshot.SnapshotData
import app.aaps.facetool.snapshot.SnapshotEngine
import app.aaps.facetool.wff.WffBaker
import app.aaps.facetool.wff.WffFaceWriter
import app.aaps.facetool.wff.savePng
import app.aaps.wear.watchfaces.wff.WffDesignParts
import org.robolectric.shadows.ShadowPausedSystemClock
import java.io.File
import java.time.ZoneId

/**
 * The WFF player in the parity suite (`docs/face-editor-design.md`, 12.5 to 12.7): draws the face a
 * test converted with the player, at the moment and with the values of the scene, as `player.png`
 * next to `reference.png` (our engine) and `emulator.png`.
 */
class ParityPlayer(private val app: Application, private val engine: SnapshotEngine, private val clock: SnapshotClock) {

    /**
     * Writes `player.png` into [dir] (the folder of the test, with its `face` folder). The scene is
     * the one of `reference.png`: fed at [fedAtMs], drawn [WffBaker.REST_LEAD_MS] later.
     *
     * @return the lines of what the player could not show
     */
    fun draw(dir: File, design: CwfData, data: SnapshotData, fedAtMs: Long): List<String> {
        val face = File(dir, "face")
        val player = WffPlayer(face, WffFaceWriter.VERSION_4)
        val dataPicture = WffDesignParts.dataOf(design)?.let { dataPicture(it, data, fedAtMs, player.width) }
        val slots = WffSlots(app).of(face, design, data, dataPicture)
        val scene = WffScene(
            timeMs = fedAtMs + WffBaker.REST_LEAD_MS, zone = ZoneId.of(SnapshotClock.ZONE),
            // Long after the screen came on: the wake frames are over, as in the picture at rest of the emulator
            visibleSec = 3600.0, heartRate = data.heartRate, steps = data.steps, batteryPercent = data.watchBattery ?: 100, slots = slots
        )
        val picture = player.draw(scene)
        picture.savePng(File(dir, "player.png"))
        picture.recycle()
        dataPicture?.recycle()
        return player.problems
    }

    /**
     * The data picture as the wear app draws it on the watch: our engine draws the data parts of the
     * design on a clear background. The clock of the test has moved on while the face was baked, so it
     * starts again here (it only goes forward) and the picture is drawn at the moment of the scene.
     */
    private fun dataPicture(design: CwfData, data: SnapshotData, fedAtMs: Long, size: Int): Bitmap {
        ShadowPausedSystemClock.reset()
        clock.setTo(fedAtMs)
        val face = engine.Face("playerdata")
        try {
            face.settingsAtDefaults()
            face.feed(data, clock.now)
            face.show(SnapshotEngine.Design("playerdata", design))
            face.frame(size, clock.now).recycle()
            clock.setTo(fedAtMs + WffBaker.REST_LEAD_MS)
            return face.frame(size, clock.now)
        } finally {
            face.release()
        }
    }
}
