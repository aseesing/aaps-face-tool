package app.aaps.facetool.editor.server

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets the page stop a frames call (`wake --no-sheet`, `wff wake --no-sheet`) that it no longer needs.
 * A whole wake can take many seconds, and the one render thread makes nothing else in that time. The
 * page names the folder of the frames; the command looks here before each frame.
 */
class FrameStops {

    private val asked = ConcurrentHashMap.newKeySet<String>()

    /** `POST /api/cancel`: the frames call that writes into [dir], running or waiting, shall stop. */
    fun ask(dir: File) {
        asked += dir.absolutePath
    }

    /** True once when a stop was asked for [dir]. */
    fun take(dir: File): Boolean = asked.remove(dir.absolutePath)
}
