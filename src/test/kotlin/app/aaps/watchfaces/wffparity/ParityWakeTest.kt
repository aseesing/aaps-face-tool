package app.aaps.watchfaces.wffparity

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The wake: layers that show or move only while a wake plays. The face at rest is compared with ours as
 * usual; with `wake = true` the base also takes pictures during a wake on the emulator and checks that
 * the wake shows something. The timing of the wake on the emulator is not exact, so the wake pictures
 * are not compared with our frames pixel by pixel.
 *
 * How the converter makes the wake (`WffLoops`, `docs/wff-converter-design.md` 6.4 and 13.3): a layer
 * that shows for the whole wake is a short loop (2 s at 15 frames a second) that repeats; a layer that
 * shows for a part of the wake is played once, at most 8 frames a second, from where it starts; a
 * static picture whose layers also move in the wake gets frames of the whole picture, 4 a second. All
 * frames of a face together: at most 200.
 */
class ParityWakeTest : WffParityBase() {

    /** A layer without rest that shows for a part of the wake only (1 s to 3 s of 5 s): played once, with a delay. */
    @Test
    fun wakeOnlyPart() {
        check(
            ParityDesign("WakePart").animation(
                """{"shape": "rect", "color": "#4CC9F0", "x": "80 + 80 * t", "y": 170, "width": 60, "height": 60, "visible": "t >= 1 && t <= 3"}""",
                "\"durationMs\": 5000, \"fadeMs\": 0"
            ),
            lint = listOf("moves in the wake", "1 loops"),
            wake = true
        )
        val xml = faceXml()
        assertWithMessage("a part of the wake plays once:\n%s", xml).that(xml).contains("loopCount=\"1\"")
        assertWithMessage("and starts when the layer shows (0.5 s, one step before 1 s):\n%s", xml).that(xml).contains("delayPlay=\"0.5\"")
    }

    /** A layer without rest that shows for the whole wake: a short loop that repeats until the wake is over. */
    @Test
    fun wakeLoop() {
        check(
            ParityDesign("WakeLoop").animation(
                """{"shape": "rect", "color": "#F72585", "x": 160, "y": 160, "width": 80, "height": 80, "rotation": "t * 180"}""",
                "\"durationMs\": 6000, \"fadeMs\": 0"
            ),
            lint = listOf("moves in the wake", "1 loops with 30 frames"),
            wake = true
        )
        val xml = faceXml()
        // 2 s at 15 a second, 3 times in a wake of 6 s
        assertWithMessage("a loop of the whole wake:\n%s", xml).that(xml).contains("frameRate=\"15\"")
        assertWithMessage("repeats over the wake:\n%s", xml).that(xml).contains("loopCount=\"3\"")
    }

    /**
     * A layer that fades in and out with `env` (fadeMs 1500). The loop is taken after the fade in, so it
     * has the full strength; the fade itself is not in WFF (the layer pops in and out).
     */
    @Test
    fun wakeEnvFade() {
        check(
            ParityDesign("WakeFade").animation(
                """{"shape": "circle", "color": "#FFD166", "x": 200, "y": 200, "radius": 50, "alpha": "env"}""",
                "\"durationMs\": 6000, \"fadeMs\": 1500"
            ),
            lint = listOf("moves in the wake", "1 loops"),
            wake = true
        )
    }

    /**
     * A rest layer that also moves in the wake: it is a part of static picture 0, and the whole picture
     * gets frames from the start of the wake to its end, at 4 a second.
     */
    @Test
    fun restMovesInWake() = check(
        ParityDesign("RestMoves").animation(
            """{"image": "Star", "rect": {"left": 150, "top": 150, "width": 100, "height": 100}, "rest": 0, "rotation": "env * 40 * sin(t * 3)"}""",
            "\"durationMs\": 4000, \"fadeMs\": 500"
        ),
        lint = listOf("picture 0 moves in the wake", "at 4 a second"),
        wake = true
    )

