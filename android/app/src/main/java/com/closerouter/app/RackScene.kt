package com.closerouter.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import kotlin.math.cos
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

private fun bayTop(i: Int) = RH - 1.75f - i * 0.72f
private const val PWR_X = 1.2f
private const val PWR_R = 0.55f
private const val JACK_X = 5.8f
private fun pwrZ() = RH - 0.875f

private class Iso(val s: Float, val cx: Float, val cy: Float) {
    fun p(x: Float, y: Float, z: Float) = Offset(cx + (x - y) * K * s, cy + (x + y) * 0.5f * s - z * s)
    fun f(x: Float, z: Float) = p(x, RD, z)
}

private fun isoFor(w: Float, h: Float): Iso {
    val spanH = RH + (RW + RD + 1.6f) / 2f
    val s = min(w / 10.2f, h / (spanH + 0.3f))
    val xMin = (-0.8f - (RD + 0.8f)) * K
    val xMax = (RW + 1.6f) * K
    val cx = w / 2f - (xMin + xMax) / 2f * s
    val cy = (h - spanH * s) / 2f + RH * s
    return Iso(s, cx, cy)
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
    fs: Float, color: Color, bold: Boolean = false
) {
    val origin = iso.f(x, zMid + (fs * 1.2f / 2f) / iso.s)
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
    'B' to listOf("11110","10001","10001","11110","10001","10001","11110"),
)

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
    val bump = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        bump.snapTo(1f)
        bump.animateTo(0f, tween(650))
    }

    var t by remember { mutableFloatStateOf(0f) }
    var spin by remember { mutableFloatStateOf(0f) }
    var flow by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                if (last != 0L) {
                    val dt = ((n - last) / 1e9f).coerceAtMost(0.05f)
                    t += dt
                    spin += dt * 600f * spinSpeed.value
                    flow += dt * (0.22f + 0.55f * glowOn.value)
                }
                last = n
            }
        }
    }

    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { o ->
                val iso = isoFor(size.width.toFloat(), size.height.toFloat())
                val x = (o.x - iso.cx) / (K * iso.s) + RD
                val z = ((x + RD) * 0.5f * iso.s + iso.cy - o.y) / iso.s
                val dx = x - PWR_X; val dz = z - pwrZ()
                if (dx * dx + dz * dz <= (PWR_R + 0.12f) * (PWR_R + 0.12f)) { onPowerNow(); return@detectTapGestures }
                if (x in (BX0 - 0.15f)..(BX1 + 0.15f)) {
                    for (i in 0 until BAY_COUNT) {
                        val z1 = bayTop(i)
                        if (z in (z1 - BAY_H - 0.03f)..(z1 + 0.03f)) { onSelectNow(i); break }
                    }
                }
            }
        }
    ) {
        val iso = isoFor(size.width, size.height)
        val s = iso.s
        val lw = 1.dp.toPx()
        val glow = glowOn.value
        val breath = 0.75f + 0.25f * sin(t * 2.2f)
        val fan = spinSpeed.value

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
            Pal.orange.copy(alpha = 0.7f * glow), iso.p(0.4f, RD + 0.45f, bz), iso.p(RW - 0.4f, RD + 0.45f, bz),
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
            val shadow = Path().apply { addPath(cable, Offset(0f, 3.dp.toPx())) }
            drawPath(shadow, Color.Black.copy(alpha = 0.10f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(cable, Pal.line, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(cable, Color(0xFFE4E4E9), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(cable, Pal.orange.copy(alpha = 0.25f + 0.75f * glow), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (glow > 0.02f) {
                val pm2 = PathMeasure()
                pm2.setPath(cable, false)
                val len = pm2.length
                for (k in 0 until 9) {
                    val u = (flow * 1.4f + k / 9f) % 1f
                    val pos = pm2.getPosition(u * len)
                    drawCircle(Pal.orange.copy(alpha = 0.30f * glow), 5.dp.toPx(), pos)
                    drawCircle(Color.White.copy(alpha = glow), 2.6.dp.toPx(), pos)
                    drawCircle(Pal.orangeDeep.copy(alpha = glow), 2.6.dp.toPx(), pos, style = Stroke(lw))
                }
            }
        }

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
                val litV = if (running) 1f else 0.55f
                val digits = iso.pixelPath(value, py1 - 0.1f, zT - 0.5f, cell)
                drawPath(digits, Pal.orange.copy(alpha = litV))
                if (running) drawPath(digits, Pal.orange.copy(alpha = 0.25f * breath), style = Stroke(2.5.dp.toPx()))
                val led = iso.p(RW, py0 + 0.14f, zT - 0.2f)
                drawCircle(if (running && (busyNow || sin(t * 3f) > 0f)) Pal.orange else Color(0xFF55555B), 2.dp.toPx(), led)
            }

            val zT = 6.55f; val zB = 5.45f
            val panel = quad(iso.p(RW, py0, zT), iso.p(RW, py1, zT), iso.p(RW, py1, zB), iso.p(RW, py0, zB))
            drawPath(panel, Color(0xFF2A2A2F))
            drawPath(panel, Pal.line, style = Stroke(lw))
            drawPath(iso.pixelPath("SIG", py1 - 0.1f, zT - 0.12f, 0.03f), Pal.orange.copy(alpha = 0.55f))
            val sled = iso.p(RW, py0 + 0.14f, zT - 0.2f)
            drawCircle(if (running && sin(t * 4f) > 0f) Pal.orange else Color(0xFF55555B), 2.dp.toPx(), sled)
            val zc = zB + 0.38f
            for (g in 0..8) {
                val yy = py0 + 0.15f + g * 0.2f
                drawCircle(Color(0xFF45454C), 0.8.dp.toPx(), iso.p(RW, yy, zc))
            }
            val amp = if (running) (if (busyNow) 0.3f else 0.18f) else 0.04f
            val wave = Path()
            val n = 36
            for (k in 0..n) {
                val u = k / n.toFloat()
                val yy = py1 - 0.12f - u * (py1 - py0 - 0.24f)
                val v = sin(u * 14f - t * 5f) * 0.6f + sin(u * 31f - t * 8f) * 0.4f
                val o = iso.p(RW, yy, zc + amp * v)
                if (k == 0) wave.moveTo(o.x, o.y) else wave.lineTo(o.x, o.y)
            }
            if (running) drawPath(wave, Pal.orange.copy(alpha = 0.25f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            drawPath(wave, Pal.orange.copy(alpha = if (running) 1f else 0.5f), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
            val sweep = (t * 0.5f) % 1f
            for (k in 0 until 12) {
                val yy = py1 - 0.12f - k * 0.14f
                val on = running && ((sweep * 12f).toInt() == k)
                drawCircle(if (on) Pal.orange else Color(0xFF45454C), 1.3.dp.toPx(), iso.p(RW, yy, zB + 0.09f))
            }

            var y = py0 + 0.1f
            while (y < py1 - 0.05f) {
                drawLine(Pal.lineSoft, iso.p(RW, y, 0.45f), iso.p(RW, y, 0.8f), strokeWidth = lw)
                y += 0.1f
            }
        }

        // top deck + fans
        val deck = quad(iso.p(0.3f, 0.3f, RH), iso.p(RW - 0.3f, 0.3f, RH), iso.p(RW - 0.3f, RD - 0.3f, RH), iso.p(0.3f, RD - 0.3f, RH))
        drawPath(deck, Color(0xFFEBEBEF))
        drawPath(deck, Pal.lineSoft, style = Stroke(lw))
        val fy = RD / 2f
        for ((k, fx) in listOf(1.15f, RW / 2f, RW - 1.15f).withIndex()) {
            val outer = iso.floorCircle(fx, fy, RH, 0.807f)
            drawPath(outer, Pal.front)
            drawPath(outer, Pal.line, style = Stroke(lw))
            val well = iso.floorCircle(fx, fy, RH, 0.714f)
            drawPath(well, Color(0xFFDCDCE1))
            drawPath(well, Pal.lineSoft, style = Stroke(lw))
            if (fan > 0.02f) drawPath(iso.floorCircle(fx, fy, RH, 0.68f), Pal.orange.copy(alpha = 0.12f * fan * glow))
            val bladeColor = lerp(Color(0xFFB9B9C1), Pal.orange, fan)
            for (j in 0 until 5) {
                val a = Math.toRadians(spin + j * 72.0 + k * 20.0).toFloat()
                fun pt(r: Float, da: Float) = iso.p(fx + r * cos(a + da), fy + r * sin(a + da), RH)
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
            drawPath(iso.floorCircle(fx, fy, RH, 0.425f), Pal.line.copy(alpha = 0.6f), style = Stroke(lw))
            for (j in 0 until 4) {
                val a = (PI / 2 * j + PI / 4).toFloat()
                drawLine(Pal.line.copy(alpha = 0.7f), iso.p(fx + 0.085f * cos(a), fy + 0.085f * sin(a), RH),
                    iso.p(fx + 0.714f * cos(a), fy + 0.714f * sin(a), RH), strokeWidth = lw)
            }
            val hub = iso.floorCircle(fx, fy, RH, 0.111f, 14)
            drawPath(hub, Pal.front)
            drawPath(hub, Pal.line, style = Stroke(lw))
            drawPath(iso.floorCircle(fx, fy, RH, 0.043f, 10), lerp(Pal.mute, Pal.orange, fan))
        }

        // bay light spill on front face
        clipPath(frontPath) {
            for (i in 0 until BAY_COUNT) {
                val a = litA[i].value
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
            val seg = iso.frontQuad(x0, RH - 1.25f, x0 + 0.19f, RH - 0.95f)
            if (j < fill) drawPath(seg, Pal.orange.copy(alpha = 0.55f + 0.4f * glow))
            else drawPath(seg, Pal.orange.copy(alpha = 0.18f))
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
        for (i in 0 until BAY_COUNT) {
            val z1 = bayTop(i); val z0 = z1 - BAY_H
            val zm = (z0 + z1) / 2f
            val a = litA[i].value
            val sel = i == selected
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

            // status LED
            val ledC = iso.f(0.78f, zm)
            if (a > 0.01f) drawCircle(Pal.orange.copy(alpha = 0.3f * a), 6.dp.toPx(), ledC)
            drawCircle(lerp(Color(0xFFD0D0D6), Pal.orange, a), 3.dp.toPx(), ledC)
            drawCircle(lerp(Pal.line, Pal.orangeDeep, a), 3.dp.toPx(), ledC, style = Stroke(lw))

            planeText(measurer, iso, "0${i + 1} ${BAY_CODES[i]}", 1.05f, zm, 0.26f * s, if (sel) Pal.orangeDeep else Pal.ink, bold = true)
            planeText(measurer, iso, readouts[i], 3.05f, zm, 0.23f * s, if (lit[i]) Pal.orangeDeep else Pal.mute)

            // decoration
            if (i == 3) {
                for (j in 0 until 8) {
                    val h = if (a > 0.01f) (0.18f + 0.38f * abs(sin(t * 1.6f + j * 0.9f))) * a + 0.06f else 0.06f
                    val x0 = 4.25f + j * 0.15f
                    drawPath(
                        iso.frontQuad(x0, z0 + 0.08f, x0 + 0.1f, z0 + 0.08f + h),
                        if (a > 0.01f) Pal.orange.copy(alpha = 0.75f) else Pal.lineSoft
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

        // cables: lower jack of a lit bay -> upper jack of the next lit bay
        val sway = sin(t * 1.3f) * 0.05f * (0.4f + glow)
        val pm = PathMeasure()
        val outOn = FloatArray(BAY_COUNT)
        val inOn = FloatArray(BAY_COUNT)
        for (i in 0 until BAY_COUNT) {
            val nj = if (lit[i]) (i + 1 until BAY_COUNT).firstOrNull { lit[it] } else null
            if (nj != null) {
                val v = min(litA[i].value, litA[nj].value)
                outOn[i] = v; inOn[nj] = v
            }
        }
        for (i in 0 until BAY_COUNT) {
            val zm = bayTop(i) - BAY_H / 2f
            val jUp = iso.f(JACK_X, zm + 0.14f)
            val jDn = iso.f(JACK_X, zm - 0.14f)
            val j = if (lit[i]) (i + 1 until BAY_COUNT).firstOrNull { lit[it] } else null
            if (j != null) {
                val zn = bayTop(j) - BAY_H / 2f
                val a1 = iso.f(JACK_X, zn + 0.14f)
                val bulge = 0.4f + 0.1f * (j - i - 1) + sway
                val c1 = iso.p(JACK_X + 0.1f, RD + bulge, zm - 0.16f)
                val c2 = iso.p(JACK_X + 0.1f, RD + bulge, zn + 0.16f)
                val cable = Path().apply {
                    moveTo(jDn.x, jDn.y); cubicTo(c1.x, c1.y, c2.x, c2.y, a1.x, a1.y)
                }
                val on = min(litA[i].value, litA[j].value)
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
