package app.aaps.watchfaces.wff

import android.graphics.Paint
import android.graphics.Typeface
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.shared.impl.weardata.toTypeface
import app.aaps.wear.watchfaces.wff.WffFontMeasure
import app.aaps.wear.watchfaces.wff.WffFontMetrics

/**
 * The [WffFontMetrics] of the fonts of [design], measured with Android's own `Paint`. A font that is not
 * in the design (a system font) is measured as the font of the device, which a WFF face also uses then.
 */
class PaintFontMeasure(private val design: CwfData) : WffFontMeasure {

    private val known = HashMap<Pair<String, Boolean>, WffFontMetrics>()

    override fun metrics(font: String, bold: Boolean): WffFontMetrics = known.getOrPut(font to bold) {
        val base = design.resData[font]?.toTypeface() ?: Typeface.DEFAULT
        val paint = Paint().apply {
            typeface = Typeface.create(base, if (bold || font == "default_bold") Typeface.BOLD else Typeface.NORMAL)
            textSize = SIZE
        }
        val m = paint.fontMetrics
        WffFontMetrics(top = m.top / SIZE.toDouble(), ascent = m.ascent / SIZE.toDouble(), descent = m.descent / SIZE.toDouble(), bottom = m.bottom / SIZE.toDouble())
    }

    private companion object {

        const val SIZE = 1000f
    }
}
