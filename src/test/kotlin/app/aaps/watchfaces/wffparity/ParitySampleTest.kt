package app.aaps.watchfaces.wffparity

import org.junit.Test

/** Two first tests of the parity suite: a still layer of shapes, and the clock of the classic face. */
class ParitySampleTest : WffParityBase() {

    @Test
    fun restShapes() = check(
        ParityDesign("Shapes").animation(
            """
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 60, "y": 150, "width": 80, "height": 100},
            {"shape": "circle", "color": "#F72585", "rest": 0, "x": 200, "y": 200, "radius": 40},
            {"shape": "line", "color": "#FFD166", "rest": 0, "x": 300, "y": 150, "length": 100, "thickness": 8, "angle": 30}
            """,
            "\"durationMs\": 4000"
        )
    )

    @Test
    fun clockView() = check(
        ParityDesign("Clock").view("time", 80, 150, 240, 100, "\"textsize\": 80, \"gravity\": \"center\", \"font\": \"default\", \"fontStyle\": \"bold\", \"fontColor\": \"#FFFFFF\""),
        lint = listOf("time")
    )
}
