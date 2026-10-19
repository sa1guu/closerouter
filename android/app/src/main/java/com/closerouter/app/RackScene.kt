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
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
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
    val bg = Color(0xFFF8F8F8)
    val line = Color(0xFFB4B4BB)
    val lineSoft = Color(0xFFD2D4DA)
    val front = Color(0xFFFFFEFD)
    val side = Color(0xFFE9EAEE)
    val top = Color(0xFFF7F8FA)
    val orange = Color(0xFFF28A2E)
    val orangeDeep = Color(0xFFE8741C)
    val ink = Color(0xFF3A3A40)
    val mute = Color(0xFF9A9AA3)
}

const val BAY_COUNT = 7
val BAY_CODES = listOf("PWR", "PROV", "PORT", "TOKEN", "SPEED", "EXPORT", "KEEP")

private const val K = 0.8660254f
private const val Z_SCALE = 0.82f
private const val RW = 6f
private const val RD = RackLayout.CABINET_DEPTH
private const val RH = 7.2f
private const val RZ0 = 0.25f
private const val BX0 = 0.45f
private const val BX1 = RW - 0.45f
private const val BAY_H = 0.66f

private const val BAY_D = 1.4f
private const val FAN_N = 4

private fun bayTop(i: Int) = RH - 1.75f - i * 0.72f
private const val PWR_X = 1.2f
private const val PWR_R = RackLayout.POWER_RADIUS
private const val JACK_X = 5.8f
private fun pwrZ() = RH - RackLayout.POWER_TOP_OFFSET

