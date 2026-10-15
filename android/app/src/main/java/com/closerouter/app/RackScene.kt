package com.closerouter.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object Pal {
    val bg = Color(0xFFF6F5F7)
    val line = Color(0xFFB4B4BB)
    val lineSoft = Color(0xFFD8D8DD)
    val front = Color(0xFFFCFCFD)
    val side = Color(0xFFEDEDF0)
    val top = Color(0xFFF4F4F6)
    val orange = Color(0xFFF28A2E)
    val orangeDeep = Color(0xFFE8741C)
    val ink = Color(0xFF3A3A40)
    val mute = Color(0xFF9A9AA3)
}

const val BAY_COUNT = 7
val BAY_CODES = listOf("PWR", "PROV", "PORT", "TOKEN", "SPEED", "EXPORT", "KEEP")

private const val K = 0.8660254f
private const val RW = 6f
private const val RD = 2.8f
private const val RH = 7.2f
private const val RZ0 = 0.25f
private const val BX0 = 0.45f
private const val BX1 = RW - 0.45f
private const val BAY_H = 0.66f

private const val BAY_D = 1.4f
private const val MAC_D = 1.0f
private const val MY0 = 0.4f
private const val MY1 = 2.4f
private const val MZ0 = 5.3f
private const val MZ1 = 6.8f
private const val FAN_N = 4

private fun bayTop(i: Int) = RH - 1.75f - i * 0.72f
private const val PWR_X = 1.2f
private const val PWR_R = 0.55f
private const val JACK_X = 5.8f
private fun pwrZ() = RH - 0.875f

private open class Iso(val s: Float, val cx: Float, val cy: Float) {
    open fun p(x: Float, y: Float, z: Float) = Offset(cx + (x - y) * K * s, cy + (x + y) * 0.5f * s - z * s)
    fun f(x: Float, z: Float) = p(x, RD, z)
}

private class LidIso(val b: Iso, val th: Float, val hy: Float) : Iso(b.s, b.cx, b.cy) {
    private val cs = cos(th)
    private val sn = sin(th)
    override fun p(x: Float, y: Float, z: Float): Offset {
        val dy = y - hy
        val dz = z - RH
        return b.p(x, hy + dy * cs - dz * sn, RH + dy * sn + dz * cs)
    }
}

private const val LIFT_H = 1.5f
private const val LID_MAX = 1.08f
private const val CAV = 1.25f
private const val LID_Y = 0.3f

private fun isoFor(w: Float, h: Float, extra: Float = 0f): Iso {
    val spanH = RH + (RW + RD + 1.6f) / 2f + extra
    val s = min(w / 10.2f, h / (spanH + 0.3f))
    val xMin = (-0.8f - (RD + 0.8f)) * K
    val xMax = (RW + 1.6f) * K
    val cx = w / 2f - (xMin + xMax) / 2f * s
    val cy = (h - spanH * s) / 2f + (RH + extra) * s
    return Iso(s, cx, cy)
}

private fun inQuad(o: Offset, q: List<Offset>): Boolean {
    var pos = false; var neg = false
    for (i in q.indices) {
        val a = q[i]; val b = q[(i + 1) % q.size]
        val c = (b.x - a.x) * (o.y - a.y) - (b.y - a.y) * (o.x - a.x)
        if (c > 0) pos = true else if (c < 0) neg = true
    }
    return !(pos && neg)
}

private fun Iso.unprojY(o: Offset, y: Float): Pair<Float, Float> {
    val x = y + (o.x - cx) / (K * s)
    return x to ((x + y) * 0.5f * s + cy - o.y) / s
}

private fun Iso.unprojX(o: Offset, x: Float): Pair<Float, Float> {
    val y = x - (o.x - cx) / (K * s)
    return y to ((x + y) * 0.5f * s + cy - o.y) / s
}

private fun Iso.unprojZ(o: Offset, z: Float): Pair<Float, Float> {
    val d = (o.x - cx) / (K * s)
    val sum = 2f * ((o.y - cy) / s + z)
    return (sum + d) / 2f to (sum - d) / 2f
}

private fun quad(a: Offset, b: Offset, c: Offset, d: Offset) = Path().apply {
    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
}

private fun Iso.frontQuad(x0: Float, z0: Float, x1: Float, z1: Float) =
    quad(f(x0, z0), f(x1, z0), f(x1, z1), f(x0, z1))

private fun Iso.floorCircle(cx: Float, cy: Float, z: Float, r: Float, n: Int = 32) = Path().apply {
    for (k in 0..n) {
        val a = 2.0 * PI * k / n
        val o = p(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), z)
        if (k == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
    }
    close()
}

private fun Iso.frontCircle(xc: Float, zc: Float, r: Float, n: Int = 20) = Path().apply {
    for (k in 0..n) {
        val a = 2.0 * PI * k / n
        val o = f(xc + r * cos(a).toFloat(), zc + r * sin(a).toFloat())
        if (k == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
    }
    close()
}

private fun DrawScope.planeText(
    m: TextMeasurer, iso: Iso, text: String, x: Float, zMid: Float,
    fs: Float, color: Color, bold: Boolean = false, y: Float = RD
) {
    val origin = iso.p(x, y, zMid + (fs * 1.2f / 2f) / iso.s)
    val mat = Matrix()
    mat.values[Matrix.ScaleX] = K
    mat.values[Matrix.SkewY] = 0.5f
    mat.values[Matrix.TranslateX] = origin.x
    mat.values[Matrix.TranslateY] = origin.y
    withTransform({ transform(mat) }) {
        drawText(
            m, text, Offset.Zero,
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fs.toSp(),
                color = color,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
            ),
            softWrap = false, maxLines = 1
        )
    }
}

private val GLYPHS: Map<Char, List<String>> = mapOf(
    '0' to listOf("01110","10001","10011","10101","11001","10001","01110"),
    '1' to listOf("00100","01100","00100","00100","00100","00100","01110"),
    '2' to listOf("01110","10001","00001","00010","00100","01000","11111"),
    '3' to listOf("11110","00001","00001","01110","00001","00001","11110"),
    '4' to listOf("00010","00110","01010","10010","11111","00010","00010"),
    '5' to listOf("11111","10000","11110","00001","00001","10001","01110"),
    '6' to listOf("00110","01000","10000","11110","10001","10001","01110"),
    '7' to listOf("11111","00001","00010","00100","01000","01000","01000"),
    '8' to listOf("01110","10001","10001","01110","10001","10001","01110"),
    '9' to listOf("01110","10001","10001","01111","00001","00010","01100"),
    '.' to listOf("0","0","0","0","0","0","1"),
    '-' to listOf("00000","00000","00000","11111","00000","00000","00000"),
    ' ' to listOf("000","000","000","000","000","000","000"),
    '/' to listOf("00001","00010","00010","00100","01000","01000","10000"),
    'T' to listOf("11111","00100","00100","00100","00100","00100","00100"),
    'O' to listOf("01110","10001","10001","10001","10001","10001","01110"),
    'K' to listOf("10001","10010","10100","11000","10100","10010","10001"),
    'S' to listOf("01111","10000","10000","01110","00001","00001","11110"),
    'F' to listOf("11111","10000","10000","11110","10000","10000","10000"),
    'L' to listOf("10000","10000","10000","10000","10000","10000","11111"),
    'A' to listOf("01110","10001","10001","11111","10001","10001","10001"),
    'M' to listOf("10001","11011","10101","10101","10001","10001","10001"),
    'I' to listOf("01110","00100","00100","00100","00100","00100","01110"),
    'B' to listOf("11110","10001","10001","11110","10001","10001","11110"),
    's' to listOf("00000","01111","10000","01110","00001","11110","00000"),
    'a' to listOf("00000","01110","00001","01111","10001","01111","00000"),
    'g' to listOf("00000","01110","10001","10001","01111","00001","01110"),
    'u' to listOf("00000","10001","10001","10001","10001","01111","00000"),
)

/** 7 个硬盘侧面屏幕依次显示 s a 1 g u u + 像素爱心 */
private val DRIVE_CHARS = listOf('s', 'a', '1', 'g', 'u', 'u')
private val HEART = listOf("0110110", "1111111", "1111111", "0111110", "0011100", "0001000")

/** 圆角四边形（屏幕空间圆角）：角点按顺时针/逆时针给出，rFrac 为切角边长占比 */
private fun roundedQuadPath(pts: List<Offset>, rFrac: Float): Path {
    val path = Path()
    for (i in pts.indices) {
        val prev = pts[(i + pts.size - 1) % pts.size]
        val cur = pts[i]
        val next = pts[(i + 1) % pts.size]
        fun toward(to: Offset) = Offset(cur.x + (to.x - cur.x) * rFrac, cur.y + (to.y - cur.y) * rFrac)
        val inP = toward(prev); val outP = toward(next)
        if (i == 0) path.moveTo(inP.x, inP.y) else path.lineTo(inP.x, inP.y)
        path.quadraticTo(cur.x, cur.y, outP.x, outP.y)
    }
    path.close()
    return path
}

/** 像素矩阵画在 x=常数 的侧平面上，以 (yc, zc) 为中心；列沿 y 递减、行沿 z 递减 */
private fun Iso.pixelMatrix(x: Float, rows: List<String>, yc: Float, zc: Float, cell: Float): Path {
    val path = Path()
    val w = rows.maxOf { it.length }; val h = rows.size
    val g = cell * 0.84f
    val yTop = yc + w * cell / 2f
    val zTop = zc + h * cell / 2f
    for (r in 0 until h) for (c in 0 until rows[r].length) {
        if (rows[r][c] != '1') continue
        val y0 = yTop - c * cell
        val z0 = zTop - r * cell
        val o = p(x, y0, z0); val a = p(x, y0 - g, z0); val b = p(x, y0 - g, z0 - g); val d = p(x, y0, z0 - g)
        path.moveTo(o.x, o.y); path.lineTo(a.x, a.y); path.lineTo(b.x, b.y); path.lineTo(d.x, d.y); path.close()
    }
    return path
}

