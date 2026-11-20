package app.aaps.facetool.wffparity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A small design for one parity test: a plain background and only the feature under test. The views
 * and the animation are given as json, as a designer writes them (see the manual of the layer
 * engine). Pictures the design can use, by key:
 *
 * - `Background`: a dark blue to purple gradient over the whole face (always there)
 * - `Star`: a yellow star of 64 x 64 on a clear background
 * - `Disc`: a white disc of 64 x 64, soft at its edge
 * - `Mask`: a white circle of 400 x 400, the upper half only (for masks)
 *
 * and the fonts of Aurora Town as `AuroraCut` and `AuroraSnow`. A test adds its own pictures with
 * [picture].
 */
class ParityDesign(val name: String) {

    /** Pictures of this design only, by key, next to the ones every design has. */
    private val pictures = HashMap<String, ResData>()

    private val json = JSONObject()
        .put("metadata", JSONObject().put(CwfMetadataKey.CWF_NAME.key, name).put(CwfMetadataKey.CWF_FILENAME.key, name).put(CwfMetadataKey.CWF_AUTHOR_VERSION.key, "1").put(CwfMetadataKey.CWF_VERSION.key, "2.0"))
        .put("background", view(0, 0, 400, 400))

    /** Adds or replaces the classic view [key]: its place and size, and more keys in [extra] (json). */
    fun view(key: String, left: Int, top: Int, width: Int, height: Int, extra: String = ""): ParityDesign = apply {
        json.put(key, view(left, top, width, height).also { v -> if (extra.isNotEmpty()) JSONObject("{$extra}").let { e -> e.keys().forEach { v.put(it, e.get(it)) } } })
    }

    /** Sets top level keys of the design json (json without the outer braces): colours, `dynData`, `options`, ... */
    fun keys(extra: String): ParityDesign = apply { JSONObject("{$extra}").let { e -> e.keys().forEach { json.put(it, e.get(it)) } } }

    /** Sets the metadata key [key] (a watch preference the design sets, like `key_show_date`). */
    fun meta(key: String, value: String): ParityDesign = apply { json.getJSONObject("metadata").put(key, value) }

    /**
     * The animation: [layers] is the json array of the layers (without the brackets), [extra] more
     * keys of the animation (`"durationMs": 6000, "let": {...}`).
     */
    fun animation(layers: String, extra: String = ""): ParityDesign = apply {
        json.put("animation", JSONObject("{$extra}").put("layers", JSONArray("[$layers]")))
    }

    /** Adds the picture [data] under [key], or replaces it: a picture this test needs, such as a hand (`HourHand`). */
    fun picture(key: String, data: ResData): ParityDesign = apply { pictures[key] = data }

    fun cwf(): CwfData {
        val res = mutableMapOf<String, ResData>()
        res["Background"] = png(400, 400) { c ->
            val p = Paint().apply { shader = LinearGradient(0f, 0f, 400f, 400f, Color.rgb(16, 24, 64), Color.rgb(72, 24, 88), Shader.TileMode.CLAMP) }
            c.drawRect(0f, 0f, 400f, 400f, p)
        }
        res["Star"] = png(64, 64) { c ->
            val path = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 30.0 else 12.0
                val a = Math.PI / 5 * i - Math.PI / 2
                val x = (32 + r * Math.cos(a)).toFloat()
                val y = (32 + r * Math.sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 210, 60) })
        }
        res["Disc"] = png(64, 64) { c -> c.drawCircle(32f, 32f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }) }
        res["Mask"] = png(400, 400) { c -> c.drawRect(0f, 0f, 400f, 200f, Paint().apply { color = Color.WHITE }) }
        fonts().forEach { (key, data) -> res[key] = data }
        res.putAll(pictures)
        // The watch reads the preferences of a design from its metadata, as a zip gives them
        val meta = json.getJSONObject("metadata")
        val metadata = meta.keys().asSequence().mapNotNull { key -> CwfMetadataKey.fromKey(key)?.let { it to meta.getString(key) } }.toMap().toMutableMap()
        metadata[CwfMetadataKey.CWF_AUTHORIZATION] = "true"
        return CwfData(json.toString(), metadata, res)
    }

    private fun view(left: Int, top: Int, width: Int, height: Int) =
        JSONObject().put("leftmargin", left).put("topmargin", top).put("width", width).put("height", height).put("visibility", "visible")

    private fun png(w: Int, h: Int, draw: (Canvas) -> Unit): ResData {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b))
        return ResData(ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(), ResFormat.PNG)
    }

    private companion object {

        /** The fonts of Aurora Town, read once from the designs repository (`designsDir`, see the README). */
        fun fonts(): Map<String, ResData> = cached ?: run {
            val designs = System.getProperty("wff.zips") ?: error("Set designsDir (the aaps-watchfaces checkout) in local.properties: the parity tests use the fonts of Aurora Town")
            val zip = File(designs, "Aurora_Town.zip")
            val data = ZipWatchfaceFormat.loadCustomWatchface(zip.readBytes(), zip.name, authorization = true)!!.cwfData.resData
            data.filterValues { it.format == ResFormat.TTF || it.format == ResFormat.OTF }.also { cached = it }
        }

        private var cached: Map<String, ResData>? = null
    }
}
