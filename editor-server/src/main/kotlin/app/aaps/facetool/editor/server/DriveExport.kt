package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.wrongUse
import java.io.File

/**
 * Copies an exported file into a folder of Google Drive for desktop (design section 10: the Export
 * window). A file that is there already is written over in place: Drive keeps it as the same file, with
 * a new version, and never adds a second file "name (1).zip". The phone's file picker remembers a file,
 * so a design keeps its place there.
 *
 * Only a file under `.facetool/out/` of a project may be copied, as for `/api/file`.
 *
 * @param folder the Drive folder, or null when none is set and none was found
 */
class DriveExport(val folder: File?) {

    /** The result of [copy]: the file in Drive, and whether it was there already. */
    class Copied(val file: File, val replaced: Boolean)

    /** Copies [source] into the Drive folder as [name]. */
    fun copy(source: File, name: String): Copied {
        val target = folder ?: wrongUse("No Google Drive folder is set. Set driveDir in local.properties of the editor checkout.")
        if (!target.isDirectory) wrongUse("The Google Drive folder ${target.path} is not there. Is Google Drive for desktop running?")
        val file = source.canonicalFile
        if (!file.isFile || !file.path.replace(File.separatorChar, '/').contains("/.facetool/out/")) wrongUse("Only an exported file under .facetool/out can go to Google Drive")
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.startsWith(".")) wrongUse("'$name' is no plain file name")
        val out = File(target, name)
        val replaced = out.isFile
        // writeBytes opens the file that is there and writes over it: the same Drive file, not a new one
        out.writeBytes(file.readBytes())
        return Copied(out, replaced)
    }

    companion object {

        /**
         * The Drive folder: `driveDir` of the editor's `local.properties` when set, else
         * `AAPS/latest` in the first Google Drive for desktop account that has it, else null.
         */
        fun find(localProperties: File?, home: File): File? {
            val named = localProperties?.takeIf { it.isFile }?.readLines()?.firstOrNull { it.trim().startsWith("driveDir=") }?.substringAfter("=")?.trim()
            if (!named.isNullOrEmpty()) return File(named)
            return File(home, "Library/CloudStorage").listFiles { file -> file.name.startsWith("GoogleDrive-") }.orEmpty()
                .sortedBy { it.name }
                .map { File(it, "My Drive/AAPS/latest") }
                .firstOrNull { it.isDirectory }
        }
    }
}