private open class Iso(val s: Float, val cx: Float, val cy: Float) {
    open fun p(x: Float, y: Float, z: Float) = Offset(cx + (x - y) * K * s, cy + (x + y) * 0.5f * s - z * Z_SCALE * s)
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
private const val CAV = RackHardwareLayout.CAVITY_DEPTH
private const val LID_Y = 0.3f

private fun isoFor(w: Float, h: Float, extra: Float = 0f): Iso {
    val xMin = (-0.8f - (RD + 0.8f)) * K
    val xMax = (RW + 0.8f + 0.8f) * K
    val zTop = (RH + extra) * Z_SCALE
    val yTop = -zTop
    val yBottom = (RW + RD + 1.6f) * 0.5f - RackHardwareLayout.BASE_BOTTOM * Z_SCALE
    val s = min(w * 0.9f / (xMax - xMin), h * 0.9f / (yBottom - yTop))
    val cx = w / 2f - (xMin + xMax) / 2f * s
    val cy = h / 2f - (yTop + yBottom) / 2f * s
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
    return x to ((x + y) * 0.5f * s + cy - o.y) / (s * Z_SCALE)
}

private fun Iso.unprojX(o: Offset, x: Float): Pair<Float, Float> {
    val y = x - (o.x - cx) / (K * s)
    return y to ((x + y) * 0.5f * s + cy - o.y) / (s * Z_SCALE)
}

private fun Iso.unprojZ(o: Offset, z: Float): Pair<Float, Float> {
    val d = (o.x - cx) / (K * s)
    val sum = 2f * ((o.y - cy) / s + z * Z_SCALE)
    return (sum + d) / 2f to (sum - d) / 2f
}

private fun quad(a: Offset, b: Offset, c: Offset, d: Offset) = Path().apply {
    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
}

private fun DrawScope.drawCabinetShell(iso: Iso, lw: Float) {
    val r = RackLayout.SHELL_RADIUS
    val x = RW - r
    val y = RD - r
    val z = RH - r
    val steps = 12
    val front = Color(0xFFF9F9F9)
    val side = Color(0xFFE5E7EB)
    val top = Pal.top
    fun patch(points: List<Offset>, color: Color) {
        val path = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        drawPath(path, color)
        drawPath(path, color, style = Stroke(0.5f))
    }
    fun outline(points: List<Offset>) {
        val path = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        }
        drawPath(path, Pal.line, style = Stroke(lw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    fun angle(step: Int) = step * (PI.toFloat() / 2f) / steps
    fun vertical(a: Float, height: Float) = iso.p(x + r * sin(a), y + r * cos(a), height)
    fun frontTop(a: Float, width: Float) = iso.p(width, y + r * cos(a), z + r * sin(a))
    fun sideTop(a: Float, depth: Float) = iso.p(x + r * cos(a), depth, z + r * sin(a))
    fun corner(a: Float, b: Float) = iso.p(
        x + r * sin(a) * cos(b), y + r * cos(a) * cos(b), z + r * sin(b)
    )
    fun metal(base: Color, a: Float) = lerp(base, Color.White, 0.30f * sin(a * 2f).coerceAtLeast(0f))

    drawPath(iso.frontQuad(0f, RZ0, x, z),
        Brush.linearGradient(listOf(Color.White, Color(0xFFF5F4F3)), iso.f(0f, RH), iso.f(RW, RZ0)))
    patch(listOf(iso.p(RW, 0f, RZ0), iso.p(RW, y, RZ0), iso.p(RW, y, z), iso.p(RW, 0f, z)), side)
    patch(listOf(iso.p(0f, 0f, RH), iso.p(x, 0f, RH), iso.p(x, y, RH), iso.p(0f, y, RH)), top)
    for (k in 0 until steps) {
        val a = angle(k)
        val b = angle(k + 1)
        val mid = (a + b) * 0.5f
        patch(listOf(vertical(a, RZ0), vertical(b, RZ0), vertical(b, z), vertical(a, z)),
            metal(lerp(front, side, sin(mid)), mid))
        patch(listOf(frontTop(a, 0f), frontTop(a, x), frontTop(b, x), frontTop(b, 0f)),
            metal(lerp(front, top, sin(mid)), mid))
        patch(listOf(sideTop(a, 0f), sideTop(a, y), sideTop(b, y), sideTop(b, 0f)),
            metal(lerp(side, top, sin(mid)), mid))
        for (j in 0 until steps) {
            val c = angle(j)
            val d = angle(j + 1)
            val elevation = (c + d) * 0.5f
            patch(listOf(corner(a, c), corner(b, c), corner(b, d), corner(a, d)),
                metal(lerp(lerp(front, side, sin(mid)), top, sin(elevation)), mid))
        }
    }
    outline(listOf(iso.p(0f, RD, RZ0), iso.p(0f, RD, z)) +
        (0..steps).map { frontTop(angle(it), 0f) } + listOf(iso.p(0f, 0f, RH), iso.p(x, 0f, RH)))
    outline((steps downTo 0).map { sideTop(angle(it), 0f) } + listOf(iso.p(RW, 0f, RZ0)))
    outline(listOf(iso.p(0f, RD, RZ0)) + (0..steps).map { vertical(angle(it), RZ0) } +
        listOf(iso.p(RW, 0f, RZ0)))
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
    mat.values[Matrix.ScaleY] = Z_SCALE
    mat.values[Matrix.TranslateX] = origin.x
    mat.values[Matrix.TranslateY] = origin.y
    withTransform({ transform(mat) }) {
        drawText(
            m.measure(text, TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fs.toSp(),
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
            ), softWrap = false, maxLines = 1),
            color = color, topLeft = Offset.Zero
        )
    }
}

private fun DrawScope.sideText(
    m: TextMeasurer, iso: Iso, text: String, yRight: Float, zMid: Float,
    fs: Float, color: Color, bold: Boolean = false
) {
    val origin = iso.p(RW, yRight, zMid + (fs * 1.2f / 2f) / iso.s)
    val mat = Matrix()
    mat.values[Matrix.ScaleX] = K
    mat.values[Matrix.SkewY] = -0.5f
    mat.values[Matrix.ScaleY] = Z_SCALE
    mat.values[Matrix.TranslateX] = origin.x
    mat.values[Matrix.TranslateY] = origin.y
    withTransform({ transform(mat) }) {
        drawText(
            m.measure(text, TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fs.toSp(),
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
            ), softWrap = false, maxLines = 1),
            color = color, topLeft = Offset.Zero
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
    0 -> floatArrayOf(1.15f, RackLayout.TOP_FAN_Y, RH, 0.81f)
    1 -> floatArrayOf(RW / 2f, RackLayout.TOP_FAN_Y, RH, 0.81f)
    2 -> floatArrayOf(RW - 1.15f, RackLayout.TOP_FAN_Y, RH, 0.81f)
    else -> if (pop0 > 0.9f) floatArrayOf(4.6f, RD + pop0 * BAY_D - 0.7f, bayTop(0) + 0.05f, 0.5f) else null
}

private fun DrawScope.drawBayNode(iso: Iso, x: Float, z: Float, active: Float, lw: Float, y: Float = RD) {
    fun circle(radius: Float) = Path().apply {
        for (k in 0..24) {
            val a = (2.0 * PI * k / 24).toFloat()
            val p = iso.p(x + radius * cos(a), y, z + radius * sin(a))
            if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(circle(0.145f), lerp(Color(0xFFE1E2E5), Color(0xFFFFD6AD), active))
    drawPath(circle(0.105f), lerp(Color(0xFFC8CBD0), Pal.orange, active))
    drawPath(circle(0.105f), lerp(Pal.line, Pal.orangeDeep, active), style = Stroke(lw * 0.7f))
    if (active > 0.01f) drawPath(circle(0.048f), Color(0xFFFFF5EA))
}

private fun DrawScope.drawBayIcon(iso: Iso, path: Path, centerX: Float, centerZ: Float,
    size: Float, color: Color, outlined: Boolean = false) {
    val origin = iso.f(centerX - size / 2f, centerZ + size / 2f)
    val unit = size * iso.s / 24f
    val matrix = Matrix().apply {
        values[Matrix.ScaleX] = K * unit
        values[Matrix.SkewY] = 0.5f * unit
        values[Matrix.ScaleY] = Z_SCALE * unit
        values[Matrix.TranslateX] = origin.x
        values[Matrix.TranslateY] = origin.y
    }
    withTransform({ transform(matrix) }) {
        if (outlined) drawPath(path, color, style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        else drawPath(path, color)
    }
}

private fun DrawScope.drawTopIo(m: TextMeasurer, iso: Iso, glow: Float, live: Boolean, lw: Float) {
    val z = RH + 0.03f
    val y = RackLayout.TOP_ROW_Y
    fun topQuad(cx: Float, halfW: Float, halfD: Float, round: Float = 0.08f) = roundedQuadPath(listOf(
        iso.p(cx - halfW, y - halfD, z), iso.p(cx + halfW, y - halfD, z),
        iso.p(cx + halfW, y + halfD, z), iso.p(cx - halfW, y + halfD, z)
    ), round)
    drawLine(lerp(Pal.lineSoft, Pal.orange, glow), iso.p(0.55f, y, z), iso.p(RackLayout.TOP_LIGHT_END_X, y, z),
        3.4.dp.toPx(), cap = StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.6f), iso.p(0.55f, y, z + 0.004f), iso.p(RackLayout.TOP_LIGHT_END_X, y, z + 0.004f),
        0.75.dp.toPx(), cap = StrokeCap.Round)
    topText(m, iso, "Router", RackLayout.TOP_LABEL_X, RackLayout.TOP_LABEL_Y, z,
        RackLayout.TOP_LABEL_SIZE * iso.s, if (live) Pal.orangeDeep else Pal.mute, true)

    val usb = RackLayout.TOP_USB_X
    drawPath(topQuad(usb, 0.245f, 0.12f, 0.03f), Color(0xFF252A32))
    val tongue = quad(iso.p(usb - 0.19f, y + 0.02f, z), iso.p(usb + 0.19f, y + 0.02f, z),
        iso.p(usb + 0.19f, y + 0.08f, z), iso.p(usb - 0.19f, y + 0.08f, z))
    drawPath(tongue, Color(0xFFBFC4CA))
    for (k in 0 until 4) {
        val x = usb - 0.13f + k * 0.087f
        drawLine(Color(0xFFC4A465), iso.p(x, y + 0.03f, z), iso.p(x, y + 0.072f, z), lw)
    }
    val typeC = RackLayout.TOP_TYPE_C_X
    val slot = Path().apply {
        val radius = 0.06f
        val halfLength = 0.225f
        for (end in 0..1) {
            val centerX = typeC + if (end == 0) halfLength - radius else radius - halfLength
            for (k in 0..16) {
                val a = -PI.toFloat() / 2f + end * PI.toFloat() + k * PI.toFloat() / 16f
                val p = iso.p(centerX + radius * cos(a), y + radius * sin(a), z)
                if (end == 0 && k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }
        close()
    }
    drawPath(slot, Color(0xFF252A32))
    drawLine(Color(0xFFAEB4BE), iso.p(typeC - 0.13f, y, z), iso.p(typeC + 0.13f, y, z), lw * 0.85f, cap = StrokeCap.Round)
}

private fun DrawScope.drawReceiver(m: TextMeasurer, iso: Iso, progress: Float, on: Boolean, lw: Float) {
    if (progress <= 0.001f) return
    val x = RackLayout.TOP_USB_X
    val y = RackLayout.TOP_ROW_Y
    val rise = RackPowerVisuals.receiverHeight(progress)
    val socketZ = RH + 0.03f
    val z = socketZ + rise - 0.145f
    val shadow = iso.floorCircle(x, y, RH + 0.027f, 0.23f + rise * 0.05f, 24)
    drawPath(shadow, Color(0xFF353A43).copy(alpha = (0.16f - rise * 0.035f).coerceAtLeast(0.03f)))
    // The seated connector is hidden inside the socket, not painted over the lid.
    box(iso, x - 0.20f, x + 0.20f, y - 0.09f, y + 0.09f, max(z, socketZ), z + 0.17f,
        Color(0xFFD7DCE2), Color(0xFFC0C7D0), Color(0xFF939DAA), lw)
    box(iso, x - 0.24f, x + 0.24f, y - 0.13f, y + 0.13f, z + 0.17f, z + 0.47f,
        Color(0xFF4A505A), Color(0xFF343A43), Color(0xFF252B34), lw)
    planeText(m, iso, "2.4G", x - 0.17f, z + 0.36f, 0.10f * iso.s, Color(0xFFE1E5E9), true, y + 0.133f)
    drawLine(if (on && rise < 0.01f) Pal.orange else Pal.mute,
        iso.p(x - 0.13f, y + 0.134f, z + 0.23f), iso.p(x + 0.13f, y + 0.134f, z + 0.23f),
        lw * 1.3f, cap = StrokeCap.Round)
}

private fun DrawScope.drawFanTray(iso: Iso, fy: Float, z: Float, lw: Float) {
    val x0 = 0.25f
    val x1 = RW - 0.25f
    val y0 = fy - 1.08f
    val y1 = fy + 1.08f
    val tray = roundedQuadPath(listOf(
        iso.p(x0, y0, z), iso.p(x1, y0, z), iso.p(x1, y1, z), iso.p(x0, y1, z)
    ), 0.055f)
    drawPath(tray, Color(0xFFEAEBEE))
    drawPath(tray, Color(0xFFC4C7CE), style = Stroke(lw))
    for (x in listOf(x0 + 0.19f, x1 - 0.19f)) {
        for (y in listOf(y0 + 0.17f, y1 - 0.17f)) {
            val screw = iso.floorCircle(x, y, z + 0.003f, 0.055f, 20)
            drawPath(screw, Color(0xFFF8F8FA))
            drawPath(screw, Pal.line, style = Stroke(lw * 0.8f))
            drawLine(Pal.mute, iso.p(x - 0.027f, y, z + 0.004f), iso.p(x + 0.027f, y, z + 0.004f), lw * 0.7f)
            drawLine(Pal.mute, iso.p(x, y - 0.027f, z + 0.004f), iso.p(x, y + 0.027f, z + 0.004f), lw * 0.7f)
        }
    }
}

private fun DrawScope.drawFan(
    iso: Iso, fx: Float, fy: Float, z: Float, sc: Float, angDeg: Float, fan: Float, glow: Float, lw: Float
) {
    val frame = iso.floorCircle(fx, fy, z, 0.81f * sc)
    drawPath(frame, Color(0xFFF0F1F3))
    drawPath(frame, Color.White, style = Stroke(3.2.dp.toPx()))
    drawPath(frame, Pal.line, style = Stroke(lw * 1.15f))
    for (j in 0 until 6) {
        val a = (2.0 * PI * j / 6).toFloat()
        drawCircle(Pal.line, 1.dp.toPx(), iso.p(fx + 0.75f * sc * cos(a), fy + 0.75f * sc * sin(a), z))
    }
    val well = iso.floorCircle(fx, fy, z, 0.68f * sc)
    drawPath(well, Brush.linearGradient(listOf(Color(0xFFB8BCC1), Color(0xFFEBECEE)), iso.p(fx, fy - sc, z), iso.p(fx, fy + sc, z)))
    drawPath(iso.floorCircle(fx, fy, z, 0.7f * sc), Color.White.copy(alpha = 0.75f), style = Stroke(lw))
    drawPath(well, Pal.lineSoft, style = Stroke(lw))
    if (fan > 0.02f) drawPath(iso.floorCircle(fx, fy, z, 0.63f * sc), Pal.orange.copy(alpha = 0.10f * fan * glow))
    for (j in 0 until 5) {
        val a = Math.toRadians(angDeg + j * 72.0).toFloat()
        fun pt(r: Float, da: Float) = iso.p(fx + r * sc * cos(a + da), fy + r * sc * sin(a + da), z)
        val blade = Path().apply {
            val p0 = pt(0.072f, -0.15f); moveTo(p0.x, p0.y)
            val p1 = pt(0.645f, -0.18f); val c1 = pt(0.425f, -0.28f); quadraticTo(c1.x, c1.y, p1.x, p1.y)
            val p2 = pt(0.645f, 0.66f); lineTo(p2.x, p2.y)
            val p3 = pt(0.085f, 0.5f); val c2 = pt(0.383f, 1.0f); quadraticTo(c2.x, c2.y, p3.x, p3.y)
            close()
        }
        drawPath(blade, Brush.linearGradient(listOf(lerp(Color(0xFF888C92), Color(0xFFFFB56B), fan), lerp(Color(0xFF62666C), Color(0xFFFF942F), fan)), iso.p(fx - sc, fy, z), iso.p(fx + sc, fy, z)))
        drawPath(blade, lerp(Pal.line, Pal.orangeDeep, fan), style = Stroke(lw, join = StrokeJoin.Round))
    }
    drawPath(iso.floorCircle(fx, fy, z, 0.425f * sc), Pal.line.copy(alpha = 0.65f), style = Stroke(lw * 0.7f))
    drawPath(iso.floorCircle(fx, fy, z, 0.65f * sc), Pal.line.copy(alpha = 0.7f), style = Stroke(lw * 0.65f))
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
    val powered = glow > 0.01f
    val or = lerp(Color(0xFF7D8289), Pal.orange, glow)
    val orF = lerp(g1, Color(0xFFF7C79A), glow)
    val orS = lerp(g2, Color(0xFFE9A25A), glow)
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
            val lvl = if (!powered) 0 else (4 + (3.5f * (1f + sin(t * 1.7f))).toInt()).coerceAtMost(8)
            for (j in 0 until 8) flat(1.75f + j * 0.25f, 1.75f + j * 0.25f + 0.17f, 0.22f, 0.4f, 0.045f, if (j < lvl) or else g2)
            plate(4.0f, 5.2f)
            if (clear(1.2f, 0.05f)) drawFan(iso, 4.6f, yb - 0.7f, z1 + 0.05f * rise, 0.56f, fanAng, fan, glow, lw)
        }
        1 -> {
            plate(0.7f, 5.3f)
            for (k in 0 until 5) {
                val on = powered && sin(t * 3f + k * 1.7f) > -0.3f
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
                dot(x0 + 0.17f, 0.32f, 0.045f, 2f, if (powered && sin(t * 4f + k * 1.3f) > 0f) or else g2)
                dot(x0 + 0.43f, 0.32f, 0.045f, 2f, if (powered && sin(t * 7f + k * 2.1f) > 0.4f) or else g2)
            }
            bx(4.2f, 5.1f, 0.55f, 1.1f, 0.04f, 0.12f, or, orF, orS)
            label("LAN", 4.5f, 0.83f, 0.12f, 0.16f, w, true)
            disc(4.65f, 0.32f, 0.04f, 0.1f, 0.12f, g1, w)
        }
        3 -> {
            plate(0.7f, 5.3f)
            for (k in 0 until 8) {
                val x0 = 0.95f + k * 0.38f
                bx(x0, x0 + 0.26f, 0.55f, 1.1f, 0.04f, 0.06f + (if (powered) 0.14f else 0f) * abs(sin(t * 1.5f + k * 0.8f)), or, orF, orS)
            }
            for (q in 0..15) seg(0.95f + q * 0.19f, 0.27f, 0.95f + q * 0.19f, if (q % 4 == 0) 0.42f else 0.35f, 0.045f, Pal.mute, 1f)
            bx(4.2f, 5.3f, 0.2f, 1.1f, 0.04f, 0.1f, dk, dk2, dk3)
            label("TOK", 4.45f, 0.84f, 0.1f, 0.22f, or, true)
            for (k in 0 until 3) dot(4.45f + k * 0.25f, 0.4f, 0.1f, 1.8f, if (powered && (t * 3f).toInt() % 3 == k) or else Pal.mute)
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
                val na = Math.toRadians(if (powered) 205.0 + 100.0 * sin(2.6 + 1.7 * sin(t * 1.1)) else 150.0).toFloat()
                drawLine(or, iso.p(cx, yb - cv, zd), iso.p(cx + 0.34f * cos(na), yb - cv + 0.34f * sin(na), zd), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Pal.ink, 2.dp.toPx(), iso.p(cx, yb - cv, zd))
            }
            plate(2.7f, 5.3f)
            val lvl = if (powered) 1 + ((t * 2f).toInt() % 4) else 0
            for (k in 0 until 4) {
                val x0 = 3.0f + k * 0.5f
                bx(x0, x0 + 0.36f, 0.55f, 1.1f, 0.04f, 0.1f + 0.033f * k, if (k < lvl) or else g2, w, g2)
            }
            for (k in 0 until 3) dot(3.15f + k * 0.35f, 0.32f, 0.045f, 1.8f, if (powered && (t * 3f).toInt() % 3 == k) or else g2)
        }
        5 -> {
            plate(0.7f, 2.9f)
            val su = if (powered) (t * 0.45f) % 1f else 0f
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
                    val nv = 0.8f + (if (powered && d < 0.3f) 0.2f * sin(d * 20f) * (1f - d / 0.3f) * (if (k % 2 == 0) 1f else -1f) else 0f)
                    seg(px, pv, nx, nv, 0.075f, or, 1.4f); px = nx; pv = nv
                }
            }
            for (k in 0 until 4) dot(0.95f + k * 0.3f, 0.3f, 0.045f, 2.2f, if (powered && (t * 2f).toInt() % 4 == k) or else g2)
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

private fun DrawScope.drawAiBootMark(iso: Iso, x: Float, reveal: Float) {
    val screen = AiModuleLayout.screen
    val width = (screen.y1 - screen.y0) * 0.48f
    val height = (screen.z1 - screen.z0) * 0.42f
    val left = (screen.y0 + screen.y1 + width) * 0.5f
    val top = (screen.z0 + screen.z1 + height) * 0.5f + 0.06f
    fun point(u: Float, v: Float) = iso.p(x, left - u * width, top - v * height)
    val mark = Path().apply {
        fillType = PathFillType.EvenOdd
        fun polygon(vararg points: Pair<Float, Float>) {
            points.forEachIndexed { index, (u, v) ->
                val p = point(u, v)
                if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        polygon(0.02f to 0.95f, 0.30f to 0.05f, 0.45f to 0.05f, 0.72f to 0.95f,
            0.55f to 0.95f, 0.48f to 0.70f, 0.24f to 0.70f, 0.17f to 0.95f)
        polygon(0.29f to 0.54f, 0.43f to 0.54f, 0.36f to 0.29f)
        polygon(0.52f to 0.05f, 0.68f to 0.05f, 0.97f to 0.95f, 0.81f to 0.95f)
    }
    if (reveal >= 1f) {
        drawPath(mark, Pal.orangeDeep)
        return
    }
    clipPath(mark) {
        val columns = 28
        val rows = 24
        val visible = (columns * rows * reveal).toInt()
        for (index in 0 until visible) {
            val column = index % columns
            val row = index / columns
            val u0 = column.toFloat() / columns
            val v0 = row.toFloat() / rows
            val u1 = (column + 1f) / columns
            val v1 = (row + 1f) / rows
            drawPath(quad(point(u0, v0), point(u1, v0), point(u1, v1), point(u0, v1)), Pal.orangeDeep)
        }
    }
}

private fun DrawScope.drawAiPanel(
    iso: Iso, x: Float, on: Boolean, runT: Float, offT: Float
) {
    val (y0, y1, z0, z1) = AiModuleLayout.screen
    val s = iso.s
    fun panel(yA: Float, yB: Float, zA: Float, zB: Float, inset: Float = 0f): Path = roundedQuadPath(
        listOf(iso.p(x, yB, zB), iso.p(x, yA, zB), iso.p(x, yA, zA), iso.p(x, yB, zA)), inset
    )
    val stopping = !on && runT > 0f && offT < RackPowerVisuals.SHUTDOWN_SECONDS
    val shutdown = if (stopping) RackPowerVisuals.shutdownProgress(offT) else 0f
    val centerZ = (z0 + z1) * 0.5f
    val halfHeight = (z1 - z0) * 0.5f * if (stopping) RackPowerVisuals.shutdownAperture(offT) else 1f
    val window = panel(y0, y1, centerZ - halfHeight, centerZ + halfHeight)

    for ((bounds, color) in listOf(AiModuleLayout.outerBezel to Color(0xFFD6D8DC), AiModuleLayout.innerBezel to Color.White)) {
        drawPath(panel(bounds.y0, bounds.y1, bounds.z0, bounds.z1, 0.02f), color)
    }
    drawPath(panel(y0, y1, z0, z1), Color(0xFF15171B))
    if (on || stopping) {
        drawPath(window, lerp(Color(0xFFFFE8D0), Color(0xFF15171B), shutdown))
    }
    clipPath(window) {
        if (!on && !stopping) return@clipPath
        if (runT < 2.9f) {
            drawAiBootMark(iso, x, RackPowerVisuals.bootProgress(runT))
        } else {
            val columns = 18
            val cell = (y1 - y0 - 0.2f) / columns
            val rowHeight = (z1 - z0 - 0.18f) / 12f
            fun pixel(column: Int, row: Float, color: Color) {
                val py = y1 - 0.1f - column * cell
                val pz = z1 - 0.1f - row * rowHeight
                drawPath(panel(py - cell * 0.75f, py, pz - rowHeight * 0.7f, pz), color)
            }
            val stepTime = 1.55f
            val elapsed = runT - 2.9f
            val message = (elapsed / stepTime).toInt()
            val progress = (elapsed % stepTime) / stepTime
            val scroll = ((progress - 0.85f) / 0.15f).coerceIn(0f, 1f)
            for (slot in 0..3) {
                val id = message - 3 + slot
                if (id < 0) continue
                val assistant = id % 2 == 1
                val length = 9 + (id * 3 % 7)
                val shown = if (id == message) (length * (progress / 0.72f).coerceIn(0f, 1f)).toInt() else length
                for (column in 0 until shown) {
                    if (column % 5 != 4) {
                        pixel(if (assistant) columns - 1 - column else column, 1f + slot * 2.2f - scroll * 2.2f,
                            if (assistant) Pal.orangeDeep else Color(0xFFBC9A7E))
                    }
                }
            }
            for (dot in 0..2) {
                pixel(7 + dot * 2, 10.5f, Pal.orangeDeep.copy(alpha = if ((elapsed * 5f).toInt() % 3 == dot) 1f else 0.25f))
            }
        }
    }
    if (stopping) {
        drawLine(Pal.orange.copy(alpha = (1f - shutdown) * 0.9f),
            iso.p(x, y0 + 0.08f, centerZ), iso.p(x, y1 - 0.08f, centerZ),
            (0.010f + 0.020f * (1f - shutdown)) * s, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawAiModule(iso: Iso, progress: Float, on: Boolean, runT: Float, offT: Float, lw: Float) {
    val d = AiModuleLayout.extension(progress)
    val xf = RW + d
    val (y0, y1, z0, z1) = AiModuleLayout.frame
    fun panel(x: Float, bounds: AiPanelBounds, round: Float = 0f): Path = roundedQuadPath(
        listOf(iso.p(x, bounds.y1, bounds.z1), iso.p(x, bounds.y0, bounds.z1),
            iso.p(x, bounds.y0, bounds.z0), iso.p(x, bounds.y1, bounds.z0)), round
    )
    fun face(path: Path, color: Color) {
        drawPath(path, color)
        drawPath(path, Pal.line, style = Stroke(lw, join = StrokeJoin.Round))
    }
    if (d > 0f) {
        face(panel(RW, AiModuleLayout.frame), Color(0xFF15171B))
        face(quad(iso.p(RW, y0, z1), iso.p(xf, y0, z1), iso.p(xf, y1, z1), iso.p(RW, y1, z1)), Pal.top)
        face(quad(iso.p(RW, y1, z0), iso.p(xf, y1, z0), iso.p(xf, y1, z1), iso.p(RW, y1, z1)), Pal.side)
    }

    // Closed and extended states use this same face; only its X plane changes.
    face(panel(xf, AiModuleLayout.frame, 0.025f), Color(0xFFFDFDFE))
    drawAiPanel(iso, xf, on, runT, offT)
    for ((y, z) in AiModuleLayout.screws) {
        val screw = Path().apply {
            for (k in 0..16) {
                val a = (2.0 * PI * k / 16).toFloat()
                val p = iso.p(xf, y + cos(a) * AiModuleLayout.SCREW_RADIUS, z + sin(a) * AiModuleLayout.SCREW_RADIUS)
                if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(screw, Color(0xFFE0E2E5))
        drawPath(screw, Pal.line, style = Stroke(lw * 0.65f))
    }
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

private fun opticHit(iso: Iso, point: Offset, extension: FloatArray, cableExtension: FloatArray, entrance: FloatArray): Int {
    for (k in 0 until RackHardwareLayout.OPTIC_COUNT) {
        if (entrance[k] < 1f) continue
        val x = RackHardwareLayout.opticX(k)
        val half = RackHardwareLayout.OPTIC_WIDTH / 2f + 0.035f
        val outletY = RackHardwareLayout.opticCableOutlet(k, extension[k], cableExtension[k]).y
        val z0 = RackHardwareLayout.OPTIC_Z0 - 0.035f
        val z1 = RackHardwareLayout.OPTIC_Z1 + 0.035f
        val faces = listOf(
            listOf(iso.p(x - half, outletY, z0), iso.p(x + half, outletY, z0), iso.p(x + half, outletY, z1), iso.p(x - half, outletY, z1)),
            listOf(iso.p(x - half, RackHardwareLayout.OPTIC_FRONT, z1), iso.p(x + half, RackHardwareLayout.OPTIC_FRONT, z1),
                iso.p(x + half, outletY, z1), iso.p(x - half, outletY, z1)),
            listOf(iso.p(x + half, RackHardwareLayout.OPTIC_FRONT, z0), iso.p(x + half, outletY, z0),
                iso.p(x + half, outletY, z1), iso.p(x + half, RackHardwareLayout.OPTIC_FRONT, z1))
        )
        if (faces.any { inQuad(point, it) }) return k
    }
    return -1
}

private fun DrawScope.drawOpticBody(m: TextMeasurer, iso: Iso, x: Float, visibleBack: Float,
    frontY: Float, z0: Float, z1: Float, lw: Float) {
    val half = RackHardwareLayout.OPTIC_WIDTH * 0.5f
    val rear = frontY - RackHardwareLayout.OPTIC_BODY_LENGTH
    val shoulder = frontY - RackHardwareLayout.OPTIC_HEAD_LENGTH
    box(iso, x - half, x + half, visibleBack, frontY, z0 + 0.02f, z1,
        Color(0xFFD9DCE0), Color(0xFFB8BDC3), Color(0xFF969CA4), lw)
    val topSurface = quad(iso.p(x - half, visibleBack, z1), iso.p(x + half, visibleBack, z1),
        iso.p(x + half, frontY, z1), iso.p(x - half, frontY, z1))
    clipPath(topSurface) {
        fun topPlate(xa: Float, xb: Float, ya: Float, yb: Float, round: Float = 0f) = roundedQuadPath(listOf(
            iso.p(xa, ya, z1 + 0.004f), iso.p(xb, ya, z1 + 0.004f),
            iso.p(xb, yb, z1 + 0.004f), iso.p(xa, yb, z1 + 0.004f)
        ), round)
        val shell = topPlate(x - half + 0.014f, x + half - 0.014f, rear + 0.018f, shoulder - 0.04f, 0.018f)
        drawPath(shell, Brush.linearGradient(listOf(Color(0xFFCACDD1), Color(0xFFF0F1F2), Color(0xFFD0D4D8)),
            iso.p(x - half, rear, z1), iso.p(x + half, rear, z1)))
        for (edgeX in listOf(x - half + 0.022f, x + half - 0.022f)) {
            drawLine(Color(0xFFF9F9FA), iso.p(edgeX, rear + 0.04f, z1 + 0.009f),
                iso.p(edgeX, shoulder - 0.045f, z1 + 0.009f), lw * 0.8f)
        }
        for (screwX in listOf(x - half + 0.06f, x + half - 0.06f)) {
            drawPath(iso.floorCircle(screwX, rear + 0.10f, z1 + 0.012f, 0.025f, 12), Color(0xFF959CA5))
            drawPath(iso.floorCircle(screwX, rear + 0.10f, z1 + 0.014f, 0.012f, 10), Color(0xFFDDE0E4))
        }
        drawPath(topPlate(x - half, x + half, shoulder - 0.045f, shoulder + 0.035f), Color(0xFFB6BBC1))
        drawLine(Color(0xFF888F98), iso.p(x - half + 0.01f, shoulder, z1 + 0.012f),
            iso.p(x + half - 0.01f, shoulder, z1 + 0.012f), lw * 0.8f)
        drawPath(topPlate(x - half + 0.025f, x + half - 0.025f, shoulder + 0.035f, frontY - 0.035f, 0.035f), Color(0xFFF3F3F1))
        for (labelY in listOf(shoulder + 0.065f, frontY - 0.095f)) {
            drawPath(topPlate(x - half + 0.05f, x + half - 0.05f, labelY, labelY + 0.035f), Color(0xFF8E949B))
        }
        topText(m, iso, "QSFP", x - half + 0.055f, shoulder + 0.13f, z1 + 0.014f,
            0.105f * iso.s, Color(0xFF545A62), true)
        for (q in 0 until 9) {
            val barcodeY = shoulder + 0.29f + q * 0.019f
            drawLine(Color(0xFF626972), iso.p(x - half + 0.06f, barcodeY, z1 + 0.015f),
                iso.p(x + half - 0.06f, barcodeY, z1 + 0.015f), lw * if (q % 3 == 0) 0.85f else 0.4f)
        }
    }
    val sideSurface = quad(iso.p(x + half, visibleBack, z0 + 0.02f), iso.p(x + half, frontY, z0 + 0.02f),
        iso.p(x + half, frontY, z1), iso.p(x + half, visibleBack, z1))
    clipPath(sideSurface) {
        drawLine(Color(0xFFBFC4CA), iso.p(x + half, rear + 0.06f, z0 + 0.075f),
            iso.p(x + half, shoulder - 0.05f, z0 + 0.075f), lw * 0.7f)
        for (fastenerY in listOf(rear + 0.11f, shoulder - 0.12f)) {
            val screw = roundedQuadPath(listOf(
                iso.p(x + half, fastenerY - 0.025f, z0 + 0.15f), iso.p(x + half, fastenerY + 0.025f, z0 + 0.15f),
                iso.p(x + half, fastenerY + 0.025f, z0 + 0.20f), iso.p(x + half, fastenerY - 0.025f, z0 + 0.20f)
            ), 0.35f)
            drawPath(screw, Color(0xFF69727D))
        }
    }
}

private fun DrawScope.drawOptics(m: TextMeasurer, iso: Iso, extension: FloatArray, cableExtension: FloatArray,
    entrance: FloatArray, lw: Float) {
    val back = RackHardwareLayout.OPTIC_FRONT
    val z0 = RackHardwareLayout.OPTIC_Z0
    val z1 = RackHardwareLayout.OPTIC_Z1
    val midZ = (z0 + z1) * 0.5f
    val half = RackHardwareLayout.OPTIC_WIDTH / 2f
    val widthScale = RackHardwareLayout.OPTIC_WIDTH / 0.66f
    val connectorTop = Color(0xFFB9BCC1)
    val connectorFront = Color(0xFF93979D)
    val connectorSide = Color(0xFF7D8289)
    fun front(xa: Float, xb: Float, y: Float, za: Float, zb: Float) = quad(
        iso.p(xa, y, za), iso.p(xb, y, za), iso.p(xb, y, zb), iso.p(xa, y, zb)
    )
    for (k in 0 until RackHardwareLayout.OPTIC_COUNT) {
        val x = RackHardwareLayout.opticX(k)
        val slot = front(x - half - 0.035f, x + half + 0.035f, back + 0.005f, z0 - 0.02f, z1 + 0.02f)
        drawPath(slot, Color(0xFF343C46))
        drawPath(slot, Color(0xFF9AA4AF), style = Stroke(lw))
        planeText(m, iso, "%02d".format(k + 1), x - 0.12f * widthScale, 0.15f, 0.105f * iso.s, Pal.mute, y = back + 0.006f)
    }
    for (k in 0 until RackHardwareLayout.OPTIC_COUNT) {
        if (entrance[k] <= 0f) continue
        val arrivalDepth = RackHardwareLayout.opticEntranceDepth(entrance[k])
        withTransform({ translate(-arrivalDepth * K * iso.s, arrivalDepth * 0.5f * iso.s) }) {
        val x = RackHardwareLayout.opticX(k)
        val y = RackHardwareLayout.opticFront(extension[k])
        val bodyBack = RackHardwareLayout.opticBodyBack(extension[k], entrance[k])
        drawOpticBody(m, iso, x, bodyBack, y, z0, z1, lw)
        drawPath(front(x - half + 0.045f, x + half - 0.045f, y + 0.006f, z0 + 0.045f, z1 - 0.045f), Color(0xFF27313B))
        val socketHalf = RackHardwareLayout.OPTIC_SOCKET_HALF_WIDTH
        val socket = front(x - socketHalf, x + socketHalf, y + 0.01f, midZ - 0.105f, midZ + 0.105f)
        drawPath(socket, Color(0xFF949AA2))
        drawPath(socket, Color(0xFFE6E8EA), style = Stroke(lw * 0.8f))
        drawPath(front(x - socketHalf + 0.022f, x + socketHalf - 0.022f, y + 0.012f,
            midZ - 0.083f, midZ + 0.083f), Color(0xFF20262D))
        val connectorY = RackHardwareLayout.opticConnectorOrigin(extension[k], cableExtension[k])
        val plugHalf = RackHardwareLayout.OPTIC_PLUG_HALF_WIDTH
        box(iso, x - plugHalf, x + plugHalf,
            max(y + 0.012f, connectorY + 0.015f), connectorY + 0.23f, midZ - 0.075f, midZ + 0.075f,
            Color(0xFFDEE1E5), Color(0xFFBFC4CA), Color(0xFF9CA4AD), lw * 0.6f)
        box(iso, x - 0.205f * widthScale, x + 0.205f * widthScale, connectorY + 0.22f, connectorY + 0.56f, midZ - 0.12f, midZ + 0.12f,
            connectorTop, connectorFront, connectorSide, lw * 0.75f)
        box(iso, x - 0.10f * widthScale, x + 0.10f * widthScale, connectorY + 0.28f, connectorY + 0.47f, midZ + 0.12f, midZ + 0.155f,
            Color(0xFFD1D3D6), connectorFront, connectorSide, lw * 0.6f)
        for (q in 0 until 4) {
            val yy = connectorY + 0.59f + q * 0.055f
            box(iso, x - 0.17f * widthScale, x + 0.17f * widthScale, yy - 0.02f, yy + 0.02f, midZ - 0.095f, midZ + 0.095f,
                Color(0xFFCACDD1), Color(0xFFA8ADB4), Color(0xFF939AA3), lw * 0.45f)
        }
        val outletY = RackHardwareLayout.opticCableOutlet(k, extension[k], cableExtension[k]).y
        box(iso, x - 0.135f * widthScale, x + 0.135f * widthScale, connectorY + 0.78f, outletY,
            midZ - 0.075f, midZ + 0.075f, Color(0xFFE4E5E5), Color(0xFFC3C7CA), Color(0xFFB1B7BC), lw * 0.5f)
        for (strand in 0 until RackHardwareLayout.OPTIC_STRAND_COUNT) {
            val outlet = RackHardwareLayout.opticCableOutlet(k, extension[k], cableExtension[k], strand)
            val collar = Path().apply {
                for (q in 0..24) {
                    val angle = (2.0 * PI * q / 24).toFloat()
                    val point = iso.p(outlet.x + 0.048f * cos(angle), outlet.y + 0.003f, outlet.z + 0.048f * sin(angle))
                    if (q == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
                close()
            }
            drawPath(collar, Color(0xFF7F858C))
            drawPath(collar, Color(0xFFE7E9EA), style = Stroke(lw * 0.6f))
            val cable = Path().apply {
                val start = iso.p(outlet.x, outlet.y + 9f, outlet.z)
                val end = iso.p(outlet.x, outlet.y + 0.006f, outlet.z)
                moveTo(start.x, start.y)
                lineTo(end.x, end.y)
            }
            drawPath(cable, Color(0xFFA9ADB2), style = Stroke(3.2.dp.toPx(), cap = StrokeCap.Butt))
            drawPath(cable, Color(0xFFE1E3E4), style = Stroke(2.25.dp.toPx(), cap = StrokeCap.Butt))
            drawPath(cable, Color(0xFFF9F9F7), style = Stroke(0.65.dp.toPx(), cap = StrokeCap.Butt))

        }
        val latchZ = z1 + 0.06f
        val latchHalf = half - 0.025f
        for (hingeX in listOf(x - latchHalf, x + latchHalf)) {
            drawLine(Color(0xFF8E949B), iso.p(hingeX, y - 0.015f, z1 - 0.045f),
                iso.p(hingeX, y - 0.015f, latchZ), 0.036f * iso.s, cap = StrokeCap.Round)
        }
        val latch = roundedQuadPath(listOf(
            iso.p(x - latchHalf, y - 0.025f, latchZ),
            iso.p(x + latchHalf, y - 0.025f, latchZ),
            iso.p(x + latchHalf, y + 0.50f, latchZ),
            iso.p(x - latchHalf, y + 0.50f, latchZ)
        ), 0.14f)
        drawPath(latch, Color(0xFF858D96), style = Stroke(0.044f * iso.s, join = StrokeJoin.Round))
        drawPath(latch, Color(0xFFDEE1E4), style = Stroke(0.027f * iso.s, join = StrokeJoin.Round))
        for (hingeX in listOf(x - latchHalf, x + latchHalf)) {
            drawPath(iso.floorCircle(hingeX, y - 0.015f, latchZ + 0.002f, 0.026f, 12), Color(0xFF858D96))
        }
        val led = iso.p(x + 0.25f * widthScale, y + 0.008f, z1 - 0.09f)
        drawCircle(Color(0xFF8C97A4), 0.9.dp.toPx(), led)
        }
    }
}

private class RackPictureCache(private val scope: CoroutineScope) {
    private var key: Any? = null
    private var picture: android.graphics.Picture? = null
    private var bitmap by mutableStateOf<ImageBitmap?>(null)
    private var rasterJob: Job? = null

    fun draw(drawScope: DrawScope, nextKey: Any, block: DrawScope.() -> Unit) {
        if (key != nextKey) {
            rasterJob?.cancel()
            bitmap = null
            val recording = android.graphics.Picture()
            val width = drawScope.size.width.toInt().coerceAtLeast(1)
            val height = drawScope.size.height.toInt().coerceAtLeast(1)
            val canvas = recording.beginRecording(width, height)
            try {
                CanvasDrawScope().draw(drawScope, drawScope.layoutDirection, Canvas(canvas), drawScope.size, block)
            } finally {
                recording.endRecording()
            }
            picture = recording
            key = nextKey
            rasterJob = scope.launch {
                delay(100)
                val image = withContext(Dispatchers.Default) {
                    android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).apply {
                        android.graphics.Canvas(this).drawPicture(recording)
                    }.asImageBitmap()
                }
                if (key == nextKey) bitmap = image
            }
        }
        val image = bitmap
        if (image != null) with(drawScope) { drawImage(image) }
        else drawScope.drawIntoCanvas { it.nativeCanvas.drawPicture(picture!!) }
    }
}

private class RackReadoutCache {
    private val values = LinkedHashMap<List<Any>, String>()

    fun fit(measurer: TextMeasurer, text: String, size: Float, width: Float, scope: DrawScope): String {
        val fontSize = with(scope) { size.toSp() }
        val key = listOf(text, fontSize, width, scope.fontScale)
        return values.getOrPut(key) {
            if (values.size >= 64) values.clear()
            val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize)
            var value = text
            while (value.isNotEmpty() && measurer.measure(value, style, softWrap = false).size.width > width) {
                value = value.dropLast(1)
            }
            value
        }
    }
}

private fun DrawScope.drawOpticSignals(
    iso: Iso, extension: FloatArray, cableExtension: FloatArray, entrance: FloatArray,
    flowEnabled: BooleanArray, running: Boolean, t: Float
) {
    val midZ = (RackHardwareLayout.OPTIC_Z0 + RackHardwareLayout.OPTIC_Z1) * 0.5f
    val widthScale = RackHardwareLayout.OPTIC_WIDTH / 0.66f
    for (k in 0 until RackHardwareLayout.OPTIC_COUNT) {
        if (entrance[k] <= 0f) continue
        val depth = RackHardwareLayout.opticEntranceDepth(entrance[k])
        withTransform({ translate(-depth * K * iso.s, depth * 0.5f * iso.s) }) {
            if (flowEnabled[k]) {
                for (strand in 0 until RackHardwareLayout.OPTIC_STRAND_COUNT) {
                    val outlet = RackHardwareLayout.opticCableOutlet(k, extension[k], cableExtension[k], strand)
                    val start = iso.p(outlet.x, outlet.y + 9f, midZ)
                    val end = iso.p(outlet.x, outlet.y + 0.006f, midZ)
                    for (pulse in 0 until 5) {
                        val phase = (t * 0.60f + pulse / 5f + strand * 0.07f + k * 0.035f) % 1f
                        val from = start + (end - start) * phase
                        val to = start + (end - start) * min(phase + 0.065f, 1f)
                        drawLine(Pal.orange.copy(alpha = 0.22f), from, to, 4.4.dp.toPx(), StrokeCap.Round)
                        drawLine(Pal.orange.copy(alpha = 0.9f), from, to, 1.7.dp.toPx(), StrokeCap.Round)
                        drawLine(Color.White.copy(alpha = 0.85f), from, to, 0.55.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            if (running && entrance[k] >= 1f && extension[k] < 0.1f && cableExtension[k] < 0.1f) {
                val led = iso.p(RackHardwareLayout.opticX(k) + 0.25f * widthScale,
                    RackHardwareLayout.opticFront(extension[k]) + 0.008f, RackHardwareLayout.OPTIC_Z1 - 0.09f)
                drawCircle(Pal.orange.copy(alpha = 0.65f + 0.35f * sin(t * 2.8f + k)), 0.9.dp.toPx(), led)
            }
        }
    }
}

private const val DRV_N = RackHardwareLayout.DRIVE_COUNT
private const val DRV_X0 = RackHardwareLayout.DRIVE_X0
private const val DRV_PITCH = RackHardwareLayout.DRIVE_PITCH
private const val DRV_T = RackHardwareLayout.DRIVE_THICKNESS
private const val DRV_Y0 = RackHardwareLayout.DRIVE_Y0
private const val DRV_Y1 = RackHardwareLayout.DRIVE_Y1
private const val DRV_H = RackHardwareLayout.DRIVE_HEIGHT
private const val DRV_UP = RackHardwareLayout.DRIVE_TRAVEL
private val CAD_TOP = Color(0xFFE4E6EA)
private val CAD_FRONT = Color(0xFFB8BDC5)
private val CAD_SIDE = Color(0xFF929AA5)

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
            m.measure(text, TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fs.toSp(),
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
            ), softWrap = false, maxLines = 1),
            color = color, topLeft = Offset.Zero
        )
    }
}

private fun DrawScope.driveTopDeco(m: TextMeasurer, iso: Iso, k: Int, top: Float, on: Boolean, t: Float, lw: Float) {
    val x0 = drvX(k)
    val x1 = x0 + DRV_T
    val xc = (x0 + x1) * 0.5f
    fun plate(xa: Float, xb: Float, ya: Float, yb: Float, inset: Float = 0.035f) = roundedQuadPath(listOf(
        iso.p(xa, ya, top + 0.004f), iso.p(xb, ya, top + 0.004f),
        iso.p(xb, yb, top + 0.004f), iso.p(xa, yb, top + 0.004f)
    ), inset)
    val lid = plate(x0 + 0.025f, x1 - 0.025f, DRV_Y0 + 0.025f, DRV_Y1 - 0.025f)
    drawPath(lid, Brush.linearGradient(listOf(Color(0xFFF0F1F3), Color(0xFFC8CED5), Color(0xFFE8EBEF)),
        iso.p(x0, DRV_Y0, top), iso.p(x1, DRV_Y1, top)))
    drawPath(lid, Color(0xFF9099A5), style = Stroke(lw * 0.85f))
    drawPath(iso.floorCircle(xc, DRV_Y0 + 0.35f, top + 0.006f, 0.19f, 28), Color(0xFFD4DAE0))
    drawPath(iso.floorCircle(xc, DRV_Y0 + 0.35f, top + 0.006f, 0.19f, 28), Color(0xFFAEB6C0), style = Stroke(lw * 0.8f))
    val labelY = DRV_Y0 + 0.68f
    drawPath(plate(x0 + 0.075f, x1 - 0.075f, labelY, DRV_Y1 - 0.33f), Color(0xFFFCFCFB))
    drawPath(plate(x0 + 0.075f, x1 - 0.075f, labelY, labelY + 0.21f, 0f), Color(0xFF444B55))
    topText(m, iso, "D${k + 1}", x0 + 0.14f, labelY + 0.04f, top + 0.01f, 0.14f * iso.s, Color.White, true)
    topText(m, iso, "HDD", x0 + 0.12f, labelY + 0.35f, top + 0.01f, 0.115f * iso.s, Pal.ink, true)
    topText(m, iso, "8TB", x0 + 0.12f, labelY + 0.57f, top + 0.01f, 0.11f * iso.s, Pal.mute)
    for (q in 0 until 11) {
        val y = labelY + 0.86f + q * 0.025f
        drawLine(Color(0xFF5D6570), iso.p(x0 + 0.12f, y, top + 0.009f),
            iso.p(x1 - if (q % 3 == 0) 0.15f else 0.10f, y, top + 0.009f), lw * if (q % 3 == 0) 1.1f else 0.55f)
    }
    for (x in listOf(x0 + 0.065f, x1 - 0.065f)) {
        for (y in listOf(DRV_Y0 + 0.09f, DRV_Y1 - 0.11f)) {
            drawPath(iso.floorCircle(x, y, top + 0.01f, 0.027f, 12), Color(0xFF8E98A4))
        }
    }
    val latch = plate(x0 + 0.09f, x1 - 0.09f, DRV_Y1 - 0.22f, DRV_Y1 - 0.07f, 0.08f)
    drawPath(latch, if (on) Pal.orange else Color(0xFFA5ADB7))
    drawPath(latch, Pal.line, style = Stroke(lw * 0.7f))
}

private fun DrawScope.drawDriveBody(iso: Iso, k: Int, bottom: Float, top: Float, fullTop: Float, lw: Float, showTop: Boolean = true) {
    val x0 = drvX(k)
    val x1 = x0 + DRV_T
    box(iso, x0, x1, DRV_Y0, DRV_Y1, bottom, top, CAD_TOP, CAD_FRONT, CAD_SIDE, lw, showTop)
    val bodySide = quad(iso.p(x1, DRV_Y0, bottom), iso.p(x1, DRV_Y1, bottom),
        iso.p(x1, DRV_Y1, top), iso.p(x1, DRV_Y0, top))
    clipPath(bodySide) {
        fun cover(ya: Float, yb: Float, za: Float, zb: Float) = roundedQuadPath(listOf(
            iso.p(x1, ya, zb), iso.p(x1, yb, zb), iso.p(x1, yb, za), iso.p(x1, ya, za)
        ), 0.055f)
        val base = fullTop - DRV_H
        drawPath(cover(DRV_Y0 + 0.025f, DRV_Y1 - 0.025f, base + 0.03f, fullTop - 0.025f), Color(0xFFD4D9DF))
        drawPath(cover(DRV_Y0 + 0.07f, DRV_Y1 - 0.07f, base + 0.09f, fullTop - 0.07f),
            Color(0xFF949EA9), style = Stroke(lw))
        drawPath(cover(DRV_Y0 + 0.14f, DRV_Y0 + 0.65f, base + 0.22f, fullTop - 0.16f), Color(0xFFF8F8F5))
        for (q in 0 until 8) {
            val y = DRV_Y0 + 0.20f + q * 0.046f
            drawLine(Color(0xFF616C78), iso.p(x1, y, base + 0.33f), iso.p(x1, y, fullTop - 0.28f),
                lw * if (q % 3 == 0) 1.3f else 0.65f)
        }
        drawPath(cover(DRV_Y0, DRV_Y1, base, base + 0.075f), Color(0xFF434D58))
        for (y in listOf(DRV_Y0 + 0.10f, DRV_Y1 - 0.10f)) {
            for (z in listOf(base + 0.14f, fullTop - 0.13f)) {
                val center = iso.p(x1, y, z)
                drawCircle(Color(0xFF7C8793), 1.1.dp.toPx(), center)
                drawCircle(Color(0xFFE5E8EC), 0.45.dp.toPx(), center)
            }
        }
    }
    val front = quad(iso.p(x0, DRV_Y1, bottom), iso.p(x1, DRV_Y1, bottom),
        iso.p(x1, DRV_Y1, top), iso.p(x0, DRV_Y1, top))
    clipPath(front) {
        drawLine(Color(0xFFE7EBEF), iso.p(x0 + 0.075f, DRV_Y1, bottom), iso.p(x0 + 0.075f, DRV_Y1, top), lw * 1.5f)
        drawLine(Color(0xFF939EAA), iso.p(x1 - 0.075f, DRV_Y1, bottom), iso.p(x1 - 0.075f, DRV_Y1, top), lw)
        for (z in listOf(fullTop - 0.28f, fullTop - DRV_H + 0.22f)) {
            drawPath(iso.frontCircle((x0 + x1) * 0.5f, z, 0.042f).apply {
                translate(iso.p(0f, DRV_Y1, 0f) - iso.p(0f, RD, 0f))
            }, Color(0xFF8995A2))
        }
    }
}

/** Drive bodies below the rim, drawn inside the clipped cavity. */
private fun DrawScope.drawDrivesBelow(m: TextMeasurer, iso: Iso, e: FloatArray, running: Boolean, t: Float, lw: Float) {
    for (k in 0 until DRV_N) {
        val top = drvTop(e[k]); val x0 = drvX(k)
        val flush = top <= RH
        drawDriveBody(iso, k, top - DRV_H, min(top, RH), top, lw, showTop = flush)
        if (flush) driveTopDeco(m, iso, k, top, running, t, lw)
    }
}

/** The part of an ejected drive that sticks out above the rim, drawn on top of the lid. */
private fun DrawScope.drawDrivesAbove(m: TextMeasurer, iso: Iso, e: FloatArray, running: Boolean, t: Float, lw: Float) {
    val order = (0 until DRV_N).filter { drvTop(e[it]) > RH + 0.004f }.sortedBy { drvX(it) + e[it] }
    for (k in order) {
        val top = drvTop(e[k]); val x0 = drvX(k); val x1 = x0 + DRV_T
        drawDriveBody(iso, k, RH, top, top, lw)
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
                val b = if (running) 0.5f + 0.5f * sin(t * 3.1f + k * 1.3f) else 0f
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
                drawPath(scr, if (running) lerp(Color(0xFF1B1D24), Color(0xFF2C2833), b) else Color(0xFF14161B))
                if (!running) return@clipPath
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
        planeText(m, iso, "DISK ARRAY", 0.7f, RH - 0.3f, 0.19f * iso.s, if (running) Pal.orange.copy(alpha = 0.9f) else Pal.mute, true, c)
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
    val measurer = rememberTextMeasurer(cacheSize = 192)
    val cacheScope = rememberCoroutineScope()
    val shellPicture = remember { RackPictureCache(cacheScope) }
    val opticsPicture = remember { RackPictureCache(cacheScope) }
    val readoutCache = remember { RackReadoutCache() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var powerStage by remember { mutableStateOf(RackPowerStage.OFF) }
    var enabledBays by remember { mutableIntStateOf(0) }
    val powerStageNow by rememberUpdatedState(powerStage)
    val onSelectNow by rememberUpdatedState(onSelect)
    val onPowerNow by rememberUpdatedState(onPower)
    var fanSpeed by remember { mutableFloatStateOf(0f) }
    val powerOn = animateFloatAsState(if (running) 1f else 0f, tween(200), label = "power")
    val glowOn = animateFloatAsState(if (running && powerStage.includes(RackPowerStage.BASE)) 1f else 0f, tween(320), label = "glow")
    val topOn = animateFloatAsState(if (running && powerStage.includes(RackPowerStage.TOP)) 1f else 0f, tween(300), label = "top")
    val cableOn = animateFloatAsState(if (running && powerStage.includes(RackPowerStage.CABLES)) 1f else 0f, tween(380), label = "cables")
    val receiverPowered = powerStage.includes(RackPowerStage.RECEIVER) && running
    val receiverA = animateFloatAsState(if (receiverPowered) 1f else 0f,
        tween(if (receiverPowered) RackStartupTiming.RECEIVER_MILLIS else 500), label = "receiver")
    val litA = List(BAY_COUNT) {
        animateFloatAsState(if (running && it < enabledBays && lit[it]) 1f else 0f, tween(240), label = "lit$it")
    }
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
    val opticTarget = remember { mutableStateListOf(*Array(RackHardwareLayout.OPTIC_COUNT) { OpticState.CONNECTED }) }
    val opticEntrance = remember { List(RackHardwareLayout.OPTIC_COUNT) { Animatable(0f) } }
    val powerSequence = remember {
        RackPowerSequence(
            entrance = { opticEntrance[it].value },
            resetOptic = { opticTarget[it] = OpticState.CONNECTED },
            animateOptic = { index, target, duration -> opticEntrance[index].animateTo(target, tween(duration)) },
            stage = { powerStage = it },
            bays = { enabledBays = it }
        )
    }
    LaunchedEffect(running) { powerSequence.play(running) }
    val opticA = List(RackHardwareLayout.OPTIC_COUNT) {
        animateFloatAsState(opticTarget[it].moduleExtension, spring(0.78f, Spring.StiffnessMediumLow), label = "optic$it")
    }
    val opticCableA = List(RackHardwareLayout.OPTIC_COUNT) {
        animateFloatAsState(opticTarget[it].cableExtension, spring(0.82f, Spring.StiffnessMediumLow), label = "opticCable$it")
    }
    val driveTarget = remember { mutableStateListOf(*Array(DRV_N) { false }) }
    val driveA = List(DRV_N) {
        animateFloatAsState(if (driveTarget[it]) 1f else 0f, spring(0.55f, Spring.StiffnessMedium), label = "drv$it")
    }
    LaunchedEffect(lidOut) { if (!lidOut) for (k in 0 until DRV_N) driveTarget[k] = false }
    var macOut by remember { mutableStateOf(false) }
    val macA = animateFloatAsState(if (macOut) 1f else 0f, spring(0.62f, Spring.StiffnessMediumLow), label = "mac")
    val fanAng = remember { mutableStateListOf(0f, 20f, 40f, 0f) }
    val fanHeld = remember { BooleanArray(FAN_N) }
    val fanKick = remember { FloatArray(FAN_N) }
    var aiRunT by remember { mutableFloatStateOf(0f) }
    var aiOffT by remember { mutableFloatStateOf(RackPowerVisuals.SHUTDOWN_SECONDS) }
    val bump = remember { Animatable(0f) }
    var previousSelected by remember { mutableIntStateOf(selected) }
    LaunchedEffect(selected) {
        if (previousSelected != selected) {
            for (i in 0 until BAY_COUNT) popTarget[i] = i == selected
            previousSelected = selected
        }
        bump.snapTo(1f)
        bump.animateTo(0f, tween(650))
    }

    var t by remember { mutableFloatStateOf(0f) }
    var flow by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            var wasPowered = false
            while (true) {
                val needsFrames = runningNow || fanSpeed > 0f ||
                    aiOffT < RackPowerVisuals.SHUTDOWN_SECONDS || fanKick.any { abs(it) > 0.1f }
                if (!needsFrames) {
                    last = 0L
                    snapshotFlow { runningNow }.first { it }
                }
                withFrameNanos { n ->
                    if (last != 0L) {
                        val dt = ((n - last) / 1e9f).coerceIn(0f, 0.25f)
                        val online = runningNow
                        val stage = powerStageNow
                        val aiShouldRun = online && stage.includes(RackPowerStage.AI) && liftA.value < 0.5f
                        val fansMayRun = online && stage.includes(RackPowerStage.FANS) && liftA.value < 0.5f
                        if (online) t += dt
                        fanSpeed = RackPowerVisuals.advanceFanSpeed(fanSpeed, fansMayRun, dt)
                        for (k in 0 until FAN_N) {
                            if (!fansMayRun) fanKick[k] = 0f
                            if (!fanHeld[k]) {
                                val velocity = 600f * fanSpeed * (1f - liftA.value) + fanKick[k]
                                if (abs(velocity) > 0.01f) fanAng[k] = (fanAng[k] + dt * velocity) % 360f
                                fanKick[k] *= exp(-2.5f * dt)
                            }
                        }
                        if (online && stage.includes(RackPowerStage.BAYS)) flow += dt * (0.22f + 0.55f * glowOn.value)
                        if (aiShouldRun) {
                            if (!wasPowered) aiRunT = 0f
                            aiRunT += dt
                            aiOffT = 0f
                        } else {
                            aiOffT = (aiOffT + dt).coerceAtMost(RackPowerVisuals.SHUTDOWN_SECONDS)
                            if (aiOffT >= RackPowerVisuals.SHUTDOWN_SECONDS) aiRunT = 0f
                        }
                        wasPowered = aiShouldRun
                    }
                    last = n
                }
            }
        }
    }

    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { oBase ->
                val iso = isoFor(size.width.toFloat(), size.height.toFloat(), 1.5f * liftA.value + 1.9f * lidA.value)
                val optic = if (runningNow) opticHit(iso, oBase,
                    FloatArray(RackHardwareLayout.OPTIC_COUNT) { opticA[it].value },
                    FloatArray(RackHardwareLayout.OPTIC_COUNT) { opticCableA[it].value },
                    FloatArray(RackHardwareLayout.OPTIC_COUNT) { opticEntrance[it].value }) else -1
                if (optic >= 0) { opticTarget[optic] = opticTarget[optic].next(); return@detectTapGestures }
                val o = Offset(oBase.x, oBase.y + LIFT_H * liftA.value.coerceIn(0f, 1f) * Z_SCALE * iso.s)
                val md = AiModuleLayout.extension(macA.value)
                val moduleFrame = AiModuleLayout.frame
                val (my, mz) = iso.unprojX(o, RW + md)
                var moduleHit = moduleFrame.contains(my, mz)
                if (!moduleHit && md > 0f) {
                    val (topX, topY) = iso.unprojZ(o, moduleFrame.z1)
                    val (sideX, sideZ) = iso.unprojY(o, moduleFrame.y1)
                    moduleHit = (topX in RW..(RW + md) && topY in moduleFrame.y0..moduleFrame.y1) ||
                        (sideX in RW..(RW + md) && sideZ in moduleFrame.z0..moduleFrame.z1)
                }
                if (moduleHit) { macOut = !macOut; return@detectTapGestures }
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
                        if (i == previousSelected) {
                            val open = !popTarget[i]
                            for (j in 0 until BAY_COUNT) popTarget[j] = false
                            popTarget[i] = open
                        }
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
                        val onFan = listOf(1.15f, RW / 2f, RW - 1.15f).any { fx -> (tx - fx) * (tx - fx) + (ty - RackLayout.TOP_FAN_Y) * (ty - RackLayout.TOP_FAN_Y) < 0.74f * 0.74f }
                        if (!onFan) { lidOut = !lidOut; return@detectTapGestures }
                    }
                }
                val (sx, sy) = iso.unprojZ(oBase, RZ0)
                val topHit = sx in -0.8f..(RW + 0.8f) && sy in -0.8f..(RD + 0.8f) && (liftA.value > 0.5f || sy >= RD)
                val (fx2, fz2) = iso.unprojY(oBase, RD + 0.8f)
                val frontHit = fx2 in -0.8f..(RW + 0.8f) && fz2 in RackHardwareLayout.BASE_BOTTOM..RZ0
                if (topHit || frontHit) liftOut = !liftOut
            }
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!runningNow || liftA.value > 0.5f) return@awaitEachGesture
                val iso = isoFor(size.width.toFloat(), size.height.toFloat(), 1.5f * liftA.value + 1.9f * lidA.value)
                val shiftY = LIFT_H * liftA.value.coerceIn(0f, 1f) * Z_SCALE * iso.s
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
        val lw = 0.7.dp.toPx()
        val dead = 1f - liftA.value.coerceIn(0f, 1f)
        val glow = glowOn.value * dead
        val live = running && powerStage.includes(RackPowerStage.BAYS) && dead > 0.5f
        val aiLive = running && powerStage.includes(RackPowerStage.AI) && dead > 0.5f
        val dataLive = running && powerStage.includes(RackPowerStage.DATA) && dead > 0.5f
        val topGlow = topOn.value * dead
        val cableGlow = cableOn.value * dead
        val powerGlow = powerOn.value * dead
        val breath = 0.75f + 0.25f * sin(t * 2.2f)
        // 脉冲光条：2s 周期正弦呼吸，alpha 0.35~1.0，phase 错开形成扫动
        fun pulse(phase: Float = 0f) = 0.675f + 0.325f * sin(t * 3.1416f + phase)
        val fan = fanSpeed * dead
        val liftPx = LIFT_H * liftA.value.coerceIn(0f, 1f) * Z_SCALE * s

        fun face(path: Path, fill: Color) {
            drawPath(path, fill)
            drawPath(path, Pal.line, style = Stroke(lw, join = StrokeJoin.Round))
        }

        val shadowCenter = iso.p(RW * 0.5f, RD * 0.5f, 0f)
        withTransform({ scale(1f, 0.4f, pivot = shadowCenter) }) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFC5B6A9).copy(alpha = 0.16f), Color.Transparent), shadowCenter, 4.9f * s), 4.9f * s, shadowCenter)
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
        face(quad(iso.p(bx0, by1, RackHardwareLayout.BASE_BOTTOM), iso.p(bx1, by1, RackHardwareLayout.BASE_BOTTOM), iso.p(bx1, by1, bz), iso.p(bx0, by1, bz)), Pal.front)
        face(quad(iso.p(bx1, by0, RackHardwareLayout.BASE_BOTTOM), iso.p(bx1, by1, RackHardwareLayout.BASE_BOTTOM), iso.p(bx1, by1, bz), iso.p(bx1, by0, bz)), Pal.side)
        face(quad(iso.p(bx0, by0, bz), iso.p(bx1, by0, bz), iso.p(bx1, by1, bz), iso.p(bx0, by1, bz)), Pal.top)
        fun baseRail(x0: Float, y0: Float, x1: Float, y1: Float) {
            val start = iso.p(x0, y0, bz + 0.008f)
            val end = iso.p(x1, y1, bz + 0.008f)
            drawLine(Color.White, start + Offset(0f, -lw), end + Offset(0f, -lw), s * 0.09f, cap = StrokeCap.Round)
            drawLine(Color(0xFFC3C6CC), start, end, s * 0.075f, cap = StrokeCap.Round)
            drawLine(lerp(Color(0xFFDDE0E4), Pal.orange, glow), start, end, s * 0.027f, cap = StrokeCap.Round)
        }
        baseRail(0.35f, RD + 0.47f, RW - 0.35f, RD + 0.47f)
        baseRail(RW + 0.46f, 0.25f, RW + 0.46f, RD - 0.25f)
        for ((sx, sy) in listOf(bx0 + 0.3f to by0 + 0.3f, bx1 - 0.3f to by0 + 0.3f, bx1 - 0.3f to by1 - 0.3f, bx0 + 0.3f to by1 - 0.3f)) {
            val c = iso.floorCircle(sx, sy, bz, 0.1f, 12)
            drawPath(c, Pal.lineSoft)
            drawPath(c, Pal.line, style = Stroke(lw))
        }

        val opticExtension = FloatArray(RackHardwareLayout.OPTIC_COUNT) { opticA[it].value }
        val opticCableExtension = FloatArray(RackHardwareLayout.OPTIC_COUNT) { opticCableA[it].value }
        val entrances = FloatArray(RackHardwareLayout.OPTIC_COUNT) { opticEntrance[it].value }
        val opticKey = listOf(iso.s, iso.cx, iso.cy, lw, fontScale) +
            opticExtension.toList() + opticCableExtension.toList() + entrances.toList()
        opticsPicture.draw(this, opticKey) {
            drawOptics(measurer, iso, opticExtension, opticCableExtension, entrances, lw)
        }
        drawOpticSignals(iso, opticExtension, opticCableExtension, entrances,
            BooleanArray(RackHardwareLayout.OPTIC_COUNT) {
                RackHardwareLayout.opticFlowEnabled(running && powerStage.includes(RackPowerStage.CABLES),
                    opticTarget[it], entrances[it], opticExtension[it], opticCableExtension[it])
            }, running, t)

        // power cable behind the rack, laid on the floor and leaving the screen
        run {
            val n = 90
            val pts = List(n + 1) { k ->
                val u = RackLayout.CABLE_START + (1f - RackLayout.CABLE_START) * k / n.toFloat()
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
            drawPath(cable, lerp(Pal.lineSoft, Pal.orange, cableGlow), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // 数据脉冲：4 个短光段（约路径 5%）沿电缆快速循环流动，~1 秒一圈
            if (cableGlow > 0.02f) {
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
                    drawPath(segPath, Pal.orange.copy(alpha = 0.22f * cableGlow), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(segPath, Pal.orange.copy(alpha = 0.85f * cableGlow), style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(segPath, Color.White.copy(alpha = 0.7f * cableGlow), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
                    val head = pm2.getPosition(u * len)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Pal.orange.copy(alpha = 0.35f * cableGlow), Color.Transparent),
                            center = head, radius = 9.dp.toPx()
                        ), radius = 9.dp.toPx(), center = head
                    )
                }
            }
        }

        if (liftA.value > 0.02f) drawPcb(iso, LIFT_H * liftA.value.coerceIn(0f, 1f), t, flow, running, lw)

        withTransform({ translate(0f, -liftPx) }) {
        // rack body
        val frontPath = iso.frontQuad(0f, RZ0, RW - RackLayout.SHELL_RADIUS, RH - RackLayout.SHELL_RADIUS)
        shellPicture.draw(this, listOf(iso.s, iso.cx, iso.cy, lw)) { drawCabinetShell(iso, lw) }
        drawLine(Color.White, iso.f(0.11f, RZ0 + 0.15f), iso.f(0.11f, RH - 0.14f), 2.dp.toPx())
        drawLine(Pal.lineSoft, iso.f(0.29f, RZ0 + 0.18f), iso.f(0.29f, RH - 0.16f), lw)
        for (k in 0 until 36) {
            val z = RZ0 + 0.28f + k * 0.18f
            val warm = k % 6 == 0
            drawCircle(if (warm && live) Pal.orange.copy(alpha = 0.8f) else Pal.line, (if (warm) 0.9f else 0.65f).dp.toPx(), iso.f(0.2f, z))
        }

        drawLine(lerp(Pal.lineSoft, Pal.orange, glow), iso.f(RW - 0.24f, RH - 0.6f), iso.f(RW - 0.24f, RH - 1.05f), lw * 1.8f, cap = StrokeCap.Round)

        val sideShell = roundedQuadPath(listOf(
            iso.p(RW, 0.08f, 0.34f), iso.p(RW, RD - 0.25f, 0.34f),
            iso.p(RW, RD - 0.25f, RH - 0.25f), iso.p(RW, 0.08f, RH - 0.25f)
        ), 0.025f)
        drawPath(sideShell, Brush.linearGradient(listOf(Color(0xFFFCFCFD), Color(0xFFE8E9EC)), iso.p(RW, RD, RH), iso.p(RW, 0f, 0f)))
        drawPath(sideShell, Pal.line, style = Stroke(lw * 0.8f))
        drawLine(Color(0xFFB5B9C1), iso.p(RW, 0.075f, RZ0 + 0.12f), iso.p(RW, 0.075f, RH - 0.18f), lw * 1.2f)
        drawLine(Color.White, iso.p(RW, 0.13f, RZ0 + 0.12f), iso.p(RW, 0.13f, RH - 0.18f), lw * 1.5f)
        fun sideQuad(y0: Float, y1: Float, z0: Float, z1: Float, inset: Float = 0f): Path = roundedQuadPath(
            listOf(iso.p(RW, y1, z1), iso.p(RW, y0, z1), iso.p(RW, y0, z0), iso.p(RW, y1, z0)), inset
        )
        val displayY0 = AiModuleLayout.screen.y0
        val displayY1 = AiModuleLayout.screen.y1
        val sideItems = listOf(
            Triple("TOTAL TOK.", fmtTokens(totalTokens), 5.08f),
            Triple("TTFT MS", if (ttftMs >= 0) "$ttftMs" else "--", 3.58f),
            Triple("TOK/S", if (tokPerSec >= 0) "%.1f".format(tokPerSec) else "--", 2.08f)
        )
        for (item in sideItems) {
            val (label, value, originalTop) = item
            val top = originalTop + RackLayout.SIDE_DISPLAY_Z_SHIFT
            val bottom = top - 1.27f
            drawPath(sideQuad(displayY0 - 0.06f, displayY1 + 0.06f, bottom - 0.05f, top + 0.05f), Color.White)
            drawPath(sideQuad(displayY0 - 0.06f, displayY1 + 0.06f, bottom - 0.05f, top + 0.05f), Pal.line, style = Stroke(lw * 0.8f))
            drawPath(sideQuad(displayY0, displayY1, bottom, top), Brush.linearGradient(listOf(Color(0xFF353535), Color(0xFF232426)), iso.p(RW, displayY1, top), iso.p(RW, displayY0, bottom)))
            if (dataLive) sideText(measurer, iso, label, displayY1 - 0.1f, top - 0.22f, 0.19f * s, Pal.orange)
            val cell = min(0.088f, 1.24f / glyphCols(value))
            val digits = iso.pixelPath(value, displayY1 - 0.12f, top - 0.5f, cell)
            if (dataLive) drawPath(digits, Pal.orange.copy(alpha = 0.86f + 0.1f * sin(t * 1.8f)))
            drawCircle(Color(0xFF525456), 1.3.dp.toPx(), iso.p(RW, displayY0 + 0.1f, top - 0.12f))
        }
        fun ventBank(top: Float, count: Int) {
            for (k in 0 until count) {
                val z = top - k * 0.13f
                drawLine(Color.White, iso.p(RW, 0.25f, z - 0.025f), iso.p(RW, 0.65f, z - 0.025f), lw * 2.6f, cap = StrokeCap.Round)
                drawLine(Color(0xFFB3B5B9), iso.p(RW, 0.25f, z), iso.p(RW, 0.65f, z), lw * 2.1f, cap = StrokeCap.Round)
            }
        }
        ventBank(6.6f, 11)
        ventBank(2.9f, 10)
        sideText(measurer, iso, "AI", 0.75f, 4.72f, 0.21f * s, Pal.mute)
        sideText(measurer, iso, "COMPUTE", 0.75f, 4.4f, 0.14f * s, Pal.mute)
        sideText(measurer, iso, "UNIT", 0.75f, 4.1f, 0.15f * s, Pal.mute)
        sideText(measurer, iso, "R-04", 0.75f, 3.79f, 0.16f * s, Pal.mute)
        for (k in 0 until 18) {
            val y = displayY0 + 0.04f + k * 0.081f
            drawLine(Pal.lineSoft, iso.p(RW, y, 0.38f), iso.p(RW, y, 0.51f), lw)
        }
        drawPath(sideQuad(0.22f, 0.67f, 0.46f, 1.13f, 0.06f), Color(0xFFF1F2F5))
        drawPath(sideQuad(0.22f, 0.67f, 0.46f, 1.13f, 0.06f), Pal.line, style = Stroke(lw))
        drawLine(Color.White, iso.p(RW, 0.66f, 0.48f), iso.p(RW, 0.66f, 1.09f), lw)
        val serviceMark = Path().apply {
            val a = iso.p(RW, 0.38f, 5.13f); val b = iso.p(RW, 0.57f, 5.13f); val c = iso.p(RW, 0.475f, 4.97f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
        }
        drawPath(serviceMark, Pal.line, style = Stroke(lw))
        drawAiModule(iso, macA.value, aiLive, aiRunT, aiOffT, lw)

        // top deck: hinged lid carrying the fans, cavity + antennas underneath
        val la = lidA.value.coerceIn(0f, 1f)
        val fy = RackLayout.TOP_FAN_Y
        val lAng = la * LID_MAX
        val li = LidIso(iso, lAng, LID_Y)
        val driveE = FloatArray(DRV_N) { driveA[it].value.coerceIn(0f, 1.1f) }
        if (la > 0.01f) drawCavity(measurer, iso, driveE, live, t, lw)
        val under = lAng > 0.78f
        val lidQ = roundedQuadPath(listOf(li.p(0.3f, LID_Y, RH), li.p(RW - 0.3f, LID_Y, RH), li.p(RW - 0.3f, RD - LID_Y, RH), li.p(0.3f, RD - LID_Y, RH)), 0.035f)
        drawPath(lidQ, if (under) Color(0xFFD9D9DF) else Color(0xFFEBEBEF))
        drawPath(lidQ, if (under) Pal.line else Pal.lineSoft, style = Stroke(if (under) 1.6.dp.toPx() else lw, join = StrokeJoin.Round))
        if (under) {
            for (ry in listOf(LID_Y, RD / 2f, RD - LID_Y)) {
                drawLine(Pal.line, li.p(0.38f, ry, RH), li.p(RW - 0.38f, ry, RH), strokeWidth = lw)
            }
        } else {
            for (k in 0..6) {
                drawLine(Color(0xFFF5F6F8), li.p(0.35f, 0.35f + k * 0.32f, RH + 0.035f), li.p(RW - 0.35f, 0.35f + k * 0.32f, RH + 0.035f), 1.dp.toPx())
            }
            val topPanel = roundedQuadPath(listOf(
                li.p(0.25f, 0.12f, RH + 0.012f), li.p(RW - 0.25f, 0.12f, RH + 0.012f),
                li.p(RW - 0.25f, RD - 0.25f, RH + 0.012f), li.p(0.25f, RD - 0.25f, RH + 0.012f)
            ), 0.04f)
            drawPath(topPanel, Color(0xFFF5F5F6))
            drawPath(topPanel, Pal.lineSoft, style = Stroke(lw))
            drawTopIo(measurer, li, topGlow, topGlow > 0.01f, lw)
        }
        drawFanTray(li, fy, RH + if (under) 0f else 0.018f, lw)
        for ((k, fx) in listOf(1.15f, RW / 2f, RW - 1.15f).withIndex()) drawFan(li, fx, fy, RH + if (under) 0f else 0.025f, 1f, fanAng[k], fan, topGlow, lw)
        if (!under) drawReceiver(measurer, li, receiverA.value, running && powerStage.includes(RackPowerStage.RECEIVER), lw)
        if (la > 0.01f) drawDrivesAbove(measurer, iso, driveE, live, t, lw)

        // bay light spill on front face
        clipPath(frontPath) {
            for (i in 0 until BAY_COUNT) {
                val a = litA[i].value * glow
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
                    listOf(Pal.orange.copy(alpha = (0.18f + 0.10f * breath + 0.25f * bump.value) * glow), Color.Transparent),
                    center = sc, radius = 2.6f * s
                ), radius = 2.6f * s, center = sc
            )
        }

        // header strip + big power button
        val hz1 = RH - RackLayout.SHELL_RADIUS - 0.04f; val hz0 = RH - 1.45f
        val hp = iso.frontQuad(BX0, hz0, BX1, hz1)
        drawPath(hp, Brush.linearGradient(listOf(lerp(Color(0xFFE9EAEC), Color(0xFFFFE0C0), glow), lerp(Color(0xFFF6F6F6), Color(0xFFFFF7F0), glow)), iso.f(BX0, RH), iso.f(BX1, hz0)))
        drawPath(hp, Pal.orange.copy(alpha = 0.82f), style = Stroke(1.25.dp.toPx()))
        val up = if (live) lit.indices.count { it < enabledBays && lit[it] } else 0
        planeText(measurer, iso, "UP  $up/$BAY_COUNT", 2.0f, RH - 0.5f, 0.32f * s, lerp(Pal.mute, Pal.orangeDeep, glow))
        planeText(measurer, iso, "LOAD", 2.0f, RH - 0.99f, 0.18f * s, Pal.mute)
        drawLine(Pal.orange.copy(alpha = 0.28f), iso.f(1.92f, RH - 1.31f), iso.f(5.25f, RH - 1.31f), lw * 0.65f)
        val segs = 10
        val fill = if (live) (up / BAY_COUNT.toFloat() * segs).roundToInt() else 0
        for (j in 0 until segs) {
            val x0 = 2.75f + j * 0.255f
            if (j < fill) {
                val pw = 0.18f * (0.82f + 0.18f * pulse(j * 0.55f))
                val seg = iso.frontQuad(x0, RH - 1.12f, x0 + pw, RH - 0.92f)
                drawPath(seg, Color(0xFFFF9442).copy(alpha = (0.68f + 0.28f * glow) * pulse(j * 0.55f)))
            } else {
                val seg = iso.frontQuad(x0, RH - 1.12f, x0 + 0.18f, RH - 0.92f)
                drawPath(seg, Color(0xFFE7D9CF))
            }
        }
        run {
            val pz = pwrZ()
            val pc = iso.f(PWR_X, pz)
            if (powerGlow > 0.01f) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Pal.orange.copy(alpha = 0.45f * powerGlow * breath), Color.Transparent),
                        center = pc, radius = 1.4f * s
                    ), radius = 1.4f * s, center = pc
                )
            }
            drawPath(iso.frontCircle(PWR_X, pz, PWR_R, 28), Color(0xFF3A3A40))
            val capR = PWR_R * 0.82f
            val cap = iso.frontCircle(PWR_X, pz, capR, 28)
            drawPath(cap, lerp(Color(0xFF66676C), Color(0xFFFF9B4A), powerGlow))
            drawPath(cap, lerp(Color(0xFF85868C), Color(0xFFFFC48D), powerGlow), style = Stroke(1.5.dp.toPx()))
            val ic = lerp(Color(0xFFB2B3B9), Color.White, powerGlow)
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
            val a = litA[i].value * glow
            val sel = i == selected
            if (d > 0.004f) {
                face(quad(iso.p(BX0, RD, z1), iso.p(BX1, RD, z1), iso.p(BX1, RD + d, z1), iso.p(BX0, RD + d, z1)), Color(0xFFF1F1F4))
                face(quad(iso.p(BX1, RD, z0), iso.p(BX1, RD + d, z0), iso.p(BX1, RD + d, z1), iso.p(BX1, RD, z1)), Pal.side)
                bayTopParts(measurer, iso, i, RD + d, z1, ((d / BAY_D - 0.55f) / 0.45f).coerceIn(0f, 1f), t, fanAng[3], fan, if (live) glow else 0f, lw)
            }
            withTransform({ translate(-d * K * s, d * 0.5f * s) }) {
                val rect = roundedQuadPath(listOf(iso.f(BX0, z0), iso.f(BX1, z0), iso.f(BX1, z1), iso.f(BX0, z1)), 0.025f)
                drawPath(rect, Brush.linearGradient(listOf(Color.White, Color(0xFFF4F4F3)), iso.f(BX0, z1), iso.f(BX1, z0)))
                drawLine(Color(0xFFFFD9B9), iso.f(BX0 + 0.018f, z0 + 0.03f), iso.f(BX0 + 0.018f, z1 - 0.03f), lw * 1.2f)
                drawLine(Color.White, iso.f(BX0 + 0.06f, z1 - 0.035f), iso.f(BX1 - 0.04f, z1 - 0.035f), lw)
                drawPath(
                    rect,
                    if (sel) Color(0xFFDBB18B) else Color(0xFFB2B3B7),
                    style = Stroke(lw * 0.95f, join = StrokeJoin.Round)
                )
                if (sel && bump.value > 0.01f) {
                    val e = 0.18f * (1f - bump.value)
                    drawPath(
                        iso.frontQuad(BX0 - e, z0 - e, BX1 + e, z1 + e),
                        Pal.orange.copy(alpha = 0.6f * bump.value), style = Stroke(1.5.dp.toPx())
                    )
                }

                drawBayNode(iso, 0.78f, zm, a, lw)

                planeText(measurer, iso, "0${i + 1} ${BAY_CODES[i]}", 1.02f, zm, 0.25f * s, if (sel) Pal.orangeDeep else Pal.ink)
                clipPath(iso.frontQuad(2.96f, z0 + 0.04f, 4.22f, z1 - 0.04f)) {
                    val value = readoutCache.fit(measurer, readouts[i], 0.22f * s, 1.1f * s, this)
                    planeText(measurer, iso, value, 3.02f, zm, 0.22f * s, if (a > 0.01f) Pal.orangeDeep else Pal.mute)
                }

                if (i == 3) {
                    for (j in 0 until 8) {
                        val h = if (a > 0.01f) (0.18f + 0.38f * abs(sin(t * 1.6f + j * 0.9f))) * a + 0.06f else 0.06f
                        val x0 = 4.25f + j * 0.15f
                        drawPath(
                            iso.frontQuad(x0, z0 + 0.08f, x0 + 0.1f, z0 + 0.08f + h),
                            if (a > 0.01f) Pal.orange.copy(alpha = 0.75f * pulse(j * 0.45f)) else Pal.lineSoft
                        )
                    }
                } else if (i == 1 || i == 2 || i == 5) {
                    for (j in 0 until 4) {
                        val on = a > 0.01f
                        val blink = if (on) 0.5f + 0.5f * sin(t * 3f + i * 1.3f + j * 1.1f) else 0f
                        drawCircle(if (on) Pal.orange.copy(alpha = 0.35f + 0.65f * blink) else Pal.lineSoft,
                            1.9.dp.toPx(), iso.f(4.4f + j * 0.28f, zm))
                    }
                } else {
                    val count = 4
                    val pitch = 0.31f
                    val iconSize = 0.25f
                    val rate = if (i == 4 && busyNow) 9f else 3f
                    for (j in 0 until count) {
                        val blink = if (a > 0.01f) 0.5f + 0.5f * sin(t * rate + i * 1.3f + j * 1.1f) else 0f
                        val color = if (a > 0.01f) Pal.orange.copy(alpha = 0.45f + 0.55f * blink) else Pal.line
                        val icon = when (i) {
                            0 -> BayIcons.lightning
                            4 -> BayIcons.chevron
                            else -> BayIcons.heart
                        }
                        val x = 4.83f + (j - (count - 1) * 0.5f) * pitch
                        drawBayIcon(iso, icon, x, zm, iconSize, color, outlined = i >= 4)
                    }
                }
            }
        }
        for (i in 0 until BAY_COUNT) if (popD[i] <= 0.004f) drawBay(i)
        for (i in BAY_COUNT - 1 downTo 0) if (popD[i] > 0.004f) drawBay(i)

        val cableLineWidth = 1.dp.toPx()
        val sway = sin(t * 1.3f) * 0.05f * (0.4f + glow)
        val pm = PathMeasure()
        for ((i, j, middle) in RackLayout.cableRuns(lit, popD)) {
            val fromZ = bayTop(i) - BAY_H / 2f
            val toZ = bayTop(j) - BAY_H / 2f
            val from = iso.p(JACK_X, RD + popD[i], fromZ)
            val to = iso.p(JACK_X, RD + popD[j], toZ)
            val yMax = RD + max(popD[i], popD[j])
            val bulge = 0.4f + 0.1f * (j - i - 1) + sway
            val c1 = iso.p(JACK_X + 0.1f, yMax + bulge, fromZ - 0.12f)
            val c2 = iso.p(JACK_X + 0.1f, yMax + bulge, toZ + 0.12f)
            val cable = Path().apply {
                moveTo(from.x, from.y); cubicTo(c1.x, c1.y, c2.x, c2.y, to.x, to.y)
            }
            val style = if (middle != null) CableStyle.CURVED else
                RackLayout.cableStyle(i, j, popD[i] > 0.004f, popD[j] > 0.004f)
            val run = if (middle != null) {
                val midpoint = iso.p(JACK_X, RD + popD[middle], bayTop(middle) - BAY_H / 2f)
                val (controlX1, controlX2) = RackLayout.arcCubicControls(from.x, midpoint.x, to.x)
                val (controlY1, controlY2) = RackLayout.arcCubicControls(from.y, midpoint.y, to.y)
                Path().apply {
                    moveTo(from.x, from.y)
                    cubicTo(controlX1, controlY1, controlX2, controlY2, to.x, to.y)
                }
            } else if (style == CableStyle.STRAIGHT) {
                Path().apply { moveTo(from.x, from.y); lineTo(to.x, to.y) }
            } else {
                cable
            }
            val on = min(min(litA[i].value, litA[j].value), middle?.let { litA[it].value } ?: 1f) * glow
            if (on <= 0.001f) continue
            drawPath(run, Pal.line.copy(alpha = on), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            drawPath(run, Pal.orange.copy(alpha = on), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            pm.setPath(run, false)
            val dotCount = RackLayout.flowDotCount(style) * if (middle != null) 2 else 1
            for (k in 0 until dotCount) {
                val u = (flow * (1f + i * 0.07f) + k / dotCount.toFloat()) % 1f
                val position = pm.getPosition(u * pm.length)
                drawCircle(Color.White.copy(alpha = 0.9f * on), 2.2.dp.toPx(), position)
                drawCircle(Pal.orangeDeep.copy(alpha = 0.9f * on), 2.2.dp.toPx(), position, style = Stroke(cableLineWidth))
            }
        }
        for (i in 0 until BAY_COUNT) {
            val center = iso.p(JACK_X, RD + popD[i], bayTop(i) - BAY_H / 2f)
            val on = litA[i].value * glow
            drawCircle(Color.White, 3.6.dp.toPx(), center)
            drawCircle(lerp(Pal.line, Pal.orangeDeep, on), 3.6.dp.toPx(), center, style = Stroke(1.4.dp.toPx()))
            drawCircle(lerp(Pal.mute, Pal.orange, on), 1.3.dp.toPx(), center)
        }
        }
    }
}
