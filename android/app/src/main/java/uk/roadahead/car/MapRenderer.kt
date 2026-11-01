package uk.roadahead.car

import android.graphics.*
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import uk.roadahead.core.*
import uk.roadahead.service.DriveMode
import uk.roadahead.service.NavState
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the car screen: a heading-up "road ahead" view of your route with
 * hazard zones, gantry signs, cameras and reports, plus a look-ahead card and
 * the current gantry speed limit. (Vector only — no map tiles.)
 */
class MapRenderer(private val ctx: CarContext) : SurfaceCallback {
    private var surface: SurfaceContainer? = null
    private var visible = Rect()
    private var state: NavState? = null
    private val zooms = doubleArrayOf(4.0, 7.0, 12.0, 20.0, 35.0)
    private var zoomIdx = 2

    private val bg = Paint().apply { color = 0xFF0B1D2E.toInt() }
    private val routeCasing = stroke(0xFF0B5394.toInt(), 30f)
    private val routeAhead = stroke(0xFF1E88E5.toInt(), 20f)
    private val routeBehind = stroke(0xFF546E7A.toInt(), 16f)
    private val hzPaint = stroke(Color.RED, 22f)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0BEC5.toInt(); textSize = 22f }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun stroke(c: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; strokeWidth = w; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun sevColor(s: Severity) = when (s) {
        Severity.SEVERE -> 0xFFD32F2F.toInt(); Severity.HIGH -> 0xFFEF6C00.toInt(); Severity.MEDIUM -> 0xFFF9A825.toInt(); else -> 0xFF78909C.toInt()
    }

    fun update(s: NavState) { state = s; draw() }
    fun zoom(d: Int) { zoomIdx = (zoomIdx + d).coerceIn(0, zooms.size - 1); draw() }

    override fun onSurfaceAvailable(c: SurfaceContainer) { surface = c; draw() }
    override fun onSurfaceDestroyed(c: SurfaceContainer) { surface = null }
    override fun onVisibleAreaChanged(r: Rect) { visible = r; draw() }
    override fun onStableAreaChanged(r: Rect) { if (visible.isEmpty) visible = r; draw() }
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) { if (scaleFactor > 1.1f) zoom(-1) else if (scaleFactor < 0.9f) zoom(1) }

    @Synchronized
    private fun draw() {
        val sc = surface ?: return
        val sf = sc.surface ?: return
        if (!sf.isValid) return
        val canvas = runCatching { sf.lockCanvas(null) }.getOrNull() ?: return
        try { render(canvas, sc.width, sc.height) } finally { sf.unlockCanvasAndPost(canvas) }
    }

    private fun render(c: Canvas, w: Int, h: Int) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val v = if (visible.isEmpty) Rect(0, 0, w, h) else visible
        val s = state ?: return
        val route = s.active
        val centre = s.position ?: route?.line?.pointAt(s.along) ?: run {
            c.drawText("Waiting for GPS…", v.left + 40f, v.centerY().toFloat(), text); return
        }
        val heading = Math.toRadians(if (s.mode != DriveMode.NONE) (s.heading ?: 0.0) else 0.0)
        val ax = v.exactCenterX(); val ay = v.top + v.height() * (if (s.mode != DriveMode.NONE) 0.78f else 0.5f)
        val mpp = zooms[zoomIdx]
        val kx = cos(Math.toRadians(centre.lat)) * 111320.0; val ky = 110540.0
        fun proj(p: Pt): PointF {
            val dx = (p.lon - centre.lon) * kx; val dy = (p.lat - centre.lat) * ky
            val right = dx * cos(heading) - dy * sin(heading)
            val fwd = dx * sin(heading) + dy * cos(heading)
            return PointF((ax + right / mpp).toFloat(), (ay - fwd / mpp).toFloat())
        }
        val range = mpp * (w + h) * 1.2

        // Subtle grid for a sense of motion.
        val grid = stroke(0x22FFFFFF, 1f)
        val step = 500.0 / mpp
        var gy = (ay % step).toFloat(); while (gy < h) { c.drawLine(0f, gy, w.toFloat(), gy, grid); gy += step.toFloat() }

        if (route != null) {
            val line = route.line
            val from = (s.along - range).coerceAtLeast(0.0); val to = (s.along + range).coerceAtMost(line.length)
            fun pathOf(pts: List<Pt>): Path = Path().apply { pts.forEachIndexed { i, p -> val q = proj(p); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) } }
            c.drawPath(pathOf(line.slice(from, s.along)), routeBehind)
            val ahead = pathOf(line.slice(s.along, to))
            c.drawPath(ahead, routeCasing); c.drawPath(ahead, routeAhead)

            val items = route.analysis?.items.orEmpty()
            for (it in items) {
                if (it.end < from || it.start > to) continue
                if (it.kind == ItemKind.ZONE || (it.kind == ItemKind.EVENT && it.end - it.start > 100)) {
                    hzPaint.color = sevColor(it.severity); hzPaint.alpha = 220
                    c.drawPath(pathOf(line.slice(maxOf(it.start, from), minOf(maxOf(it.end, it.start + 100), to))), hzPaint)
                }
            }
            for (g in route.analysis?.gantries.orEmpty()) {
                if (g.along < s.along - 200 || g.along > to) continue
                if (g.signal?.all { it.aspect == Aspect.BLANK } != false) continue
                val q = proj(g.location)
                drawGantry(c, g.signal, q.x + 24, q.y - 20, 30f)
            }
            for (it in items) {
                if (it.start < s.along - 100 || it.start > to || it.kind == ItemKind.ZONE || it.kind == ItemKind.VMS) continue
                val q = proj(it.location)
                fill.color = Color.WHITE; c.drawCircle(q.x, q.y, 28f, fill)
                fill.color = sevColor(it.severity); fill.style = Paint.Style.STROKE; fill.strokeWidth = 5f; c.drawCircle(q.x, q.y, 28f, fill); fill.style = Paint.Style.FILL
                c.drawText(it.type.icon, q.x, q.y + 14, emoji)
            }
            // Destination flag.
            if (line.length - s.along < range) { val q = proj(line.coords.last()); c.drawText("🏁", q.x, q.y, emoji) }
        }

        // Car.
        val car = Path().apply { moveTo(ax, ay - 30); lineTo(ax + 20, ay + 22); lineTo(ax, ay + 10); lineTo(ax - 20, ay + 22); close() }
        fill.color = Color.WHITE; c.drawCircle(ax, ay, 34f, fill)
        fill.color = 0xFF1E88E5.toInt(); c.drawPath(car, fill)

        if (route != null) drawLookAhead(c, v, s, route)
        s.limit?.let { drawLimit(c, v.left + 70f, v.bottom - 80f, it) }
    }

    private fun drawLookAhead(c: Canvas, v: Rect, s: NavState, route: Route) {
        val next = route.analysis?.items?.firstOrNull { (it.kind == ItemKind.ZONE || it.kind == ItemKind.EVENT || it.kind == ItemKind.CAMERA) && it.end > s.along - 20 && (it.kind != ItemKind.CAMERA || it.start - s.along < 3000) } ?: return
        val d = next.start - s.along
        val cardW = minOf(520f, v.width() - 40f); val left = v.right - cardW - 20; val top = v.top + 20f
        val gantry = next.gantries.firstOrNull { it.lanesClosed.isNotEmpty() && it.along > s.along } ?: next.gantries.filter { it.along > s.along }.minByOrNull { it.speedLimit ?: 99 }
        val height = if (gantry != null) 190f else 130f
        fill.color = 0xEE1B2733.toInt(); c.drawRoundRect(left, top, left + cardW, top + height, 18f, 18f, fill)
        fill.color = sevColor(next.severity); c.drawRoundRect(left, top, left + 12, top + height, 6f, 6f, fill)
        val big = Paint(text).apply { textSize = 38f; isFakeBoldText = true }
        c.drawText(if (d > 0) fmtMiles(d) else "NOW", left + 30, top + 48, big)
        c.drawText(next.type.icon, left + cardW - 40, top + 50, emoji)
        val hl = TextUtilsShim.ellipsize(next.headline, text, cardW - 50)
        c.drawText(hl, left + 30, top + 88, text)
        val sub = listOfNotNull(if (next.delayMinutes > 0) "+${Math.round(next.delayMinutes)} min" else null,
            next.exitsBefore.firstOrNull { it.along > s.along + 100 }?.let { "Exit ${it.ref ?: ""} in ${fmtMiles(it.along - s.along)}" }).joinToString(" · ")
        if (sub.isNotEmpty()) c.drawText(TextUtilsShim.ellipsize(sub, small, cardW - 50), left + 30, top + 118, small)
        if (gantry != null) drawGantry(c, gantry.signal, left + 30, top + 132, 44f)
    }

    private fun drawGantry(c: Canvas, lanes: List<SignalLane>?, x: Float, y: Float, cell: Float) {
        val ls = lanes?.sortedBy { it.lane } ?: return
        val pad = cell * 0.12f
        fill.color = 0xFF111111.toInt(); c.drawRect(x, y, x + ls.size * (cell + pad) + pad, y + cell + 2 * pad, fill)
        ls.forEachIndexed { i, l ->
            val cx = x + pad + i * (cell + pad) + cell / 2; val cy = y + pad + cell / 2
            fill.color = Color.BLACK; c.drawRect(cx - cell / 2, cy - cell / 2, cx + cell / 2, cy + cell / 2, fill)
            when (l.aspect) {
                Aspect.SPEED -> {
                    fill.color = Color.WHITE; c.drawCircle(cx, cy, cell * 0.44f, fill)
                    val ring = stroke(0xFFE53935.toInt(), cell * 0.1f); c.drawCircle(cx, cy, cell * 0.39f, ring)
                    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = cell * 0.36f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
                    c.drawText("${l.speed ?: ""}", cx, cy + cell * 0.13f, t)
                }
                Aspect.CLOSED -> { val p = stroke(0xFFFF1744.toInt(), cell * 0.12f); val r = cell * 0.32f
                    c.drawLine(cx - r, cy - r, cx + r, cy + r, p); c.drawLine(cx + r, cy - r, cx - r, cy + r, p) }
                Aspect.MERGE_LEFT, Aspect.MERGE_RIGHT -> {
                    val p = stroke(Color.WHITE, cell * 0.1f); val r = cell * 0.3f; val dir = if (l.aspect == Aspect.MERGE_LEFT) -1 else 1
                    c.drawLine(cx - dir * r, cy - r, cx + dir * r, cy + r, p)
                    c.drawLine(cx + dir * r, cy + r, cx + dir * r, cy, p); c.drawLine(cx + dir * r, cy + r, cx, cy + r, p)
                }
                Aspect.END -> { val p = stroke(Color.WHITE, cell * 0.06f); c.drawCircle(cx, cy, cell * 0.36f, p)
                    c.drawLine(cx - cell * 0.25f, cy + cell * 0.25f, cx + cell * 0.25f, cy - cell * 0.25f, stroke(Color.WHITE, cell * 0.1f)) }
                Aspect.BLANK -> {}
            }
        }
    }

    private fun drawLimit(c: Canvas, x: Float, y: Float, mph: Int) {
        fill.color = Color.WHITE; c.drawCircle(x, y, 50f, fill)
        c.drawCircle(x, y, 44f, stroke(0xFFE53935.toInt(), 10f))
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        c.drawText("$mph", x, y + 13, t)
    }
}

object TextUtilsShim {
    fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var e = s
        while (e.isNotEmpty() && p.measureText("$e…") > maxW) e = e.dropLast(1)
        return "$e…"
    }
}