private fun fmtTokens(n: Long): String = when {
    n < 0 -> "--"
    n < 10_000 -> "$n"
    n < 100_000 -> "%.1fK".format(n / 1000.0)
    n < 1_000_000 -> "%.0fK".format(n / 1000.0)
    n < 100_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n < 1_000_000_000 -> "%.0fM".format(n / 1_000_000.0)
    else -> "%.1fB".format(n / 1_000_000_000.0)
}

/** Pixel text on the x=RW side plane; left-to-right on screen means y decreasing. */
private fun Iso.pixelPath(text: String, yStart: Float, zTop: Float, cell: Float): Path {
    val path = Path()
    var y = yStart
    val g = cell * 0.88f
    for (ch in text) {
        val rows = GLYPHS[ch] ?: GLYPHS[' ']!!
        val w = rows[0].length
        for (r in 0 until 7) for (c in 0 until w) {
            if (rows[r][c] != '1') continue
            val y0 = y - c * cell
            val z0 = zTop - r * cell
            val o = p(RW, y0, z0)
            val a = p(RW, y0 - g, z0); val b = p(RW, y0 - g, z0 - g); val d = p(RW, y0, z0 - g)
            path.moveTo(o.x, o.y); path.lineTo(a.x, a.y); path.lineTo(b.x, b.y); path.lineTo(d.x, d.y); path.close()
        }
        y -= (w + 1) * cell
    }
    return path
}

private fun glyphCols(text: String) = text.sumOf { (GLYPHS[it] ?: GLYPHS[' ']!!)[0].length + 1 }

private fun DrawScope.box(
    iso: Iso, x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float,
    top: Color, front: Color, side: Color, lw: Float, showTop: Boolean = true
) {
    if (z1 - z0 < 0.002f) return
    fun fc(p: Path, c: Color) { drawPath(p, c); drawPath(p, Pal.line, style = Stroke(lw, join = StrokeJoin.Round)) }
    fc(quad(iso.p(x0, y1, z0), iso.p(x1, y1, z0), iso.p(x1, y1, z1), iso.p(x0, y1, z1)), front)
    fc(quad(iso.p(x1, y0, z0), iso.p(x1, y1, z0), iso.p(x1, y1, z1), iso.p(x1, y0, z1)), side)
    if (showTop) fc(quad(iso.p(x0, y0, z1), iso.p(x1, y0, z1), iso.p(x1, y1, z1), iso.p(x0, y1, z1)), top)
}

private fun DrawScope.cyl(
    iso: Iso, cx: Float, cy: Float, z0: Float, z1: Float, r: Float,
    top: Color, body: Color, lw: Float
) {
    if (z1 - z0 < 0.002f) return
    val rx = r * K * 1.41421356f * iso.s
    val b = iso.p(cx, cy, z0)
    val t = iso.p(cx, cy, z1)
    val bot = iso.floorCircle(cx, cy, z0, r, 20)
    drawPath(bot, body); drawPath(bot, Pal.line, style = Stroke(lw))
    drawRect(body, Offset(b.x - rx, t.y), androidx.compose.ui.geometry.Size(2 * rx, b.y - t.y))
    drawLine(Pal.line, Offset(b.x - rx, t.y), Offset(b.x - rx, b.y), lw)
    drawLine(Pal.line, Offset(b.x + rx, t.y), Offset(b.x + rx, b.y), lw)
    val tp = iso.floorCircle(cx, cy, z1, r, 20)
    drawPath(tp, top); drawPath(tp, Pal.line, style = Stroke(lw))
}

private fun fanGeom(k: Int, pop0: Float): FloatArray? = when (k) {
    0 -> floatArrayOf(1.15f, RD / 2f, RH, 0.81f)
    1 -> floatArrayOf(RW / 2f, RD / 2f, RH, 0.81f)
    2 -> floatArrayOf(RW - 1.15f, RD / 2f, RH, 0.81f)
    else -> if (pop0 > 0.9f) floatArrayOf(4.6f, RD + pop0 * BAY_D - 0.7f, bayTop(0) + 0.05f, 0.5f) else null
}

private fun DrawScope.drawFan(
    iso: Iso, fx: Float, fy: Float, z: Float, sc: Float, angDeg: Float, fan: Float, glow: Float, lw: Float
) {
    val outer = iso.floorCircle(fx, fy, z, 0.807f * sc)
    drawPath(outer, Pal.front)
    drawPath(outer, Pal.line, style = Stroke(lw))
    val well = iso.floorCircle(fx, fy, z, 0.714f * sc)
    drawPath(well, Color(0xFFDCDCE1))
    drawPath(well, Pal.lineSoft, style = Stroke(lw))
    if (fan > 0.02f) drawPath(iso.floorCircle(fx, fy, z, 0.68f * sc), Pal.orange.copy(alpha = 0.12f * fan * glow))
    val bladeColor = lerp(Color(0xFFB9B9C1), Pal.orange, fan)
    for (j in 0 until 5) {
        val a = Math.toRadians(angDeg + j * 72.0).toFloat()
        fun pt(r: Float, da: Float) = iso.p(fx + r * sc * cos(a + da), fy + r * sc * sin(a + da), z)
        val blade = Path().apply {
            val p0 = pt(0.072f, -0.15f); moveTo(p0.x, p0.y)
            val p1 = pt(0.646f, 0.3f); val c1 = pt(0.425f, -0.2f); quadraticTo(c1.x, c1.y, p1.x, p1.y)
            val p2 = pt(0.646f, 0.85f); lineTo(p2.x, p2.y)
            val p3 = pt(0.085f, 0.5f); val c2 = pt(0.383f, 0.75f); quadraticTo(c2.x, c2.y, p3.x, p3.y)
            close()
        }
        drawPath(blade, bladeColor.copy(alpha = 0.55f))
        drawPath(blade, lerp(Pal.mute, Pal.orangeDeep, fan), style = Stroke(lw))
    }
    drawPath(iso.floorCircle(fx, fy, z, 0.425f * sc), Pal.line.copy(alpha = 0.6f), style = Stroke(lw))
    for (j in 0 until 4) {
        val a = (PI / 2 * j + PI / 4).toFloat()
        drawLine(
            Pal.line.copy(alpha = 0.7f), iso.p(fx + 0.085f * sc * cos(a), fy + 0.085f * sc * sin(a), z),
            iso.p(fx + 0.714f * sc * cos(a), fy + 0.714f * sc * sin(a), z), strokeWidth = lw
        )
    }
    val hub = iso.floorCircle(fx, fy, z, 0.111f * sc, 14)
    drawPath(hub, Pal.front)
    drawPath(hub, Pal.line, style = Stroke(lw))
    drawPath(iso.floorCircle(fx, fy, z, 0.043f * sc, 10), lerp(Pal.mute, Pal.orange, fan))
}

