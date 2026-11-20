package app.aaps.watchfaces.wff

import android.graphics.Bitmap
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.watchfaces.snapshot.SnapshotClock
import app.aaps.watchfaces.snapshot.SnapshotData
import app.aaps.watchfaces.snapshot.SnapshotEngine
import app.aaps.wear.watchfaces.animation.DesignRect
import java.io.File
import java.util.Base64

/**
 * Draws designs with the watch's own engine for the WFF harness: the face at rest and frames of the
 * wake-up animation, at [size] pixels. The clock is frozen and only goes forward, so each picture is
 * drawn a little later than the one before.
 */
class WffBaker(private val engine: SnapshotEngine, private val clock: SnapshotClock, val size: Int, private val data: SnapshotData = SnapshotData(weather = "clear")) {

    private var faces = 0

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
     * A wake frame of the engine always has a background, so each frame is drawn twice: on a black and
     * on a white background picture. Where the two differ, the frame lets the background through; how
     * much they differ is how see-through it is there (difference matting). This is exact, also for
     * the soft edges of a snow flake.
     */
    fun wake(design: CwfData, atMs: List<Long>, onBlack: Boolean = false): List<Bitmap?> {
        val black = wakeOn(withBackground(design, BLACK_PNG), atMs)
        // Light that adds to what lies below has no transparency: it stays on black, see [WffAssets.Loop.blend]
        if (onBlack) return black
        val white = wakeOn(withBackground(design, WHITE_PNG), atMs)
        return black.indices.map { i ->
            val b = black[i]
            val w = white[i]
            if (b == null || w == null) null.also { b?.recycle(); w?.recycle() } else matte(b, w).also { b.recycle(); w.recycle() }
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
            // The wake starts with the first frame drawn: draw it at the start, not at the first moment asked for
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

    /** The frame of [onBlack] and [onWhite] (the same frame on two backgrounds) as one see-through frame. */
    private fun matte(onBlack: Bitmap, onWhite: Bitmap): Bitmap {
        val w = onBlack.width
        val h = onBlack.height
        val b = IntArray(w * h).also { onBlack.getPixels(it, 0, w, 0, 0, w, h) }
        val l = IntArray(w * h).also { onWhite.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)
        for (i in out.indices) {
            val pb = b[i]
            val pw = l[i]
            // How much of the background shows: the largest difference of a channel
            val through = maxOf((pw shr 16 and 0xff) - (pb shr 16 and 0xff), (pw shr 8 and 0xff) - (pb shr 8 and 0xff), (pw and 0xff) - (pb and 0xff)).coerceIn(0, 255)
            val alpha = 255 - through
            if (alpha == 0) continue
            // On black the colour is the frame's colour times its alpha
            fun channel(shift: Int) = ((pb shr shift and 0xff) * 255 / alpha).coerceAtMost(255)
            out[i] = (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
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
            File(out, "shared_prefs/aaps_health_values.xml").writeText(preferencesXml(preferences("scene_aaps_health_values")))
            clock.setTo(clock.now + REST_LEAD_MS)
            face.frame(size, clock.now).savePng(File(out, "reference.png"))
        } finally {
            face.release()
        }
    }

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

        /** PNGs of one black and one white pixel. */
        private val BLACK_PNG: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg==")
        private val WHITE_PNG: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGP4DwQACfsD/fteaysAAAAASUVORK5CYII=")

        /** Longer than the longest wake-up animation the engine allows (30 s). */
        const val REST_LEAD_MS = 31_000L

        /** The pixels of [bitmap] that are not fully clear, as a box in pixels snapped out to [snap]; null when all are clear. */
        fun inkBox(bitmap: Bitmap, snap: Int): IntArray? {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            var left = w
            var top = h
            var right = -1
            var bottom = -1
            for (y in 0 until h) for (x in 0 until w) if (pixels[y * w + x] ushr 24 != 0) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            if (right < 0) return null
            return intArrayOf(left / snap * snap, top / snap * snap, minOf(w, (right / snap + 1) * snap), minOf(h, (bottom / snap + 1) * snap))
        }

        /** The pixels of [bitmap] that are not black, as [inkBox] does for the pixels that are not clear. */
        fun lightBox(bitmap: Bitmap, snap: Int): IntArray? {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            val marked = Bitmap.createBitmap(IntArray(w * h) { i -> if (pixels[i] and 0xffffff != 0) -1 else 0 }, w, h, Bitmap.Config.ARGB_8888)
            return inkBox(marked, snap).also { marked.recycle() }
        }

        fun union(a: IntArray?, b: IntArray?): IntArray? = when {
            a == null -> b
            b == null -> a
            else      -> intArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
        }

        fun crop(bitmap: Bitmap, box: IntArray): Bitmap = Bitmap.createBitmap(bitmap, box[0], box[1], box[2] - box[0], box[3] - box[1])

        /** A box in pixels of a picture [size] wide as a box of the design (400 wide). */
        fun designRect(box: IntArray, size: Int): DesignRect {
            val k = 400f / size
            return DesignRect(box[0] * k, box[1] * k, (box[2] - box[0]) * k, (box[3] - box[1]) * k)
        }
    }
}

fun Bitmap.savePng(file: File) {
    file.parentFile?.mkdirs()
    file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
}
