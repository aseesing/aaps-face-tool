package app.aaps.facetool.wff

import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.wear.watchfaces.wff.WffDesignParts
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The face zip that the AAPS phone app loads: one zip per design, named `<design>-wff.zip`. Format 1:
 *
 * | Entry | What |
 * |---|---|
 * | `face.json` | `{"format": 1, "name": "<design name>"}` |
 * | `data.zip` | the parts of the data picture as an old design zip, see [dataZip] |
 * | `wff4/<appId>.apk`, `wff4/<appId>.token` | the face as WFF 4 for Wear OS 6, and its Watch Face Push token |
 * | `wff2/<appId>.apk` | the face as WFF 2 for Wear OS 5, installed as a normal app (no token) |
 *
 * `<appId>` is the wear app the face is for ([APP_IDS]); a zip may hold any of them. The phone takes the
 * highest version the watch can run.
 */
object WffFaceZip {

    /** The layout of the zip, in `face.json`. */
    const val FORMAT = 1

    const val FACE_JSON = "face.json"
    const val DATA_ZIP = "data.zip"

    /** The wear apps a face can be made for: the full app, the three clients and pump control. */
    val APP_IDS = listOf(
        "info.nightscout.androidaps",
        "info.nightscout.aapsclient",
        "info.nightscout.aapsclient2",
        "info.nightscout.aapsclient3",
        "info.nightscout.aapspumpcontrol"
    )

    /** The package of a face for the wear app [appId]: Watch Face Push needs it to start with the app's own id. */
    fun packageOf(appId: String): String = "$appId.watchfacepush.design"

    /** The name of the face zip of the design zip [designZip]: `Aurora_Town.zip` gives `Aurora_Town-wff.zip`. */
    fun nameOf(designZip: String): String = designZip.removeSuffix(".zip") + "-wff.zip"

    fun apkEntry(version: Int, appId: String): String = "wff$version/$appId.apk"

    fun tokenEntry(version: Int, appId: String): String = "wff$version/$appId.token"

    /**
     * One face APK of the zip.
     *
     * @param token the Watch Face Push token of exactly this APK; null for a version that is not pushed (2)
     */
    data class Face(val appId: String, val version: Int, val apk: File, val token: String?)

    fun faceJson(name: String): String = JSONObject().put("format", FORMAT).put("name", name).toString()

    /**
     * The design [source] with only the parts the watch draws into the data picture ([parts]), as an old
     * design zip: `CustomWatchface.json` and the resource files. The background is a clear picture.
     * The other resources all stay, the arrow pictures too, also when no view of the data picture
     * uses them: the glucose slot of the face shows the arrow from these files.
     */
    fun dataZip(source: CwfData, parts: WffDesignParts): ByteArray {
        val data = WffDesignParts.asCwf(source, parts.data(), transparent = true)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.entry(CWF_JSON, data.json.toByteArray(Charsets.UTF_8))
            for ((name, res) in data.resData) zip.entry("$name.${res.format.extension}", res.value)
        }
        return bytes.toByteArray()
    }

    /** Writes the face zip [file] of the design [name]: `face.json`, [data] as `data.zip`, and the [faces]. */
    fun write(file: File, name: String, data: ByteArray, faces: List<Face>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.entry(FACE_JSON, faceJson(name).toByteArray(Charsets.UTF_8))
            zip.entry(DATA_ZIP, data)
            for (face in faces) {
                zip.entry(apkEntry(face.version, face.appId), face.apk.readBytes())
                face.token?.let { zip.entry(tokenEntry(face.version, face.appId), it.toByteArray(Charsets.UTF_8)) }
            }
        }
    }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    /** The json of a design zip, as `ZipWatchfaceFormat` reads it. */
    private const val CWF_JSON = "CustomWatchface.json"
}