private fun DrawScope.bayTopParts(
    m: TextMeasurer, iso: Iso, i: Int, yb: Float, z1: Float, rise: Float, t: Float,
    fanAng: Float, fan: Float, glow: Float, lw: Float
) {
    if (rise <= 0.01f) return
    val w = Color.White
    val g1 = Color(0xFFE9E9ED)
    val g2 = Color(0xFFD6D6DC)
    val dk = Color(0xFF3A3A40)
    val dk2 = Color(0xFF2A2A2F)
    val dk3 = Color(0xFF202024)
    val or = Pal.orange
    val orF = Color(0xFFF7C79A)
    val orS = Color(0xFFE9A25A)
    // parts stay at most ~0.2 tall and must clear the rack face behind them
    fun clear(v1: Float, h: Float) = yb - v1 >= RD + h * rise + 0.02f
    fun bx(x0: Float, x1: Float, v0: Float, v1: Float, h0: Float, h1: Float,
           top: Color = w, fr: Color = g1, sd: Color = g2) {
        if (!clear(v1, h1)) return
        box(iso, x0, x1, yb - v1, yb - v0, z1 + h0 * rise, z1 + h1 * rise, top, fr, sd, lw)
    }
    fun flat(x0: Float, x1: Float, v0: Float, v1: Float, h: Float, fill: Color?, edge: Color? = null) {
        if (!clear(v1, h)) return
        val zz = z1 + h * rise
        val q = quad(iso.p(x0, yb - v0, zz), iso.p(x1, yb - v0, zz), iso.p(x1, yb - v1, zz), iso.p(x0, yb - v1, zz))
        if (fill != null) drawPath(q, fill)
        if (edge != null) drawPath(q, edge, style = Stroke(lw, join = StrokeJoin.Round))
    }
    fun seg(x0: Float, v0: Float, x1: Float, v1: Float, h: Float, col: Color, wd: Float = 1.5f) {
        if (!clear(max(v0, v1), h)) return
        drawLine(col, iso.p(x0, yb - v0, z1 + h * rise), iso.p(x1, yb - v1, z1 + h * rise), strokeWidth = wd.dp.toPx(), cap = StrokeCap.Round)
    }
    fun dot(x: Float, v: Float, h: Float, r: Float, col: Color) {
        if (!clear(v, h)) return
        drawCircle(col, r.dp.toPx(), iso.p(x, yb - v, z1 + h * rise))
    }
    fun disc(x: Float, v: Float, h0: Float, h1: Float, r: Float, top: Color, body: Color = g1) {
        if (!clear(v + r, h1)) return
        cyl(iso, x, yb - v, z1 + h0 * rise, z1 + h1 * rise, r, top, body, lw)
    }
    fun label(text: String, x: Float, v: Float, h: Float, fs: Float, col: Color, bold: Boolean = false) {
        if (!clear(v + fs, h)) return
        topText(m, iso, text, x, yb - v - fs * 0.6f, z1 + h * rise, fs * iso.s, col, bold)
    }
    fun plate(x0: Float, x1: Float) = bx(x0, x1, 0.12f, 1.22f, 0f, 0.04f)

    // two rows on every unit: controls along the front edge (v 0.2-0.45), main parts behind (v 0.55-1.1)
    flat(0.5f, 5.5f, 0.07f, 1.3f, 0f, null, Pal.lineSoft)
    when (i) {
        0 -> {
            plate(0.7f, 3.8f)
            for (k in 0 until 5) disc(1.05f + k * 0.62f, 0.86f, 0.04f, 0.2f, 0.17f, or)
            label("OUT 12V", 0.85f, 0.3f, 0.045f, 0.13f, Pal.mute)
            val lvl = (4 + (3.5f * (1f + sin(t * 1.7f))).toInt()).coerceAtMost(8)
            for (j in 0 until 8) flat(1.75f + j * 0.25f, 1.75f + j * 0.25f + 0.17f, 0.22f, 0.4f, 0.045f, if (j < lvl) or else g2)
            plate(4.0f, 5.2f)
            if (clear(1.2f, 0.05f)) drawFan(iso, 4.6f, yb - 0.7f, z1 + 0.05f * rise, 0.56f, fanAng, fan, glow, lw)
        }
        1 -> {
            plate(0.7f, 5.3f)
            for (k in 0 until 5) {
                val on = sin(t * 3f + k * 1.7f) > -0.3f
                val x0 = 0.9f + k * 0.88f
                bx(x0, x0 + 0.7f, 0.55f, 1.1f, 0.04f, 0.14f, if (on) or else w, if (on) orF else g1, if (on) orS else g2)
                label("P${k + 1}", x0 + 0.23f, 0.83f, 0.14f, 0.16f, if (on) w else Pal.ink, true)
                dot(x0 + 0.35f, 0.32f, 0.045f, 2.2f, if (on) or else g2)
            }
        }
        2 -> {
            plate(0.7f, 5.3f)
            for (k in 0 until 4) {
                val x0 = 0.9f + k * 0.78f
                bx(x0, x0 + 0.6f, 0.55f, 1.1f, 0.04f, 0.14f)
                flat(x0 + 0.1f, x0 + 0.5f, 0.68f, 0.97f, 0.14f, dk, Pal.line)
                dot(x0 + 0.17f, 0.32f, 0.045f, 2f, if (sin(t * 4f + k * 1.3f) > 0f) or else g2)
                dot(x0 + 0.43f, 0.32f, 0.045f, 2f, if (sin(t * 7f + k * 2.1f) > 0.4f) or else g2)
            }
            bx(4.2f, 5.1f, 0.55f, 1.1f, 0.04f, 0.12f, or, orF, orS)
            label("LAN", 4.5f, 0.83f, 0.12f, 0.16f, w, true)
            disc(4.65f, 0.32f, 0.04f, 0.1f, 0.12f, g1, w)
        }
        3 -> {
            plate(0.7f, 5.3f)
            for (k in 0 until 8) {
                val x0 = 0.95f + k * 0.38f
                bx(x0, x0 + 0.26f, 0.55f, 1.1f, 0.04f, 0.06f + 0.14f * abs(sin(t * 1.5f + k * 0.8f)), or, orF, orS)
            }
            for (q in 0..15) seg(0.95f + q * 0.19f, 0.27f, 0.95f + q * 0.19f, if (q % 4 == 0) 0.42f else 0.35f, 0.045f, Pal.mute, 1f)
            bx(4.2f, 5.3f, 0.2f, 1.1f, 0.04f, 0.1f, dk, dk2, dk3)
            label("TOK", 4.45f, 0.84f, 0.1f, 0.22f, or, true)
            for (k in 0 until 3) dot(4.45f + k * 0.25f, 0.4f, 0.1f, 1.8f, if ((t * 3f).toInt() % 3 == k) or else Pal.mute)
        }
        4 -> {
            val cx = 1.7f; val cv = 0.67f
            disc(cx, cv, 0f, 0.1f, 0.52f, g1, w)
            if (clear(cv + 0.52f, 0.1f)) {
                val zd = z1 + 0.1f * rise + 0.002f
                val face = iso.floorCircle(cx, yb - cv, zd, 0.44f, 28)
                drawPath(face, w); drawPath(face, Pal.line, style = Stroke(lw))
                for (k in 0..8) {
                    val a = Math.toRadians(150.0 + k * 30.0).toFloat()
                    drawLine(Pal.mute, iso.p(cx + 0.36f * cos(a), yb - cv + 0.36f * sin(a), zd),
                        iso.p(cx + 0.44f * cos(a), yb - cv + 0.44f * sin(a), zd), strokeWidth = lw)
                }
                val na = Math.toRadians(205.0 + 100.0 * sin(2.6 + 1.7 * sin(t * 1.1))).toFloat()
                drawLine(Pal.orangeDeep, iso.p(cx, yb - cv, zd), iso.p(cx + 0.34f * cos(na), yb - cv + 0.34f * sin(na), zd), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Pal.ink, 2.dp.toPx(), iso.p(cx, yb - cv, zd))
            }
            plate(2.7f, 5.3f)
            val lvl = 1 + ((t * 2f).toInt() % 4)
            for (k in 0 until 4) {
                val x0 = 3.0f + k * 0.5f
                bx(x0, x0 + 0.36f, 0.55f, 1.1f, 0.04f, 0.1f + 0.033f * k, if (k < lvl) or else g2, w, g2)
            }
            for (k in 0 until 3) dot(3.15f + k * 0.35f, 0.32f, 0.045f, 1.8f, if ((t * 3f).toInt() % 3 == k) or else g2)
        }
        5 -> {
            plate(0.7f, 2.9f)
            val su = (t * 0.45f) % 1f
            val s0 = 0.4f + 0.2f * su
            bx(0.95f, 2.65f, s0, s0 + 0.5f, 0.04f, 0.08f)
            for (k in 0 until 3) seg(1.15f, s0 + 0.14f + k * 0.12f, 2.45f, s0 + 0.14f + k * 0.12f, 0.08f, Pal.lineSoft, 1f)
            plate(3.0f, 5.3f)
            if (clear(0.9f, 0.045f)) {
                val zz = z1 + 0.045f * rise
                val tri = Path().apply {
                    val a1 = iso.p(3.15f, yb - 0.9f, zz); val a2 = iso.p(3.15f, yb - 0.6f, zz); val a3 = iso.p(3.45f, yb - 0.75f, zz)
                    moveTo(a1.x, a1.y); lineTo(a2.x, a2.y); lineTo(a3.x, a3.y); close()
                }
                drawPath(tri, or)
            }
            bx(3.7f, 5.1f, 0.55f, 1.1f, 0.04f, 0.14f, or, orF, orS)
            label("EXE", 4.18f, 0.83f, 0.14f, 0.16f, w, true)
            flat(3.7f, 5.1f, 0.2f, 0.38f, 0.045f, g2, Pal.lineSoft)
            flat(3.7f, 3.7f + 1.4f * su, 0.2f, 0.38f, 0.05f, or)
        }
        else -> {
            plate(0.7f, 3.5f)
            bx(0.85f, 3.35f, 0.5f, 1.1f, 0.04f, 0.07f, dk, dk2, dk3)
            run {
                var px = 0.95f; var pv = 0.8f
                val ph = (t * 0.8f) % 1f
                for (k in 1..24) {
                    val nx = 0.95f + k * 0.1f
                    val d = abs(nx - (0.95f + ph * 2.4f))
                    val nv = 0.8f + (if (d < 0.3f) 0.2f * sin(d * 20f) * (1f - d / 0.3f) * (if (k % 2 == 0) 1f else -1f) else 0f)
                    seg(px, pv, nx, nv, 0.075f, or, 1.4f); px = nx; pv = nv
                }
            }
            for (k in 0 until 4) dot(0.95f + k * 0.3f, 0.3f, 0.045f, 2.2f, if ((t * 2f).toInt() % 4 == k) or else g2)
            label("KEEP ALIVE", 2.4f, 0.3f, 0.045f, 0.13f, Pal.mute)
            plate(3.7f, 5.3f)
            disc(4.5f, 0.67f, 0.04f, 0.14f, 0.36f, w, g1)
            if (clear(0.67f + 0.36f, 0.14f)) {
                val zd = z1 + 0.14f * rise + 0.002f
                drawPath(iso.floorCircle(4.5f, yb - 0.67f, zd, 0.27f, 24), or.copy(alpha = 0.45f + 0.4f * sin(t * 3f)), style = Stroke(2.dp.toPx()))
                drawCircle(or, 3.dp.toPx(), iso.p(4.5f, yb - 0.67f, zd))
            }
        }
    }
}

