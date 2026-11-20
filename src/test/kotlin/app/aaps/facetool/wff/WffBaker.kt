package app.aaps.facetool.wff

import android.graphics.Bitmap
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.facetool.snapshot.SnapshotClock
import app.aaps.facetool.snapshot.SnapshotData
import app.aaps.facetool.snapshot.SnapshotEngine
import java.io.File

/**
 * Draws designs with the watch's own engine for the WFF harness: the face at rest and frames of the
 * wake-up animation, at [size] pixels. The clock is frozen and only goes forward, so each picture is
 * drawn a little later than the one before.
 */
class WffBaker(private val engine: SnapshotEngine, private val clock: SnapshotClock, val size: Int, private val data: SnapshotData = SnapshotData(weather = "clear")) {

    private var faces = 0

    /** The time of the baker's clock. */
    val now: Long get() = clock.now

    /** Moves the clock forward to [epochMs], for a layer that shows only on some days. */
    fun moveTo(epochMs: Long) {
        if (epochMs > clock.now) clock.setTo(epochMs)
    }

    private fun newFace(): SnapshotEngine.Face = engine.Face("wff${faces++}").apply {
        settingsAtDefaults()
        feed(data, clock.now)
    }

    /** [design] at rest: shown, and drawn when its wake-up animation is over. */
    fun rest(design: CwfData): Bitmap {
        val face = newFace()
        try {
            face.show(SnapshotEngine.Design("rest", design))
            face.frame(size, clock.now).recycle()
            clock.setTo(clock.now + REST_LEAD_MS)
            return face.frame(size, clock.now).also { check(!face.isAnimating) { "The wake-up animation still plays after ${REST_LEAD_MS / 1000} s" } }
        } finally {
            face.release()
        }
    }

    /**
     * [design] at each of [atMs] after its wake starts (rising), see-through where it draws nothing; null
     * for a moment after the animation ended.
     *
     * A wake frame of the engine always has a background, so each frame is drawn twice, on a black and
     * on a white background picture, and the converter makes one see-through frame of the two
     * ([WffPictures.matte]). With [onBlack] the frames stay on black ([WffPictures.lightBlend]); with
     * [keepBackground] the design keeps its own background and is drawn once.
     */
    fun wake(design: CwfData, atMs: List<Long>, onBlack: Boolean = false, keepBackground: Boolean = false): List<Bitmap?> {
        // A design with its own background is opaque anyway
        if (keepBackground) return wakeOn(design, atMs)
        val black = wakeOn(withBackground(design, WffPictures.BLACK_PNG), atMs)
        // Light that adds to what lies below has no transparency: it stays on black
        if (onBlack) return black
        val white = wakeOn(withBackground(design, WffPictures.WHITE_PNG), atMs)
        return black.indices.map { i ->
            val b = black[i]
            val w = white[i]
            if (b == null || w == null) null.also { b?.recycle(); w?.recycle() }
            else WffPictures.bitmapOf(WffPictures.matte(WffPictures.pixelsOf(b), WffPictures.pixelsOf(w))).also { b.recycle(); w.recycle() }
        }
    }

    /** [design] with a background picture of one pixel [png], drawn over the whole face. */
    private fun withBackground(design: CwfData, png: ByteArray): CwfData =
        CwfData(design.json, design.metadata, design.resData.toMutableMap().apply { put("Background", ResData(png, ResFormat.PNG)) })

    private fun wakeOn(design: CwfData, atMs: List<Long>): List<Bitmap?> {
        val face = newFace()
        try {
            clock.setTo(clock.now + 1000)
            val start = clock.now
            face.show(SnapshotEngine.Design("wake", design))
            // The wake starts with the first frame drawn: draw it at the start, not at the first moment asked for (see WffLoops)
            if (atMs.firstOrNull() != 0L) face.frame(size, clock.now).recycle()
            return atMs.map { ms ->
                clock.setTo(start + ms)
                val frame = face.frame(size, clock.now)
                if (face.isAnimating) frame else null.also { frame.recycle() }
            }
        } finally {
            clock.setTo(clock.now + REST_LEAD_MS)
            face.release()
        }
    }

