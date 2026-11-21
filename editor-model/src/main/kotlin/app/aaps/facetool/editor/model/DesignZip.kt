package app.aaps.facetool.editor.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One file of a design zip: its name, its bytes and the time the zip gives it. */
class ZipFileEntry(val name: String, val bytes: ByteArray, val time: LocalDateTime?)

/** Reads and writes design zips. Entries keep their order. */
object DesignZip {

    /** The files of a zip, in the order of the zip. Folders are left out. */
    fun read(zip: ByteArray): List<ZipFileEntry> {
        val entries = mutableListOf<ZipFileEntry>()
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name.startsWith("/") || name.split('/', '\\').any { it == ".." || it == ".facetool" }) unreadable("The zip has a file with a bad name: $name")
                    entries += ZipFileEntry(name, input.readBytes(), entry.timeLocal)
                }
            }
        } catch (e: IOException) {
            unreadable("The zip does not read: ${e.message}")
        }
        if (entries.isEmpty()) unreadable("This is not a zip, or it is empty")
        return entries
    }

    /** Writes [entries] in their order, with the compression of the generators (deflate, the default level). */
    fun write(entries: List<ZipFileEntry>, defaultTime: LocalDateTime): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for (entry in entries) {
                val zipEntry = ZipEntry(entry.name)
                zipEntry.timeLocal = entry.time ?: defaultTime
                out.putNextEntry(zipEntry)
                out.write(entry.bytes)
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
