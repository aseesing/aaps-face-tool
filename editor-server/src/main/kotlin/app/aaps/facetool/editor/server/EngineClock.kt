package app.aaps.facetool.editor.server

import app.aaps.facetool.snapshot.SnapshotClock

/**
 * The frozen clock of the engine, for a server: it can go to any time. Robolectric's clock only goes
 * forward, so a move back starts it again with [restart] (`ShadowPausedSystemClock.reset()`), see
 * design 4.3.
 */
class EngineClock(private val clock: SnapshotClock, private val restart: () -> Unit) {

    val now: Long get() = clock.now

    /** The frozen clock itself, for the harness (`WffBaker`), which only moves it forward. */
    val snapshotClock: SnapshotClock get() = clock

    fun moveTo(epochMs: Long) {
        if (epochMs < clock.now) restart()
        if (epochMs != clock.now) clock.setTo(epochMs)
    }
}