    /** The values and the design of [design] as the watch app stores them, for the emulator, with the face of now as `reference.png`. */
    fun scene(design: CwfData, out: File, filesDir: File, preferences: (String) -> Map<String, *>) {
        val face = engine.Face("scene")
        try {
            face.settingsAtDefaults()
            face.feed(data, clock.now)
            face.show(SnapshotEngine.Design("scene", design))
            face.frame(size, clock.now).recycle()
            // The stores, while the face still holds them
            File(filesDir, "scene/datastore").listFiles()?.forEach { it.copyTo(File(out, "datastore/${it.name}"), overwrite = true) }
            File(out, "shared_prefs").mkdirs()
            File(out, "shared_prefs/watch_settings.xml").writeText(preferencesXml(preferences("scene_watch_settings")))
            File(out, "shared_prefs/aaps_health_values.xml").writeText(preferencesXml(aheadOfSensors(preferences("scene_aaps_health_values"))))
            // The battery of the watch is no store of the app: the emulator driver sets it (dumpsys battery)
            data.watchBattery?.let { File(out, WATCH_BATTERY_FILE).writeText("$it\n") }
            clock.setTo(clock.now + REST_LEAD_MS)
            face.frame(size, clock.now).savePng(File(out, "reference.png"))
        } finally {
            face.release()
        }
    }

    /**
     * The heart rate and the steps of the scene for the emulator, with their times a few minutes after
     * the scene. On the emulator the wear app also gets the emulator's own heart rate and steps (Health
     * Services), as soon as a face shows the AAPS heart rate or steps, and it keeps a value only when it
     * is newer than the one it has (`HealthValues`). With these times it keeps the values of the scene
     * while the face is shown. The values do not change, so the face shows what our engine shows.
     */
    private fun aheadOfSensors(values: Map<String, *>): Map<String, *> =
        values.mapValues { (name, value) -> if (name in HEALTH_TIMES && value is Long) clock.now + SENSORS_GUARD_MS else value }

    /** A preferences map as Android writes its xml file. */
    private fun preferencesXml(values: Map<String, *>): String = buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
        for ((name, value) in values.toSortedMap()) {
            val key = xml(name)
            when (value) {
                is String  -> append("    <string name=\"$key\">${xml(value)}</string>\n")
                is Boolean -> append("    <boolean name=\"$key\" value=\"$value\" />\n")
                is Int     -> append("    <int name=\"$key\" value=\"$value\" />\n")
                is Long    -> append("    <long name=\"$key\" value=\"$value\" />\n")
                is Float   -> append("    <float name=\"$key\" value=\"$value\" />\n")
                else       -> Unit
            }
        }
        append("</map>\n")
    }

    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {

        /** The file of the scene with the battery level of the watch in percent, for the emulator driver. */
        const val WATCH_BATTERY_FILE = "watch_battery.txt"

        /** The times of the heart rate and the steps in the store of the wear app (`HealthValues`). */
        private val HEALTH_TIMES = setOf("heart_rate_at", "steps_at")

        /**
         * How far after the scene the times of the heart rate and the steps lie on the emulator: more
         * than the seconds a face is shown, less than the 5 minutes that `HealthValues` allows a time to
         * lie ahead of the clock (a time further ahead counts as none).
         */
        private const val SENSORS_GUARD_MS = 4 * 60_000L

        /** Longer than the longest wake-up animation the engine allows (30 s). */
        const val REST_LEAD_MS = 31_000L

        /** The part [box] of [bitmap]. */
        fun crop(bitmap: Bitmap, box: WffPictures.Box): Bitmap = Bitmap.createBitmap(bitmap, box.left, box.top, box.width, box.height)
    }
}

/** For pictures that are not a part of the face, such as `reference.png` of the scene. The face's pictures are written by [WffPictures.encode]. */
fun Bitmap.savePng(file: File) {
    file.parentFile?.mkdirs()
    file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
}
