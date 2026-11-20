package app.aaps.watchfaces.wffparity

import org.junit.Test

/*
 * Parity of copies and formulas (sections 4, 5 and 6 of the manual of the layer engine): copies of a
 * layer, operators, functions, the time, `let`, own functions, texts, the names of the Watch Face
 * Format, values and the sky. Every layer has `"rest": 0`, so the face at rest shows it.
 *
 * A layer of fixed numbers is a static picture. A layer that reads the clock or a value goes to the
 * data picture (drawn by our engine on the watch), or is live in WFF; the picture must match either way.
 */
/** Copies and formulas. */
class ParityCopiesFormulasCopiesTest : WffParityBase() {

    // ---- copies ----

    /** `count` with `i`, `n` and `u`: twelve circles on a ring, each a little bigger. */
    @Test
    fun copiesCount() = check(
        ParityDesign("CopiesCount").animation(
            """
            {"shape": "circle", "color": "#4CC9F0", "rest": 0, "count": 12,
             "x": "200 + 110 * cos(2 * pi * i / n)", "y": "200 + 110 * sin(2 * pi * i / n)", "radius": "6 + 14 * u"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("static picture (12 copies)")
    )

    /** `count` and `seed`: random numbers `r1` to `r4` per copy, the same at every frame. */
    @Test
    fun copiesSeed() = check(
        ParityDesign("CopiesSeed").animation(
            """
            {"shape": "circle", "color": "#FFFFFF", "rest": 0, "count": 30, "seed": 5,
             "x": "90 + 220 * r1", "y": "90 + 220 * r2", "radius": "3 + 7 * r3", "alpha": "0.4 + 0.6 * r4"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("static picture (30 copies)")
    )

    /** `hash` and `noise` of fixed numbers per copy: bars of random height, still. */
    @Test
    fun copiesHash() = check(
        ParityDesign("CopiesHash").animation(
            """
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "count": 14,
             "let": {"h": "20 + 80 * noise(i * 0.35, 2)"},
             "x": "74 + i * 18", "width": 12, "y": "190 - h", "height": "h"},
            {"shape": "rect", "color": "#F72585", "rest": 0, "count": 14,
             "let": {"h": "20 + 80 * hash(i, 3)"},
             "x": "74 + i * 18", "width": 12, "y": "310 - h", "height": "h"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("shows at rest too: in static picture 0")
    )

    /** `points` with `px`, `py` and the extra numbers `p0` (radius) and `p1` (colour). */
    @Test
    fun copiesPoints() = check(
        ParityDesign("CopiesPoints").animation(
            """
            {"shape": "circle", "colors": ["#FF4632", "#FFBE3C", "#508CFF", "#5AE66E"], "rest": 0,
             "points": [[80, 250, 10, 0], [120, 215, 12, 1], [160, 190, 14, 2], [200, 180, 16, 3],
                        [240, 190, 18, 0], [280, 215, 20, 1], [320, 250, 22, 2]],
             "x": "px", "y": "py", "radius": "p0", "colorIndex": "p1"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("static picture (7 copies)")
    )

    /** `slices` standing up and lying flat, each strip moved by `u`. */
    @Test
    fun copiesSlices() = check(
        ParityDesign("CopiesSlices").animation(
            """
            {"image": "Star", "rect": {"left": 40, "top": 125, "width": 150, "height": 150}, "rest": 0,
             "slices": 30, "y": "15 * sin(u * 6)"},
            {"image": "Star", "rect": {"left": 210, "top": 125, "width": 150, "height": 150}, "rest": 0,
             "slices": 30, "slicesAlong": "y", "x": "15 * sin(u * 6)"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("static picture (slices)")
    )

    // ---- formulas ----

    /** The operators: the sizes and places of the bars come from them. The last circle has x = 1 / 0: not drawn. */
    @Test
    fun formulaOperators() = check(
        ParityDesign("Operators").animation(
            """
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": "-(-80)", "y": 100, "width": "2 + 3 * 4 * 5", "height": 26},
            {"shape": "rect", "color": "#F72585", "rest": 0, "x": 80, "y": 135, "width": "-1 % 4 * 30 + 7 / 2", "height": 26},
            {"shape": "rect", "color": "#FFD166", "rest": 0, "x": 80, "y": 170, "width": "(7 < 9) * 100 + (3 >= 4) * 50 + (2 == 2) * 40 + (2 != 2) * 30", "height": 26},
            {"shape": "rect", "color": "#2A9D8F", "rest": 0, "x": 80, "y": 205, "width": "1 > 0 && 0 || 1 ? 200 : 20", "height": 26},
            {"shape": "rect", "color": "#E63946", "rest": 0, "x": 80, "y": 240, "width": "0 ? 50 : 1 ? 120 : 10", "height": 26},
            {"shape": "rect", "color": "#9AA5B5", "rest": 0, "x": 80, "y": 275, "width": 160, "height": 26, "visible": "!(1 / 0 == 1 / 0)"},
            {"shape": "circle", "color": "#FFFFFF", "rest": 0, "x": "1 / 0", "y": 320, "radius": 20}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("static picture")
    )

    /** The functions of fixed numbers: the radius of each circle and the angle of each line comes from one. */
    @Test
    fun formulaFunctions() = check(
        ParityDesign("Functions").animation(
            """
            {"shape": "circle", "color": "#4CC9F0", "rest": 0, "x": 110, "y": 120, "radius": "10 + 10 * abs(sin(pi / 2)) - 5 * cos(pi)"},
            {"shape": "circle", "color": "#F72585", "rest": 0, "x": 170, "y": 120, "radius": "clamp(50, 5, 18)"},
            {"shape": "circle", "color": "#FFD166", "rest": 0, "x": 230, "y": 120, "radius": "max(4, min(30, 22), 9)"},
            {"shape": "circle", "color": "#2A9D8F", "rest": 0, "x": 290, "y": 120, "radius": "10 + 20 * fract(1.25) - 20 * fract(-0.25)"},
            {"shape": "circle", "color": "#E63946", "rest": 0, "x": 110, "y": 180, "radius": "mod(-1, 4) * 5 + (-6 % 4)"},
            {"shape": "circle", "color": "#4CC9F0", "rest": 0, "x": 170, "y": 180, "radius": "mix(10, 30, 0.5)"},
            {"shape": "circle", "color": "#F72585", "rest": 0, "x": 230, "y": 180, "radius": "10 + 10 * step(1, 2) + 5 * smoothstep(0, 2, 1)"},
            {"shape": "circle", "color": "#FFD166", "rest": 0, "x": 290, "y": 180, "radius": "floor(12.7) + ceil(3.2) + round(2.5) + round(-2.5) + 2"},
            {"shape": "circle", "color": "#2A9D8F", "rest": 0, "x": 110, "y": 240, "radius": "sqrt(100) + pow(2, 3) + cbrt(-8) * sign(-3)"},
            {"shape": "circle", "color": "#E63946", "rest": 0, "x": 170, "y": 240, "radius": "exp(log(20)) + log2(8) - log10(1000) + expm1(0)"},
            {"shape": "circle", "color": "#4CC9F0", "rest": 0, "x": 230, "y": 240, "radius": "10 + 10 * easeInOut(0.5) + 10 * window(3, 1, 4)"},
            {"shape": "circle", "color": "#F72585", "rest": 0, "x": 290, "y": 240, "radius": "pingpong(7, 5) * 6 + if(2 > 1, 8, 1)"},
            {"shape": "line", "color": "#FFFFFF", "rest": 0, "x": 110, "y": 300, "length": 30, "thickness": 5, "angle": "deg(atan2(1, 1))"},
            {"shape": "line", "color": "#FFFFFF", "rest": 0, "x": 170, "y": 300, "length": 30, "thickness": 5, "angle": "deg(acos(0)) + deg(asin(1))"},
            {"shape": "line", "color": "#FFFFFF", "rest": 0, "x": 230, "y": 300, "length": 30, "thickness": 5, "angle": "deg(atan(1)) + deg(rad(90))"},
            {"shape": "line", "color": "#FFFFFF", "rest": 0, "x": 290, "y": 300, "length": 30, "thickness": 5, "angle": "45 * tan(pi / 4) + 90"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("static picture")
    )

    /**
     * The time at rest: hands from `hour` and `min` (left), and a hand from `sec` (right). They read the
     * clock, so they go to the data picture. The seconds hand is drawn when the watch makes the picture,
     * not at the second of our picture: the lint says so, and its region is not compared.
     */
    @Test
    fun timeOfDay() = check(
        ParityDesign("TimeOfDay").animation(
            """
            {"shape": "circle", "color": "#33415C", "rest": 0, "x": 140, "y": 200, "radius": 80},
            {"shape": "line", "color": "#FFFFFF", "rest": 0, "x": 140, "y": 200, "length": 50, "thickness": 10,
             "angle": "180 - (mod(hour, 12) + min / 60) * 30"},
            {"shape": "line", "color": "#C8D0E0", "rest": 0, "x": 140, "y": 200, "length": 72, "thickness": 6,
             "angle": "180 - min * 6"},
            {"shape": "circle", "color": "#33415C", "rest": 0, "x": 290, "y": 200, "radius": 55},
            {"shape": "line", "color": "#FF5A5A", "rest": 0, "x": 290, "y": 200, "length": 50, "thickness": 3,
             "angle": "180 - sec * 6"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("it reads the seconds, but the picture changes only with the data"),
        regions = listOf(
            ParityCompare.Region("hour and minute", 1.0, intArrayOf(50, 110, 180, 180)),
            // The data picture is made at another second than ours: WFF shows it, the lint says so
            ParityCompare.Region("seconds", 100.0, intArrayOf(232, 142, 116, 116))
        )
    )

    /** The date at rest: bars as long as the day, the month and the day of the week, from values and from WFF names. */
    @Test
    fun dateVariables() = check(
        ParityDesign("DateVariables").animation(
            """
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 90, "y": 100, "width": "v_day * 8", "height": 24},
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 90, "y": 135, "width": "v_month * 10", "height": 24},
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 90, "y": 170, "width": "v_day_name * 30", "height": 24},
            {"shape": "rect", "color": "#FFD166", "rest": 0, "x": 90, "y": 215, "width": "[DAY] * 8", "height": 24},
            {"shape": "rect", "color": "#FFD166", "rest": 0, "x": 90, "y": 250, "width": "[MONTH] * 10", "height": 24},
            {"shape": "rect", "color": "#FFD166", "rest": 0, "x": 90, "y": 285, "width": "[DAY_OF_WEEK] * 30", "height": 24}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("reads wff_value: in the data picture", "reads clock: in the data picture")
    )

    /** `let` in the animation (fixed numbers and the minute) and in a layer (per copy). */
    @Test
    fun letNames() = check(
        ParityDesign("Let").animation(
            """
            {"shape": "circle", "color": "#FFD166", "rest": 0, "x": "cx", "y": 210, "radius": "r0 / 2"},
            {"shape": "circle", "color": "#4CC9F0", "rest": 0, "count": 6,
             "let": {"a": "2 * pi * i / n", "d": "r0 + 10 * i"},
             "x": "cx + d * cos(a)", "y": "210 + d * sin(a)", "radius": 8},
            {"shape": "rect", "color": "#F72585", "rest": 0, "x": 110, "y": 60, "width": "m * 22", "height": 14}
            """,
            "\"durationMs\": 4000, \"let\": {\"cx\": \"200\", \"r0\": \"60 + 20\", \"m\": \"min\"}"
        ),
        lint = listOf("reads clock: in the data picture")
    )

    /** Own functions: one that gives a text, one that gives a number and is called per copy. */
    @Test
    fun ownFunctions() = check(
        ParityDesign("OwnFunctions").animation(
            """
            {"text": "two(7) & ':' & two(5)", "fontSize": 40, "rest": 0, "align": "center", "x": 200, "y": 110, "color": "#FFD166"},
            {"shape": "circle", "color": "#4CC9F0", "rest": 0, "count": 8,
             "x": "200 + 90 * sin(ring(i, n))", "y": "235 - 90 * cos(ring(i, n))", "radius": "big(5)"}
            """,
            "\"durationMs\": 4000, \"functions\": {\"two(n)\": \"text(n, '00')\", \"ring(k, of)\": \"2 * pi * k / of\", \"big(a)\": \"a * 2\"}"
        ),
        lint = listOf("static picture (8 copies)")
    )

    // ---- texts in formulas ----
}

/** Texts in formulas, the names of the Watch Face Format, values and the sky. */
class ParityCopiesFormulasTextsTest : WffParityBase() {

    /** `clock()`, and the clock from its parts with an own sign between them. */
    @Test
    fun textClock() = check(
        ParityDesign("TextClock").animation(
            """
            {"text": "clock()", "font": "default_bold", "fontSize": 72, "rest": 0, "align": "center", "x": 200, "y": 170, "color": "#FFFFFF"},
            {"text": "clockHour() & 'h' & clockMinute() & ampm()", "fontSize": 48, "rest": 0, "align": "center", "x": 200, "y": 270, "color": "#FFD166"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("clock text"),
        regions = listOf(
            ParityCompare.Region("clock()", 1.0, intArrayOf(40, 90, 320, 100)),
            ParityCompare.Region("hour h minute", 1.0, intArrayOf(40, 215, 320, 75))
        )
    )

    /** `date(pattern)` in capitals. */
    @Test
    fun textDate() = check(
        ParityDesign("TextDate").animation(
            """
            {"text": "upper(date('EEE d MMM'))", "fontSize": 40, "rest": 0, "align": "center", "x": 200, "y": 190, "color": "#9AA5B1"},
            {"text": "date('d ''of'' MMMM')", "fontSize": 32, "rest": 0, "align": "center", "x": 200, "y": 260, "color": "#FFD166"}
            """,
            "\"durationMs\": 4000"
        ),
        regions = listOf(
            ParityCompare.Region("EEE d MMM", 1.0, intArrayOf(40, 140, 320, 65)),
            ParityCompare.Region("d of MMMM", 1.0, intArrayOf(40, 220, 320, 55))
        )
    )

    /** `icuText(skeleton)`: the language of the watch chooses the order. */
    @Test
    fun textIcu() = check(
        ParityDesign("TextIcu").animation(
            """
            {"text": "icuText('EEE d MMM')", "fontSize": 36, "rest": 0, "align": "center", "x": 200, "y": 190, "color": "#9AA5B1"},
            {"text": "icuText('yyyyMMdd')", "fontSize": 32, "rest": 0, "align": "center", "x": 200, "y": 260, "color": "#FFD166"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("reads clock: in the data picture")
    )

    /** `glucose(v_sgv)` alone, and joined with a fixed text in a forced unit. */
    @Test
    fun textGlucose() = check(
        ParityDesign("TextGlucose").animation(
            """
            {"text": "glucose(v_sgv)", "fontSize": 56, "rest": 0, "align": "center", "x": 200, "y": 170, "color": "#4CC9F0"},
            {"text": "'BG ' & glucose(v_sgv, 'mgdl')", "fontSize": 36, "rest": 0, "align": "center", "x": 200, "y": 260, "color": "#FFD166"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("glucose text"),
        regions = listOf(
            ParityCompare.Region("glucose(v_sgv)", 1.0, intArrayOf(40, 110, 320, 75)),
            ParityCompare.Region("BG and glucose in mg/dl", 1.0, intArrayOf(40, 220, 320, 55))
        )
    )

    /** The other text functions, on fixed texts (a static picture) and on `v_iob` (the data picture). */
    @Test
    fun textFunctions() = check(
        ParityDesign("TextFunctions").animation(
            """
            {"text": "fixed(3.14159, 2) & ' ' & text(5, '00') & ' ' & padLeft('7', 3, '0') & ' ' & padRight('x', 3, '-')", "fontSize": 26, "rest": 0, "align": "center", "x": 200, "y": 100},
            {"text": "upper('mon') & ' ' & lower('TUE') & ' ' & left('hello', 2) & mid('hello', 2, 3) & right('abc', 1) & ' ' & rept('ab', 2)", "fontSize": 26, "rest": 0, "align": "center", "x": 200, "y": 145},
            {"text": "substitute('a-b', '-', '+') & ' ' & len('hello') & ' ' & find('l', 'hello') & ' ' & subText('abc def', 2, 5) & ' ' & textLength('ab')", "fontSize": 26, "rest": 0, "align": "center", "x": 200, "y": 190},
            {"text": "numberFormat('#,##0.0', 1234.56) & ' ' & concat('x', 1, 'y') & ' ' & (1 + 2 & 3 + 4) & ' ' & trim('  a  b ')", "fontSize": 26, "rest": 0, "align": "center", "x": 200, "y": 235},
            {"text": "if(value('12.5') == 12.5, 'value', 'no') & ' ' & if(missing(1 / 0), 'missing', 'there') & ' ' & ('a' == 'A')", "fontSize": 26, "rest": 0, "align": "center", "x": 200, "y": 280},
            {"text": "'IOB ' & text(v_iob, '+0.00;-0.00') & ' U'", "fontSize": 26, "rest": 0, "align": "center", "x": 200, "y": 325, "color": "#4CC9F0"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("reads aaps: in the data picture")
    )

    // ---- names of the Watch Face Format, values and the sky ----

    /** Data sources between square brackets: hands, a choice by the day of the week, the date and the moon. */
    @Test
    fun wffNames() = check(
        ParityDesign("WffNames").animation(
            """
            {"shape": "circle", "color": "#33415C", "rest": 0, "x": 130, "y": 200, "radius": 70},
            {"shape": "line", "color": "#FFFFFF", "rest": 0, "x": 130, "y": 200, "length": 42, "thickness": 9, "angle": "180 - [HOUR_0_11_MINUTE] * 30"},
            {"shape": "line", "color": "#C8D0E0", "rest": 0, "x": 130, "y": 200, "length": 62, "thickness": 5, "angle": "180 - [MINUTE] * 6"},
            {"text": "[DAY_OF_WEEK] == 1 || [DAY_OF_WEEK] == 7 ? 'weekend' : 'work day'", "fontSize": 24, "rest": 0, "align": "center", "x": 280, "y": 150, "color": "#9AA5B1"},
            {"text": "[DAY] & '/' & [MONTH] & ' ' & [YEAR]", "fontSize": 24, "rest": 0, "align": "center", "x": 280, "y": 190},
            {"text": "[HOUR_1_12] & ' ' & [AMPM_STATE] & ' ' & [DAY_OF_YEAR] & ' ' & [DAYS_IN_MONTH]", "fontSize": 24, "rest": 0, "align": "center", "x": 280, "y": 230},
            {"shape": "rect", "color": "#FFD166", "rest": 0, "x": 210, "y": 255, "width": "[MOON_PHASE_POSITION] * 5", "height": 16}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("reads wff_value: in the data picture", "reads clock: in the data picture")
    )

    /**
     * Values of AAPS as bars: glucose (coloured by its level), reservoir, delta, age, sensor and pump
     * time left, phone battery, heart rate and steps. Not here: `v_iob`, `v_cob` and `v_basalRate`,
     * because the scene sends them only as texts (they would be missing on both sides), and
     * `v_watch_battery`, because the scene does not set it while the emulator has a real battery.
     */
    @Test
    fun values() = check(
        ParityDesign("Values").animation(
            """
            {"shape": "rect", "colors": ["#FF3B3B", "#2EE66B", "#FFB21A"], "rest": 0, "x": 90, "y": 80, "width": "v_sgv * 1.5", "height": 22, "colorIndex": "v_sgvLevel + 1"},
            {"shape": "rect", "color": "#4DA3FF", "rest": 0, "x": 90, "y": 110, "width": "v_reservoir * 1.5", "height": 22},
            {"shape": "rect", "color": "#FFB21A", "rest": 0, "x": 90, "y": 140, "width": "clamp(v_sensor_left, 0, 100) * 2", "height": 22},
            {"shape": "rect", "color": "#C8D0E0", "rest": 0, "x": 90, "y": 170, "width": "100 + v_delta * 20", "height": 22},
            {"shape": "rect", "color": "#F72585", "rest": 0, "x": 90, "y": 200, "width": "20 + v_timestamp * 20", "height": 22},
            {"shape": "rect", "color": "#2A9D8F", "rest": 0, "x": 90, "y": 230, "width": "clamp(v_pump_left, 0, 100) * 2", "height": 22},
            {"shape": "rect", "color": "#9AA5B5", "rest": 0, "x": 90, "y": 260, "width": "v_uploader_battery * 2", "height": 22},
            {"shape": "rect", "color": "#E63946", "rest": 0, "x": 90, "y": 290, "width": "v_heart_rate * 2", "height": 22},
            {"shape": "rect", "color": "#5AE66E", "rest": 0, "x": 90, "y": 320, "width": "v_steps / 40", "height": 22}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("in the data picture", "a mark of an old reading")
    )

    /** The place of the wearer: the sun and the moon as bars and a line, and the colour of day or night. */
    @Test
    fun sky() = check(
        ParityDesign("Sky").keys("\"needsPlace\": true").animation(
            """
            {"shape": "rect", "color": "#FFD166", "rest": 0, "x": 90, "y": 100, "width": "20 + 200 * v_sun_progress", "height": 22},
            {"shape": "rect", "color": "#FFD166", "rest": 0, "x": 90, "y": 130, "width": "20 + 200 * v_sun_cycle", "height": 22},
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 90, "y": 160, "width": "20 + 200 * v_night_progress", "height": 22},
            {"shape": "rect", "color": "#C8D0E0", "rest": 0, "x": 90, "y": 190, "width": "20 + 200 * v_moon_phase", "height": 22},
            {"shape": "rect", "color": "#C8D0E0", "rest": 0, "x": 90, "y": 220, "width": "20 + 200 * v_moon_lit", "height": 22},
            {"shape": "rect", "color": "#9AA5B5", "rest": 0, "x": 90, "y": 250, "width": "20 + 200 * v_moon_cycle", "height": 22},
            {"shape": "circle", "colors": ["#33415C", "#FFD166"], "rest": 0, "x": 150, "y": 310, "radius": 18, "colorIndex": "v_is_day"},
            {"shape": "line", "color": "#FFFFFF", "rest": 0, "x": 250, "y": 310, "length": 30, "thickness": 5, "angle": "180 - v_moon_limb"}
            """,
            "\"durationMs\": 4000"
        ),
        lint = listOf("reads sky: in the data picture")
    )
}