    /** A light that adds (blend add) in the wake, over a rest bar: framed on black, played with PLUS. */
    @Test
    fun wakeBlendAdd() {
        check(
            ParityDesign("WakeAdd").animation(
                """
                {"shape": "rect", "color": "#35507A", "rest": 0, "x": 60, "y": 150, "width": 280, "height": 100},
                {"shape": "circle", "color": "#C97B2A", "blend": "add", "radius": 45, "x": "200 + 80 * sin(t * 2)", "y": 200}
                """,
                "\"durationMs\": 5000, \"fadeMs\": 0"
            ),
            lint = listOf("moves in the wake", "1 loops"),
            wake = true
        )
        val xml = faceXml()
        assertWithMessage("the light is played with PLUS:\n%s", xml).that(xml).contains("blendMode=\"PLUS\"")
    }

    /**
     * A layer that shows only from 24 to 26 December (as Santa). At the test moment, 17 November, it must
     * not show. A loop on the left makes the wake visible; the right part must stay as at rest.
     */
    @Test
    fun wakeOnSomeDays() {
        check(
            ParityDesign("WakeDays").animation(
                """
                {"shape": "rect", "color": "#4CC9F0", "x": 70, "y": "170 + 20 * sin(t * 3)", "width": 60, "height": 60},
                {"shape": "circle", "color": "#E63946", "x": "290 + 10 * sin(t * 3)", "y": 200, "radius": 40, "visible": "v_month == 12 && v_day >= 24 && v_day <= 26"}
                """,
                "\"durationMs\": 5000, \"fadeMs\": 0"
            ),
            lint = listOf("L1: shows only on 3 days of the year"),
            wake = true,
            stillInWake = listOf(ParityCompare.Region("red circle (not on 17 Nov)", STILL_PERCENT, intArrayOf(230, 140, 120, 120)))
        )
        val xml = faceXml()
        assertWithMessage("its frames play inside a Condition on the date:\n%s", xml).that(xml).contains("[MONTH] == 12 &amp;&amp; [DAY] &gt;= 24 &amp;&amp; [DAY] &lt;= 26")
    }

    /**
     * A classic view (the day name, a live date text) that a view layer moves only in the wake: on WFF it
     * stays at its rest place. A loop on the left makes the wake visible.
     */
    @Test
    fun viewMovesOnlyInWake() {
        check(
            ParityDesign("ViewWake")
                // The date views show only with this watch preference
                .meta("key_show_date", "true")
                .view("day_name", 220, 170, 140, 60, "\"textsize\": 40, \"gravity\": \"center\", \"font\": \"default\", \"fontStyle\": \"bold\", \"fontColor\": \"#FFFFFF\"")
                .animation(
                    """
                    {"shape": "rect", "color": "#4CC9F0", "x": 70, "y": "170 + 20 * sin(t * 3)", "width": 60, "height": 60},
                    {"view": "day_name", "y": "-30 * env * abs(sin(t * 5))"}
                    """,
                    "\"durationMs\": 5000, \"fadeMs\": 500"
                ),
            lint = listOf("date text", "day_name moves only during the wake: it stays at its rest place"),
            wake = true,
            stillInWake = listOf(ParityCompare.Region("day name", STILL_PERCENT, intArrayOf(210, 120, 160, 120)))
        )
    }

    /**
     * More frames than the budget of 200: 8 layers of the whole wake, each in a group of its own (3 slots x
     * 3 blends), each a loop of 30 frames. 6 loops fit, the 7th gets the 20 left, the 8th none, and the
     * lint must say so.
     */
    @Test
    fun tooManyFrames() {
        val groups = listOf("back" to "normal", "back" to "add", "back" to "screen", "views" to "normal", "views" to "add", "views" to "screen", "front" to "normal", "front" to "add")
        val layers = groups.mapIndexed { i, (slot, blend) ->
            val a = 2 * Math.PI * i / groups.size - Math.PI / 2
            val x = 200 + 110 * Math.cos(a)
            val y = 190 + 100 * Math.sin(a)
            """{"shape": "circle", "color": "#C97B2A", "slot": "$slot", "blend": "$blend", "radius": 22, "x": "${"%.0f".format(x)} + 10 * sin(t * 4)", "y": ${"%.0f".format(y)}}"""
        }
        check(
            ParityDesign("TooManyFrames").animation(layers.joinToString(",\n"), "\"durationMs\": 6000, \"fadeMs\": 0"),
            lint = listOf("the budget of 200 frames is used up", "frames of 200"),
            wake = true
        )
    }

    // ---- helpers ----

    private companion object {

        /** A part that must not move in the wake may change this much (noise of the screen pictures). */
        const val STILL_PERCENT = 0.5
    }
}