private fun DrawScope.drawMac(iso: Iso, m: Float, on: Boolean, bt: Float, t: Float, lw: Float) {
    val d = m.coerceIn(0f, 1.15f) * MAC_D
    val xf = RW + d
    val slot = quad(iso.p(RW, MY0, MZ1), iso.p(RW, MY1, MZ1), iso.p(RW, MY1, MZ0), iso.p(RW, MY0, MZ0))
    drawPath(slot, Color(0xFF2A2A2F)); drawPath(slot, Pal.line, style = Stroke(lw))
    fun face(p: Path, c: Color) { drawPath(p, c); drawPath(p, Pal.line, style = Stroke(lw, join = StrokeJoin.Round)) }
    if (d > 0.003f) {
        face(quad(iso.p(RW, MY0, MZ1), iso.p(xf, MY0, MZ1), iso.p(xf, MY1, MZ1), iso.p(RW, MY1, MZ1)), Pal.top)
        face(quad(iso.p(RW, MY1, MZ0), iso.p(xf, MY1, MZ0), iso.p(xf, MY1, MZ1), iso.p(RW, MY1, MZ1)), Pal.front)
        if (d > 0.45f) {
            for (k in 0 until 5) {
                val x = RW + 0.2f + k * 0.07f
                if (x < xf - 0.1f) drawLine(Pal.lineSoft, iso.p(x, MY0 + 0.4f, MZ1), iso.p(x, MY1 - 0.4f, MZ1), strokeWidth = lw)
            }
            var x = RW + 0.2f
            while (x < xf - 0.12f) {
                drawLine(Pal.lineSoft, iso.p(x, MY1, MZ0 + 0.25f), iso.p(x, MY1, MZ0 + 0.7f), strokeWidth = lw)
                x += 0.08f
            }
        }
    }
    face(quad(iso.p(xf, MY0, MZ1), iso.p(xf, MY1, MZ1), iso.p(xf, MY1, MZ0), iso.p(xf, MY0, MZ0)), Color(0xFFF4F4F7))
    val bez = quad(iso.p(xf, MY0 + 0.14f, MZ1 - 0.12f), iso.p(xf, MY1 - 0.14f, MZ1 - 0.12f), iso.p(xf, MY1 - 0.14f, MZ0 + 0.34f), iso.p(xf, MY0 + 0.14f, MZ0 + 0.34f))
    drawPath(bez, Color(0xFFDCDCE1)); drawPath(bez, Pal.lineSoft, style = Stroke(lw))

    val sy0 = MY0 + 0.22f; val sy1 = MY1 - 0.22f; val sz0 = MZ0 + 0.42f; val sz1 = MZ1 - 0.2f
    val pOn = if (on) ((bt - 0.15f) / 0.45f).coerceIn(0f, 1f) else 0f
    val scr = quad(iso.p(xf, sy0, sz1), iso.p(xf, sy1, sz1), iso.p(xf, sy1, sz0), iso.p(xf, sy0, sz0))
    drawPath(scr, lerp(Color(0xFF2E2E34), Color(0xFFFFE8D6), pOn))
    drawPath(scr, Pal.line, style = Stroke(lw))

    val cols = 16; val rows = 11
    val cw = (sy1 - sy0) / cols; val ch = (sz1 - sz0) / rows
    val strong = Path(); val dim = Path()
    fun dot(path: Path, c: Int, r: Int) {
        val yc = sy1 - (c + 0.5f) * cw; val zc = sz1 - (r + 0.5f) * ch
        val hw = cw * 0.36f; val hh = ch * 0.36f
        val a = iso.p(xf, yc + hw, zc + hh); val b = iso.p(xf, yc - hw, zc + hh)
        val c2 = iso.p(xf, yc - hw, zc - hh); val d2 = iso.p(xf, yc + hw, zc - hh)
        path.moveTo(a.x, a.y); path.lineTo(b.x, b.y); path.lineTo(c2.x, c2.y); path.lineTo(d2.x, d2.y); path.close()
    }
    fun glyph(chr: Char, c0: Int, r0: Int) {
        val g = GLYPHS[chr] ?: return
        for (r in g.indices) for (c in g[r].indices) if (g[r][c] == '1') dot(strong, c0 + c, r0 + r)
    }
    if (pOn > 0.99f) {
        if (bt < 2.6f) {
            if (bt > 0.7f && !(bt in 0.9f..1.0f)) { glyph('A', 3, 1); glyph('I', 9, 1) }
            val prog = ((bt - 0.7f) / 1.7f).coerceIn(0f, 1f)
            val n = (prog * 10).toInt()
            for (c in 0 until 10) dot(if (c < n) strong else dim, 3 + c, 9)
        } else {
            val tc = bt - 2.6f
            val period = 1.7f
            val n = (tc / period).toInt()
            val f = (tc - n * period) / period
            for (s in 0..3) {
                val mi = n - 3 + s
                if (mi < 0) continue
                val row = 1 + 2 * s
                val ai = mi % 2 == 1
                val len = 5 + (mi * 5 + 3) % 8
                val shown = if (mi == n) ((f / 0.7f).coerceIn(0f, 1f) * len).toInt() else len
                for (k in 0 until shown) {
                    if (k % 5 == 4) continue
                    dot(if (ai) strong else dim, if (ai) 14 - k else 1 + k, row)
                }
            }
            if (f > 0.7f) {
                val nextAi = (n + 1) % 2 == 1
                for (k in 0..2) if (((t * 6f).toInt() + k) % 3 == 0) dot(dim, if (nextAi) 14 - k else 1 + k, 9)
            }
        }
        drawPath(dim, Pal.orange.copy(alpha = 0.45f))
        drawPath(strong, Pal.orangeDeep)
        drawPath(strong, Pal.orange.copy(alpha = 0.25f), style = Stroke(2.dp.toPx()))
    }
    drawLine(Color(0xFF3A3A40), iso.p(xf, MY0 + 0.7f, MZ0 + 0.17f), iso.p(xf, MY1 - 0.7f, MZ0 + 0.17f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    val ledOn = on && sin(t * 3f) > -0.2f
    drawCircle(if (ledOn) Pal.orange else Color(0xFFC8C8CE), 2.dp.toPx(), iso.p(xf, MY0 + 0.3f, MZ0 + 0.17f))
}


private val TRACES = listOf(
    listOf(0.5f to 0.5f, 1.5f to 0.5f, 1.9f to 0.9f, 3.3f to 0.9f, 3.7f to 1.3f, 5.6f to 1.3f),
    listOf(0.5f to 1.0f, 1.2f to 1.0f, 1.6f to 1.4f, 2.8f to 1.4f, 3.2f to 1.8f, 5.6f to 1.8f),
    listOf(0.5f to 1.5f, 0.9f to 1.9f, 2.2f to 1.9f, 2.6f to 2.3f, 5.6f to 2.3f),
    listOf(1.0f to 0.38f, 4.9f to 0.38f, 5.3f to 0.78f, 5.7f to 0.78f),
    listOf(2.9f to 0.38f, 2.9f to 1.0f),
    listOf(4.4f to 1.3f, 4.4f to 1.8f)
)

private fun DrawScope.drawPcb(iso: Iso, lh: Float, t: Float, flow: Float, active: Boolean, lw: Float) {
    val z = RZ0 + 0.07f
    val x0 = 0.15f; val x1 = RW - 0.15f; val y0 = 0.25f; val y1 = RD - 0.1f
    val gold = Color(0xFFD2AC52)
    drawPath(quad(iso.p(x0, y1, RZ0 + 0.02f), iso.p(x1, y1, RZ0 + 0.02f), iso.p(x1, y1, z), iso.p(x0, y1, z)), Color(0xFF1B3A2F))
    drawPath(quad(iso.p(x1, y0, RZ0 + 0.02f), iso.p(x1, y1, RZ0 + 0.02f), iso.p(x1, y1, z), iso.p(x1, y0, z)), Color(0xFF16302A))
    val board = quad(iso.p(x0, y0, z), iso.p(x1, y0, z), iso.p(x1, y1, z), iso.p(x0, y1, z))
    drawPath(board, Color(0xFF2C5A4B))
    drawPath(board, Color(0xFF1E3F34), style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
    val zt = z + 0.004f
    val pm = PathMeasure()
    for ((ti, tr) in TRACES.withIndex()) {
        val path = Path()
        for ((k, pt) in tr.withIndex()) {
            val o = iso.p(pt.first, pt.second, zt)
            if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, gold.copy(alpha = 0.85f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (active) {
            pm.setPath(path, false)
            val len = pm.length
            for (k in 0 until 2) {
                val u = (flow * (1.1f + ti * 0.13f) + k * 0.5f + ti * 0.17f) % 1f
                val pos = pm.getPosition(u * len)
                drawCircle(Pal.orange.copy(alpha = 0.35f), 5.dp.toPx(), pos)
                drawCircle(Color.White, 2.2.dp.toPx(), pos)
            }
        }
    }
    for ((hx, hy) in listOf(x0 + 0.2f to y0 + 0.2f, x1 - 0.2f to y0 + 0.2f, x1 - 0.2f to y1 - 0.2f, x0 + 0.2f to y1 - 0.2f)) {
        val c = iso.floorCircle(hx, hy, zt, 0.09f, 12)
        drawPath(c, gold); drawPath(c, Color(0xFF1E3F34), style = Stroke(lw))
    }
    val dk = Color(0xFF2A2A2F); val dk3 = Color(0xFF18181B)
    fun pcbPins(xa: Float, xb: Float, ya: Float, yb: Float, zz: Float) {
        var x = xa + 0.05f
        while (x < xb - 0.02f) {
            drawLine(Pal.mute, iso.p(x, ya - 0.05f, zz), iso.p(x, ya, zz), strokeWidth = lw)
            drawLine(Pal.mute, iso.p(x, yb, zz), iso.p(x, yb + 0.05f, zz), strokeWidth = lw)
            x += 0.1f
        }
    }
    fun ic(xa: Float, xb: Float, ya: Float, yb: Float, h: Float) {
        box(iso, xa, xb, ya, yb, z, z + h, Color(0xFF3A3A40), dk, dk3, lw)
        pcbPins(xa, xb, ya, yb, z + h * 0.4f)
        drawCircle(if (active && sin(t * 4f + xa * 3f) > 0f) Pal.orange else Color(0xFF55555B), 1.2.dp.toPx(), iso.p(xa + 0.1f, yb - 0.1f, z + h))
    }
    ic(0.8f, 1.5f, 1.1f, 1.6f, 0.1f)
    box(iso, 0.55f, 1.05f, 2.15f, 2.5f, z, z + 0.16f, Color(0xFFD9BC82), Color(0xFFB59A6A), Color(0xFF9A8258), lw)
    box(iso, 2.3f, 3.5f, 1.0f, 1.9f, z, z + 0.14f, Color(0xFF3A3A40), dk, dk3, lw)
    pcbPins(2.3f, 3.5f, 1.0f, 1.9f, z + 0.06f)
    val die = quad(iso.p(2.65f, 1.25f, z + 0.145f), iso.p(3.15f, 1.25f, z + 0.145f), iso.p(3.15f, 1.65f, z + 0.145f), iso.p(2.65f, 1.65f, z + 0.145f))
    drawPath(die, Color(0xFF4A4A54)); drawPath(die, Pal.orange.copy(alpha = if (active) 0.5f + 0.4f * sin(t * 3f) else 0.15f), style = Stroke(1.2.dp.toPx()))
    ic(4.0f, 4.8f, 1.0f, 1.35f, 0.08f)
    ic(4.0f, 4.8f, 1.6f, 1.95f, 0.08f)
    for (k in 0 until 3) cyl(iso, 1.8f + k * 0.22f, 2.25f, z, z + 0.2f, 0.09f, Color(0xFFDADCE0), Color(0xFF8E96A0), lw)
    box(iso, 5.0f, 5.7f, 1.95f, 2.55f, z, z + 0.32f, Color(0xFFD5D7DC), Color(0xFFB4B8C0), Color(0xFF9EA3AC), lw)
    val port = quad(iso.p(5.1f, 2.55f, z + 0.2f), iso.p(5.6f, 2.55f, z + 0.2f), iso.p(5.6f, 2.55f, z + 0.07f), iso.p(5.1f, 2.55f, z + 0.07f))
    drawPath(port, Color(0xFF202024))
    drawCircle(if (active) Color(0xFF58C27D) else Color(0xFF55555B), 1.6.dp.toPx(), iso.p(5.15f, 2.55f, z + 0.27f))
    drawCircle(if (active && sin(t * 7f) > 0f) Pal.orange else Color(0xFF55555B), 1.6.dp.toPx(), iso.p(5.55f, 2.55f, z + 0.27f))
    for (k in 0 until 5) {
        val on = active && sin(t * 2.5f - k * 0.8f) > 0f
        val seg = quad(iso.p(0.7f + k * 0.25f, y1 - 0.28f, zt), iso.p(0.9f + k * 0.25f, y1 - 0.28f, zt), iso.p(0.9f + k * 0.25f, y1 - 0.12f, zt), iso.p(0.7f + k * 0.25f, y1 - 0.12f, zt))
        drawPath(seg, if (on) Pal.orange else Color(0xFF3F6E5E))
    }
}

private const val DRV_N = 7
private const val DRV_X0 = 0.65f
private const val DRV_PITCH = 0.7f
private const val DRV_T = 0.5f
private const val DRV_Y0 = 1.05f
private const val DRV_Y1 = 2.0f
private const val DRV_H = 1.1f
private const val DRV_UP = 0.75f
private val CAD_TOP = Color(0xFFE4E6EA)
private val CAD_FRONT = Color(0xFFCBCED5)
private val CAD_SIDE = Color(0xFFB4B8C0)

private fun drvX(k: Int) = DRV_X0 + k * DRV_PITCH
private fun drvTop(e: Float) = RH - 0.04f + e * DRV_UP

private fun driveHit(iso: Iso, o: Offset, e: FloatArray): Int {
    val order = (0 until DRV_N).sortedByDescending { drvX(it) + e[it] + if (drvTop(e[it]) > RH) 10f else 0f }
    for (k in order) {
        val top = drvTop(e[k]); val zl = max(top - DRV_H, RH - 0.25f)
        val x0 = drvX(k); val x1 = x0 + DRV_T
        val faces = listOf(
            listOf(iso.p(x0, DRV_Y0, top), iso.p(x1, DRV_Y0, top), iso.p(x1, DRV_Y1, top), iso.p(x0, DRV_Y1, top)),
            listOf(iso.p(x0, DRV_Y1, zl), iso.p(x1, DRV_Y1, zl), iso.p(x1, DRV_Y1, top), iso.p(x0, DRV_Y1, top)),
            listOf(iso.p(x1, DRV_Y0, zl), iso.p(x1, DRV_Y1, zl), iso.p(x1, DRV_Y1, top), iso.p(x1, DRV_Y0, top))
        )
        if (faces.any { inQuad(o, it) }) return k
    }
    return -1
}

/** Text lying on a horizontal plane; (x, y) is the top-left corner, text runs along +x. */
private fun DrawScope.topText(
    m: TextMeasurer, iso: Iso, text: String, x: Float, y: Float, z: Float,
    fs: Float, color: Color, bold: Boolean = false
) {
    val o = iso.p(x, y, z)
    val mat = Matrix()
    mat.values[Matrix.ScaleX] = K
    mat.values[Matrix.SkewY] = 0.5f
    mat.values[Matrix.SkewX] = -K
    mat.values[Matrix.ScaleY] = 0.5f
    mat.values[Matrix.TranslateX] = o.x
    mat.values[Matrix.TranslateY] = o.y
    withTransform({ transform(mat) }) {
        drawText(
            m, text, Offset.Zero,
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fs.toSp(),
                color = color,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
            ),
            softWrap = false, maxLines = 1
        )
    }
}

private fun DrawScope.driveTopDeco(m: TextMeasurer, iso: Iso, k: Int, top: Float, on: Boolean, t: Float, lw: Float) {
    val x0 = drvX(k); val x1 = x0 + DRV_T
    topText(m, iso, "D${k + 1}", x0 + 0.1f, DRV_Y0 + 0.1f, top, 0.15f * iso.s, Pal.ink, true)
    val ledOn = on && sin(t * 5f + k * 2.1f) > -0.2f
    drawCircle(if (ledOn) Pal.orange else Color(0xFFB0B0B8), 2.dp.toPx(), iso.p(x0 + DRV_T / 2f, 1.45f, top))
    for (q in 0 until 3) {
        val y = 1.53f + q * 0.07f
        drawLine(Pal.lineSoft, iso.p(x0 + 0.1f, y, top), iso.p(x1 - 0.1f, y, top), strokeWidth = lw)
    }
    val latch = quad(iso.p(x0 + 0.08f, 1.72f, top), iso.p(x1 - 0.08f, 1.72f, top), iso.p(x1 - 0.08f, 1.88f, top), iso.p(x0 + 0.08f, 1.88f, top))
    drawPath(latch, Pal.orange.copy(alpha = 0.9f)); drawPath(latch, Pal.orangeDeep, style = Stroke(lw))
}

/** Drive bodies below the rim, drawn inside the clipped cavity. */
private fun DrawScope.drawDrivesBelow(m: TextMeasurer, iso: Iso, e: FloatArray, running: Boolean, t: Float, lw: Float) {
    for (k in 0 until DRV_N) {
        val top = drvTop(e[k]); val x0 = drvX(k)
        val flush = top <= RH
        box(iso, x0, x0 + DRV_T, DRV_Y0, DRV_Y1, top - DRV_H, min(top, RH), CAD_TOP, CAD_FRONT, CAD_SIDE, lw, showTop = flush)
        if (flush) driveTopDeco(m, iso, k, top, running, t, lw)
    }
}

/** The part of an ejected drive that sticks out above the rim, drawn on top of the lid. */
private fun DrawScope.drawDrivesAbove(m: TextMeasurer, iso: Iso, e: FloatArray, running: Boolean, t: Float, lw: Float) {
    val order = (0 until DRV_N).filter { drvTop(e[it]) > RH + 0.004f }.sortedBy { drvX(it) + e[it] }
    for (k in order) {
        val top = drvTop(e[k]); val x0 = drvX(k); val x1 = x0 + DRV_T
        box(iso, x0, x1, DRV_Y0, DRV_Y1, RH, top, CAD_TOP, CAD_FRONT, CAD_SIDE, lw)
        driveTopDeco(m, iso, k, top, running, t, lw)
        val side = quad(iso.p(x1, DRV_Y0, RH), iso.p(x1, DRV_Y1, RH), iso.p(x1, DRV_Y1, top), iso.p(x1, DRV_Y0, top))
        clipPath(side) {
            // 内嵌小屏幕：深色圆角面板 + 像素字符；7 个盘拼出 sa1guu + 爱心，亮度轻微呼吸
            val visH = top - RH
            if (visH > 0.22f) {
                val yc = (DRV_Y0 + DRV_Y1) / 2f
                val zc = (RH + top) / 2f
                val sh = min(0.58f, visH * 0.76f)
                val sw = 0.68f
                val b = 0.5f + 0.5f * sin(t * 3.1f + k * 1.3f)
                fun scrQuad(yHalf: Float, zHalf: Float, r: Float) = roundedQuadPath(
                    listOf(
                        iso.p(x1, yc + yHalf, zc + zHalf),
                        iso.p(x1, yc - yHalf, zc + zHalf),
                        iso.p(x1, yc - yHalf, zc - zHalf),
                        iso.p(x1, yc + yHalf, zc - zHalf)
                    ), r
                )
                val bez = scrQuad(sw / 2f + 0.055f, sh / 2f + 0.06f, 0.18f)
                drawPath(bez, Color(0xFF141418))
                drawPath(bez, Pal.line, style = Stroke(lw))
                val scr = scrQuad(sw / 2f, sh / 2f, 0.14f)
                drawPath(scr, lerp(Color(0xFF1B1D24), Color(0xFF2C2833), b))
                if (k == DRV_N - 1) {
                    val cell = min(sw * 0.66f / 7f, sh * 0.66f / 6f)
                    val hp = iso.pixelMatrix(x1, HEART, yc, zc, cell)
                    drawPath(hp, Pal.orange.copy(alpha = 0.6f + 0.4f * b))
                    drawPath(hp, Pal.orangeDeep.copy(alpha = 0.3f * b), style = Stroke(2.dp.toPx()))
                } else {
                    val rows = GLYPHS[DRIVE_CHARS[k]] ?: GLYPHS[' ']!!
                    val cell = min(sw * 0.66f / 5f, sh * 0.66f / 7f)
                    drawPath(iso.pixelMatrix(x1, rows, yc, zc, cell), Pal.orange.copy(alpha = 0.6f + 0.4f * b))
                }
            }
        }
    }
}

private fun DrawScope.drawCavity(m: TextMeasurer, iso: Iso, e: FloatArray, running: Boolean, t: Float, lw: Float) {
    val zf = RH - CAV
    val a = LID_Y; val b = RW - LID_Y; val c = LID_Y; val d = RD - LID_Y
    val opening = quad(iso.p(a, c, RH), iso.p(b, c, RH), iso.p(b, d, RH), iso.p(a, d, RH))
    val rib = Color(0xFF6A6A72)
    clipPath(opening) {
        drawPath(quad(iso.p(a, c, zf), iso.p(b, c, zf), iso.p(b, d, zf), iso.p(a, d, zf)), Color(0xFF3B3B42))
        drawPath(quad(iso.p(a, c, zf), iso.p(b, c, zf), iso.p(b, c, RH), iso.p(a, c, RH)), Color(0xFF55555C))
        drawPath(quad(iso.p(a, c, zf), iso.p(a, d, zf), iso.p(a, d, RH), iso.p(a, c, RH)), Color(0xFF4A4A51))
        var vy = 0.7f
        while (vy < d - 0.1f) {
            drawLine(rib, iso.p(a, vy, RH - 0.6f), iso.p(a, vy, RH - 0.2f), strokeWidth = lw)
            vy += 0.25f
        }
        val bp = quad(iso.p(0.5f, c, RH - 0.72f), iso.p(b - 0.2f, c, RH - 0.72f), iso.p(b - 0.2f, c, RH - 0.1f), iso.p(0.5f, c, RH - 0.1f))
        drawPath(bp, Color(0xFF2B2B31)); drawPath(bp, rib, style = Stroke(lw))
        planeText(m, iso, "DISK ARRAY", 0.7f, RH - 0.3f, 0.19f * iso.s, Pal.orange.copy(alpha = 0.9f), true, c)
        planeText(m, iso, "7 BAY  HOT SWAP", 0.7f, RH - 0.54f, 0.13f * iso.s, Pal.mute, false, c)
        for (k in 0 until DRV_N) {
            val on = running && e[k] < 0.3f && sin(t * (2.5f + k * 0.7f) + k) > -0.2f
            val lc = iso.p(3.3f + k * 0.33f, c, RH - 0.34f)
            if (on) drawCircle(Pal.orange.copy(alpha = 0.3f), 5.dp.toPx(), lc)
            drawCircle(if (on) Pal.orange else Color(0xFF5A5A62), 2.2.dp.toPx(), lc)
        }
        drawDrivesBelow(m, iso, e, running, t, lw)
    }
}

@Composable
fun RackScene(
    selected: Int,
    running: Boolean,
    lit: List<Boolean>,
    readouts: List<String>,
    busy: Boolean,
    tokPerSec: Double,
    ttftMs: Long,
    totalTokens: Long,
    onPower: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val onSelectNow by rememberUpdatedState(onSelect)
    val onPowerNow by rememberUpdatedState(onPower)
    val spinSpeed = animateFloatAsState(if (running) 1f else 0f, tween(1400), label = "spin")
    val glowOn = animateFloatAsState(if (running) 1f else 0f, tween(900), label = "glow")
    val litA = List(BAY_COUNT) { animateFloatAsState(if (lit[it]) 1f else 0f, tween(600), label = "lit$it") }
    val busyNow by rememberUpdatedState(busy)
    val runningNow by rememberUpdatedState(running)
    val popTarget = remember { mutableStateListOf(*Array(BAY_COUNT) { false }) }
    val popA = List(BAY_COUNT) {
        animateFloatAsState(if (popTarget[it]) 1f else 0f, spring(0.62f, Spring.StiffnessMediumLow), label = "pop$it")
    }
    var liftOut by remember { mutableStateOf(false) }
    var lidOut by remember { mutableStateOf(false) }
    val liftA = animateFloatAsState(if (liftOut) 1f else 0f, spring(0.8f, Spring.StiffnessLow), label = "lift")
    val lidA = animateFloatAsState(if (lidOut) 1f else 0f, spring(0.75f, Spring.StiffnessLow), label = "lid")
    val driveTarget = remember { mutableStateListOf(*Array(DRV_N) { false }) }
    val driveA = List(DRV_N) {
        animateFloatAsState(if (driveTarget[it]) 1f else 0f, spring(0.55f, Spring.StiffnessMedium), label = "drv$it")
    }
    LaunchedEffect(lidOut) { if (!lidOut) for (k in 0 until DRV_N) driveTarget[k] = false }
    var macOut by remember { mutableStateOf(false) }
    val macA = animateFloatAsState(if (macOut) 1f else 0f, spring(0.62f, Spring.StiffnessMediumLow), label = "mac")
    val macOutNow by rememberUpdatedState(macOut)
    val fanAng = remember { mutableStateListOf(0f, 20f, 40f, 0f) }
    val fanHeld = remember { BooleanArray(FAN_N) }
    val fanKick = remember { FloatArray(FAN_N) }
    var bootT by remember { mutableFloatStateOf(0f) }
    val bump = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        bump.snapTo(1f)
        bump.animateTo(0f, tween(650))
    }

    var t by remember { mutableFloatStateOf(0f) }
    var flow by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                if (last != 0L) {
                    val dt = ((n - last) / 1e9f).coerceAtMost(0.05f)
                    t += dt
                    for (k in 0 until FAN_N) if (!fanHeld[k]) {
                        fanAng[k] = (fanAng[k] + dt * (600f * spinSpeed.value * (1f - liftA.value) + fanKick[k])) % 360f
                        fanKick[k] *= exp(-2.5f * dt)
                    }
                    bootT = if (runningNow || macOutNow) bootT + dt else 0f
                    flow += dt * (0.22f + 0.55f * glowOn.value)
                }
                last = n
            }
        }
    }

    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { oBase ->
                val iso = isoFor(size.width.toFloat(), size.height.toFloat(), 1.5f * liftA.value + 1.9f * lidA.value)
                val o = Offset(oBase.x, oBase.y + LIFT_H * liftA.value.coerceIn(0f, 1f) * iso.s)
                val md = macA.value.coerceIn(0f, 1.15f) * MAC_D
                val (my, mz) = iso.unprojX(o, RW + md)
                if (my in MY0..MY1 && mz in MZ0..MZ1) { macOut = !macOut; return@detectTapGestures }
                val (x, z) = iso.unprojY(o, RD)
                val dx = x - PWR_X; val dz = z - pwrZ()
                if (dx * dx + dz * dz <= (PWR_R + 0.12f) * (PWR_R + 0.12f)) { onPowerNow(); return@detectTapGestures }
                val order = (0 until BAY_COUNT).sortedBy { if (popA[it].value > 0.05f) 0 else 1 }
                for (i in order) {
                    val d = popA[i].value.coerceAtLeast(0f) * BAY_D
                    val (bx, bz) = iso.unprojY(o, RD + d)
                    val z1 = bayTop(i)
                    var hit = bx in (BX0 - 0.15f)..(BX1 + 0.15f) && bz in (z1 - BAY_H - 0.03f)..(z1 + 0.03f)
                    if (!hit && d > 0.05f) {
                        val (tx, ty) = iso.unprojZ(o, z1)
                        hit = tx in BX0..BX1 && ty in RD..(RD + d)
                    }
                    if (hit) {
                        val open = !popTarget[i]
                        for (j in 0 until BAY_COUNT) popTarget[j] = false
                        popTarget[i] = open
                        onSelectNow(i)
                        return@detectTapGestures
                    }
                }
                val la = lidA.value
                if (la > 0.5f) {
                    val dk = driveHit(iso, o, FloatArray(DRV_N) { driveA[it].value.coerceIn(0f, 1.1f) })
                    if (dk >= 0) { driveTarget[dk] = !driveTarget[dk]; return@detectTapGestures }
                }
                if (la > 0.05f) {
                    val li = LidIso(iso, la * LID_MAX, LID_Y)
                    val q = listOf(li.p(LID_Y, LID_Y, RH), li.p(RW - LID_Y, LID_Y, RH), li.p(RW - LID_Y, RD - LID_Y, RH), li.p(LID_Y, RD - LID_Y, RH))
                    val (cx2, cy2) = iso.unprojZ(o, RH - CAV)
                    if (inQuad(o, q) || (cx2 in LID_Y..(RW - LID_Y) && cy2 in LID_Y..(RD - LID_Y))) { lidOut = !lidOut; return@detectTapGestures }
                } else {
                    val (tx, ty) = iso.unprojZ(o, RH)
                    if (tx in 0f..RW && ty in 0f..RD) {
                        val onFan = listOf(1.15f, RW / 2f, RW - 1.15f).any { fx -> (tx - fx) * (tx - fx) + (ty - RD / 2f) * (ty - RD / 2f) < 0.74f * 0.74f }
                        if (!onFan) { lidOut = !lidOut; return@detectTapGestures }
                    }
                }
                val (sx, sy) = iso.unprojZ(oBase, RZ0)
                val topHit = sx in -0.8f..(RW + 0.8f) && sy in -0.8f..(RD + 0.8f) && (liftA.value > 0.5f || sy >= RD)
                val (fx2, fz2) = iso.unprojY(oBase, RD + 0.8f)
                val frontHit = fx2 in -0.8f..(RW + 0.8f) && fz2 in 0f..RZ0
                if (topHit || frontHit) liftOut = !liftOut
            }
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val iso = isoFor(size.width.toFloat(), size.height.toFloat(), 1.5f * liftA.value + 1.9f * lidA.value)
                val shiftY = LIFT_H * liftA.value.coerceIn(0f, 1f) * iso.s
                fun sh(o: Offset) = Offset(o.x, o.y + shiftY)
                val pop0 = popA[0].value.coerceAtLeast(0f)
                var hit = -1
                for (k in FAN_N - 1 downTo 0) {
                    if (k < 3 && lidA.value > 0.05f) continue
                    val g = fanGeom(k, pop0) ?: continue
                    val (px, py) = iso.unprojZ(sh(down.position), g[2])
                    val ddx = px - g[0]; val ddy = py - g[1]
                    if (ddx * ddx + ddy * ddy <= g[3] * g[3]) { hit = k; break }
                }
                if (hit < 0) return@awaitEachGesture
                val g = fanGeom(hit, pop0)!!
                down.consume()
                fanHeld[hit] = true
                fanKick[hit] = 0f
                fun angOf(o: Offset): Float {
                    val (px, py) = iso.unprojZ(sh(o), g[2])
                    return Math.toDegrees(atan2(py - g[1], px - g[0]).toDouble()).toFloat()
                }
                var prev = angOf(down.position)
                var prevT = down.uptimeMillis
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) {
                        if (ch.uptimeMillis - prevT > 100) fanKick[hit] = 0f
                        break
                    }
                    val a = angOf(ch.position)
                    var dl = a - prev
                    while (dl > 180f) dl -= 360f
                    while (dl < -180f) dl += 360f
                    fanAng[hit] = fanAng[hit] + dl
                    val dtS = ((ch.uptimeMillis - prevT) / 1000f).coerceAtLeast(0.008f)
                    fanKick[hit] = (dl / dtS).coerceIn(-1500f, 1500f)
                    prev = a; prevT = ch.uptimeMillis
                    ch.consume()
                }
                fanHeld[hit] = false
            }
        }
    ) {
        val iso = isoFor(size.width, size.height, 1.5f * liftA.value + 1.9f * lidA.value)
        val s = iso.s
        val lw = 1.dp.toPx()
        val dead = 1f - liftA.value.coerceIn(0f, 1f)
        val glow = glowOn.value * dead
        val live = running && dead > 0.5f
        val breath = 0.75f + 0.25f * sin(t * 2.2f)
        // 脉冲光条：2s 周期正弦呼吸，alpha 0.35~1.0，phase 错开形成扫动
        fun pulse(phase: Float = 0f) = 0.675f + 0.325f * sin(t * 3.1416f + phase)
        val fan = spinSpeed.value * dead
        val liftPx = LIFT_H * liftA.value.coerceIn(0f, 1f) * s

        fun face(path: Path, fill: Color) {
            drawPath(path, fill)
            drawPath(path, Pal.line, style = Stroke(lw, join = StrokeJoin.Round))
        }

        // floor light
        if (glow > 0.01f) {
            val c = iso.p(RW / 2f, RD / 2f, 0f)
            withTransform({ scale(1f, 0.55f, pivot = c) }) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Pal.orange.copy(alpha = 0.26f * glow * breath), Color.Transparent),
                        center = c, radius = 6.4f * s
                    ), radius = 6.4f * s, center = c
                )
            }
        }

        // base slab
        val bx0 = -0.8f; val bx1 = RW + 0.8f; val by0 = -0.8f; val by1 = RD + 0.8f; val bz = RZ0
        face(quad(iso.p(bx0, by1, 0f), iso.p(bx1, by1, 0f), iso.p(bx1, by1, bz), iso.p(bx0, by1, bz)), Pal.front)
        face(quad(iso.p(bx1, by0, 0f), iso.p(bx1, by1, 0f), iso.p(bx1, by1, bz), iso.p(bx1, by0, bz)), Pal.side)
        face(quad(iso.p(bx0, by0, bz), iso.p(bx1, by0, bz), iso.p(bx1, by1, bz), iso.p(bx0, by1, bz)), Pal.top)
        drawLine(
            Pal.lineSoft, iso.p(0.4f, RD + 0.45f, bz), iso.p(RW - 0.4f, RD + 0.45f, bz),
            strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round
        )
        drawLine(
            Pal.orange.copy(alpha = 0.7f * glow * pulse()), iso.p(0.4f, RD + 0.45f, bz), iso.p(RW - 0.4f, RD + 0.45f, bz),
            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round
        )
        for ((sx, sy) in listOf(bx0 + 0.3f to by0 + 0.3f, bx1 - 0.3f to by0 + 0.3f, bx1 - 0.3f to by1 - 0.3f, bx0 + 0.3f to by1 - 0.3f)) {
            val c = iso.floorCircle(sx, sy, bz, 0.1f, 12)
            drawPath(c, Pal.lineSoft)
            drawPath(c, Pal.line, style = Stroke(lw))
        }

        // power cable behind the rack, laid on the floor and leaving the screen
        run {
            val n = 90
            val pts = List(n + 1) { k ->
                val u = k / n.toFloat()
                val y = -9f * u
                val x = 3.2f + 1.1f * sin(u * 9f + 0.5f) + 0.6f * sin(u * 17f)
                val dz = ((-y - 0.8f) / 0.4f).coerceIn(0f, 1f)
                iso.p(x, y, RZ0 * (1f - dz))
            }
            val cable = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (k in 1..n) lineTo(pts[k].x, pts[k].y)
            }
            val shadow = Path().apply { addPath(cable, Offset(0f, 4.5.dp.toPx())) }
            drawPath(shadow, Color.Black.copy(alpha = 0.10f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(cable, Pal.line, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(cable, Color(0xFFE4E4E9), style = Stroke(7.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(cable, Pal.orange.copy(alpha = 0.25f + 0.75f * glow), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // 数据脉冲：4 个短光段（约路径 5%）沿电缆快速循环流动，~1 秒一圈
            if (glow > 0.02f) {
                val pm2 = PathMeasure()
                pm2.setPath(cable, false)
                val len = pm2.length
                val segFrac = 0.05f
                for (k in 0 until 4) {
                    val u = (t / 1.0f + k / 4f) % 1f
                    val segPath = Path()
                    var started = false
                    val steps = 8
                    for (q in 0..steps) {
                        val uu = u + segFrac * q / steps
                        if (uu > 1f) break // 到尾即消失，从头部重新进入，避免回绕跳线
                        val pos = pm2.getPosition(uu * len)
                        if (!started) { segPath.moveTo(pos.x, pos.y); started = true } else segPath.lineTo(pos.x, pos.y)
                    }
                    if (!started) continue
                    drawPath(segPath, Pal.orange.copy(alpha = 0.22f * glow), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(segPath, Pal.orange.copy(alpha = 0.85f * glow), style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(segPath, Color.White.copy(alpha = 0.7f * glow), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
                    val head = pm2.getPosition(u * len)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Pal.orange.copy(alpha = 0.35f * glow), Color.Transparent),
                            center = head, radius = 9.dp.toPx()
                        ), radius = 9.dp.toPx(), center = head
                    )
                }
            }
        }

        if (liftA.value > 0.02f) drawPcb(iso, LIFT_H * liftA.value.coerceIn(0f, 1f), t, flow, running, lw)

        withTransform({ translate(0f, -liftPx) }) {
        // rack body
        val frontPath = iso.frontQuad(0f, RZ0, RW, RH)
        face(frontPath, Pal.front)
        face(quad(iso.p(RW, 0f, RZ0), iso.p(RW, RD, RZ0), iso.p(RW, RD, RH), iso.p(RW, 0f, RH)), Pal.side)
        face(quad(iso.p(0f, 0f, RH), iso.p(RW, 0f, RH), iso.p(RW, RD, RH), iso.p(0f, RD, RH)), Pal.top)

        // rails
        var z = RZ0 + 0.15f
        while (z < RH - 0.1f) {
            drawCircle(Pal.line, 0.9.dp.toPx(), iso.f(0.2f, z))
            drawCircle(Pal.line, 0.9.dp.toPx(), iso.f(RW - 0.2f, z))
            z += 0.14f
        }

        // side pixel displays
        run {
            val items = listOf(
                Triple("TOK/S", if (tokPerSec >= 0) "%.1f".format(tokPerSec) else "--", 2.2f),
                Triple("TTFT MS", if (ttftMs >= 0) "$ttftMs" else "--", 3.55f),
                Triple("TOTAL TOK", fmtTokens(totalTokens), 5.1f)
            )
            val py0 = 0.4f; val py1 = RD - 0.4f
            for ((label, value, zT) in items) {
                val zB = zT - 1.2f
                val panel = quad(iso.p(RW, py0, zT), iso.p(RW, py1, zT), iso.p(RW, py1, zB), iso.p(RW, py0, zB))
                drawPath(panel, Color(0xFF2A2A2F))
                drawPath(panel, Pal.line, style = Stroke(lw))
                drawPath(iso.pixelPath(label, py1 - 0.1f, zT - 0.14f, 0.03f), Pal.orange.copy(alpha = 0.55f))
                val cell = min(0.06f, 1.6f / glyphCols(value))
                val litV = if (live) pulse() else 0.55f
                val digits = iso.pixelPath(value, py1 - 0.1f, zT - 0.5f, cell)
                drawPath(digits, Pal.orange.copy(alpha = litV))
                if (live) drawPath(digits, Pal.orange.copy(alpha = 0.25f * breath), style = Stroke(2.5.dp.toPx()))
                val led = iso.p(RW, py0 + 0.14f, zT - 0.2f)
                drawCircle(if (live && (busyNow || sin(t * 3f) > 0f)) Pal.orange else Color(0xFF55555B), 2.dp.toPx(), led)
            }

            var y = py0 + 0.1f
            while (y < py1 - 0.05f) {
                drawLine(Pal.lineSoft, iso.p(RW, y, 0.45f), iso.p(RW, y, 0.8f), strokeWidth = lw)
                y += 0.1f
            }
        }

        drawMac(iso, macA.value, (running || macOutNow) && dead > 0.5f, bootT, t, lw)

        // top deck: hinged lid carrying the fans, cavity + antennas underneath
        val la = lidA.value.coerceIn(0f, 1f)
        val fy = RD / 2f
        val lAng = la * LID_MAX
        val li = LidIso(iso, lAng, LID_Y)
        val driveE = FloatArray(DRV_N) { driveA[it].value.coerceIn(0f, 1.1f) }
        if (la > 0.01f) drawCavity(measurer, iso, driveE, running, t, lw)
        val under = lAng > 0.78f
        val lidQ = quad(li.p(0.3f, LID_Y, RH), li.p(RW - 0.3f, LID_Y, RH), li.p(RW - 0.3f, RD - LID_Y, RH), li.p(0.3f, RD - LID_Y, RH))
        drawPath(lidQ, if (under) Color(0xFFD9D9DF) else Color(0xFFEBEBEF))
        drawPath(lidQ, if (under) Pal.line else Pal.lineSoft, style = Stroke(if (under) 1.6.dp.toPx() else lw, join = StrokeJoin.Round))
        if (under) for (ry in listOf(0.55f, 1.4f, 2.25f)) drawLine(Pal.line, li.p(0.5f, ry, RH), li.p(RW - 0.5f, ry, RH), strokeWidth = lw)
        for ((k, fx) in listOf(1.15f, RW / 2f, RW - 1.15f).withIndex()) drawFan(li, fx, fy, RH, 1f, fanAng[k], fan, glow, lw)
        if (la > 0.01f) drawDrivesAbove(measurer, iso, driveE, running, t, lw)

        // bay light spill on front face
        clipPath(frontPath) {
            for (i in 0 until BAY_COUNT) {
                val a = litA[i].value * dead
                if (a > 0.01f) {
                    val c = iso.f((BX0 + BX1) / 2f, bayTop(i) - BAY_H / 2f)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Pal.orange.copy(alpha = 0.20f * a * (0.85f + 0.15f * breath)), Color.Transparent),
                            center = c, radius = 2.1f * s
                        ), radius = 2.1f * s, center = c
                    )
                }
            }
            val sc = iso.f((BX0 + BX1) / 2f, bayTop(selected) - BAY_H / 2f)
            drawCircle(
                Brush.radialGradient(
                    listOf(Pal.orange.copy(alpha = 0.18f + 0.10f * breath + 0.25f * bump.value), Color.Transparent),
                    center = sc, radius = 2.6f * s
                ), radius = 2.6f * s, center = sc
            )
        }

        // header strip + big power button
        val hz1 = RH - 0.15f; val hz0 = RH - 1.6f
        val hp = iso.frontQuad(BX0, hz0, BX1, hz1)
        drawPath(hp, Pal.orange.copy(alpha = 0.10f + 0.08f * glow))
        drawPath(hp, Pal.orange.copy(alpha = 0.8f), style = Stroke(1.3.dp.toPx()))
        val up = lit.count { it }
        planeText(measurer, iso, "UP $up/$BAY_COUNT", 2.05f, RH - 0.5f, 0.3f * s, Pal.orangeDeep, bold = true)
        planeText(measurer, iso, "LOAD", 2.05f, RH - 1.1f, 0.2f * s, Pal.mute)
        val segs = 10
        val fill = (up / BAY_COUNT.toFloat() * segs).roundToInt()
        for (j in 0 until segs) {
            val x0 = 2.75f + j * 0.27f
            if (j < fill) {
                val pw = 0.19f * (0.78f + 0.22f * pulse(j * 0.55f))
                val seg = iso.frontQuad(x0, RH - 1.25f, x0 + pw, RH - 0.95f)
                drawPath(seg, Pal.orange.copy(alpha = (0.55f + 0.4f * glow) * pulse(j * 0.55f)))
            } else {
                val seg = iso.frontQuad(x0, RH - 1.25f, x0 + 0.19f, RH - 0.95f)
                drawPath(seg, Pal.orange.copy(alpha = 0.18f))
            }
        }
        run {
            val pz = pwrZ()
            val pc = iso.f(PWR_X, pz)
            if (glow > 0.01f) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Pal.orange.copy(alpha = 0.45f * glow * breath), Color.Transparent),
                        center = pc, radius = 1.4f * s
                    ), radius = 1.4f * s, center = pc
                )
            }
            drawPath(iso.frontCircle(PWR_X, pz, PWR_R, 28), Color(0xFF3A3A40))
            val capR = PWR_R * 0.82f
            val cap = iso.frontCircle(PWR_X, pz, capR, 28)
            drawPath(cap, lerp(Color(0xFFEDEDF0), Pal.orange, glow))
            drawPath(cap, lerp(Pal.line, Pal.orangeDeep, glow), style = Stroke(1.5.dp.toPx()))
            val ic = lerp(Pal.mute, Color.White, glow)
            val arc = Path()
            for (k in 0..24) {
                val ang = Math.toRadians(125.0 + k * (290.0 / 24)).toFloat()
                val o = iso.f(PWR_X + 0.22f * cos(ang), pz + 0.22f * sin(ang))
                if (k == 0) arc.moveTo(o.x, o.y) else arc.lineTo(o.x, o.y)
            }
            drawPath(arc, ic, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(ic, iso.f(PWR_X, pz + 0.03f), iso.f(PWR_X, pz + 0.3f), strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round)
        }

        // bays
        val popD = FloatArray(BAY_COUNT) { popA[it].value.coerceAtLeast(0f) * BAY_D }
        fun drawBay(i: Int) {
            val d = popD[i]
            val z1 = bayTop(i); val z0 = z1 - BAY_H
            val zm = (z0 + z1) / 2f
            val a = litA[i].value * dead
            val sel = i == selected
            if (d > 0.004f) {
                face(quad(iso.p(BX0, RD, z1), iso.p(BX1, RD, z1), iso.p(BX1, RD + d, z1), iso.p(BX0, RD + d, z1)), Color(0xFFF1F1F4))
                face(quad(iso.p(BX1, RD, z0), iso.p(BX1, RD + d, z0), iso.p(BX1, RD + d, z1), iso.p(BX1, RD, z1)), Pal.side)
                bayTopParts(measurer, iso, i, RD + d, z1, ((d / BAY_D - 0.55f) / 0.45f).coerceIn(0f, 1f), t, fanAng[3], fan, glow, lw)
            }
            withTransform({ translate(-d * K * s, d * 0.5f * s) }) {
                val rect = iso.frontQuad(BX0, z0, BX1, z1)
                drawPath(rect, if (sel) Pal.orange.copy(alpha = 0.08f).compositeOver(Color.White) else Color.White)
                drawPath(
                    rect,
                    if (sel) Pal.orangeDeep else Pal.line,
                    style = Stroke(if (sel) 1.8.dp.toPx() else lw, join = StrokeJoin.Round)
                )
                if (sel && bump.value > 0.01f) {
                    val e = 0.18f * (1f - bump.value)
                    drawPath(
                        iso.frontQuad(BX0 - e, z0 - e, BX1 + e, z1 + e),
                        Pal.orange.copy(alpha = 0.6f * bump.value), style = Stroke(1.5.dp.toPx())
                    )
                }

                val ledC = iso.f(0.78f, zm)
                if (a > 0.01f) drawCircle(Pal.orange.copy(alpha = 0.3f * a), 6.dp.toPx(), ledC)
                drawCircle(lerp(Color(0xFFD0D0D6), Pal.orange, a), 3.dp.toPx(), ledC)
                drawCircle(lerp(Pal.line, Pal.orangeDeep, a), 3.dp.toPx(), ledC, style = Stroke(lw))

                planeText(measurer, iso, "0${i + 1} ${BAY_CODES[i]}", 1.05f, zm, 0.26f * s, if (sel) Pal.orangeDeep else Pal.ink, bold = true)
                planeText(measurer, iso, readouts[i], 3.05f, zm, 0.23f * s, if (lit[i]) Pal.orangeDeep else Pal.mute)

                if (i == 3) {
                    for (j in 0 until 8) {
                        val h = if (a > 0.01f) (0.18f + 0.38f * abs(sin(t * 1.6f + j * 0.9f))) * a + 0.06f else 0.06f
                        val x0 = 4.25f + j * 0.15f
                        drawPath(
                            iso.frontQuad(x0, z0 + 0.08f, x0 + 0.1f, z0 + 0.08f + h),
                            if (a > 0.01f) Pal.orange.copy(alpha = 0.75f * pulse(j * 0.45f)) else Pal.lineSoft
                        )
                    }
                } else {
                    val rate = if (i == 4 && busyNow) 9f else 3f
                    for (j in 0 until 4) {
                        val on = a > 0.01f
                        val blink = if (on) 0.5f + 0.5f * sin(t * rate + i * 1.3f + j * 1.1f) else 0f
                        drawCircle(
                            if (on) Pal.orange.copy(alpha = 0.35f + 0.65f * blink) else Pal.lineSoft,
                            1.9.dp.toPx(), iso.f(4.4f + j * 0.28f, zm)
                        )
                    }
                }
            }
        }
        for (i in 0 until BAY_COUNT) if (popD[i] <= 0.004f) drawBay(i)
        for (i in BAY_COUNT - 1 downTo 0) if (popD[i] > 0.004f) drawBay(i)

        // cables: lower jack of a lit bay -> upper jack of the next lit bay
        val sway = sin(t * 1.3f) * 0.05f * (0.4f + glow)
        val pm = PathMeasure()
        val outOn = FloatArray(BAY_COUNT)
        val inOn = FloatArray(BAY_COUNT)
        for (i in 0 until BAY_COUNT) {
            val nj = if (lit[i]) (i + 1 until BAY_COUNT).firstOrNull { lit[it] } else null
            if (nj != null) {
                val v = min(litA[i].value * dead, litA[nj].value * dead)
                outOn[i] = v; inOn[nj] = v
            }
        }
        for (i in 0 until BAY_COUNT) {
            val zm = bayTop(i) - BAY_H / 2f
            val jUp = iso.p(JACK_X, RD + popD[i], zm + 0.14f)
            val jDn = iso.p(JACK_X, RD + popD[i], zm - 0.14f)
            val j = if (lit[i]) (i + 1 until BAY_COUNT).firstOrNull { lit[it] } else null
            if (j != null) {
                val zn = bayTop(j) - BAY_H / 2f
                val a1 = iso.p(JACK_X, RD + popD[j], zn + 0.14f)
                val yMax = RD + max(popD[i], popD[j])
                val bulge = 0.4f + 0.1f * (j - i - 1) + sway
                val c1 = iso.p(JACK_X + 0.1f, yMax + bulge, zm - 0.16f)
                val c2 = iso.p(JACK_X + 0.1f, yMax + bulge, zn + 0.16f)
                val cable = Path().apply {
                    moveTo(jDn.x, jDn.y); cubicTo(c1.x, c1.y, c2.x, c2.y, a1.x, a1.y)
                }
                val on = min(litA[i].value * dead, litA[j].value * dead)
                drawPath(cable, Pal.line.copy(alpha = on), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawPath(cable, Pal.orange.copy(alpha = on), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                pm.setPath(cable, false)
                val len = pm.length
                for (k in 0 until 3) {
                    val u = (flow * (1f + i * 0.07f) + k / 3f) % 1f
                    val pos = pm.getPosition(u * len)
                    drawCircle(Color.White.copy(alpha = 0.9f * on), 2.2.dp.toPx(), pos)
                    drawCircle(Pal.orangeDeep.copy(alpha = 0.9f * on), 2.2.dp.toPx(), pos, style = Stroke(lw))
                }
            }
            for ((jj, c) in listOf(jUp, jDn).withIndex()) {
                if (i == 0 && jj == 0) continue
                if (i == BAY_COUNT - 1 && jj == 1) continue
                drawCircle(Color.White, 3.6.dp.toPx(), c)
                val ja = if (jj == 0) inOn[i] else outOn[i]
                drawCircle(lerp(Pal.line, Pal.orangeDeep, ja), 3.6.dp.toPx(), c, style = Stroke(1.4.dp.toPx()))
                drawCircle(lerp(Pal.mute, Pal.orange, ja), 1.3.dp.toPx(), c)
            }
        }
        }
    }
}
