package app.phonemcp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The design's 24x24 stroke icons, drawn from the same SVG path data (no icon library needed). */
sealed class IconShape {
    class P(val d: String) : IconShape()
    class Circle(val cx: Float, val cy: Float, val r: Float) : IconShape()
    class RRect(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float) : IconShape()
}

object Icons {
    private fun p(vararg d: String) = d.map { IconShape.P(it) }

    val Terminal = p("M5 7l5 5-5 5", "M13 17h6")
    val Phone = listOf(IconShape.RRect(7f, 2.5f, 10f, 19f, 2.5f), IconShape.P("M11 18.5h2"))
    val Bolt = p("M13 3L5 14h6l-1 7 8-11h-6z")
    val ShieldCheck = p("M12 3l8 3v6c0 4.5-3.2 8-8 9-4.8-1-8-4.5-8-9V6z", "M8.5 12l2.5 2.5 4.5-5")
    val ShieldMinus = p("M12 3l8 3v6c0 4.5-3.2 8-8 9-4.8-1-8-4.5-8-9V6z", "M9 12h6")
    val Warning = p("M12 3l10 18H2z", "M12 10v5", "M12 18h.01")
    val Copy = listOf(
        IconShape.RRect(8f, 8f, 12f, 12f, 2.5f),
        IconShape.P("M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2")
    )
    val Back = p("M15 5l-7 7 7 7")
    val Chevron = p("M9 5l7 7-7 7")
    val Check = p("M5 12.5l4.5 4.5L19 7.5")
    val Home = p("M4 11l8-7 8 7", "M6 10v9h12v-9")
    val List = p("M4 6h16M4 12h16M4 18h10")
    val Gear = listOf(
        IconShape.Circle(12f, 12f, 3f),
        IconShape.P("M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1")
    )
    val Eye = listOf(IconShape.P("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"), IconShape.Circle(12f, 12f, 3f))
    val EyeOff = listOf(IconShape.P("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"), IconShape.P("M4 4l16 16"))
    val Share = p("M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7", "M16 6l-4-4-4 4", "M12 2v13")
}

@Composable
fun Icon(shapes: List<IconShape>, size: Dp = 22.dp, tint: Color = C.Text, stroke: Float = 2f, modifier: Modifier = Modifier) {
    val paths = remember(shapes) {
        shapes.map {
            // A malformed path must never crash the UI: draw nothing instead.
            if (it is IconShape.P) runCatching { PathParser().parsePathString(it.d).toPath() }.getOrDefault(Path()) else null
        }
    }
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        val st = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(s, s, Offset.Zero) {
            shapes.forEachIndexed { i, shape ->
                when (shape) {
                    is IconShape.P -> drawPath(paths[i] as Path, tint, style = st)
                    is IconShape.Circle -> drawCircle(tint, shape.r, Offset(shape.cx, shape.cy), style = st)
                    is IconShape.RRect -> drawRoundRect(
                        tint, Offset(shape.x, shape.y), Size(shape.w, shape.h), CornerRadius(shape.r), style = st
                    )
                }
            }
        }
    }
}
