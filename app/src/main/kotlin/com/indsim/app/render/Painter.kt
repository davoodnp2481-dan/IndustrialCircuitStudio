package com.indsim.app.render

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.indsim.canvas.Camera
import com.indsim.canvas.Grid
import com.indsim.canvas.WorldBounds
import com.indsim.editor.Document
import com.indsim.editor.Geo
import com.indsim.editor.Kind
import com.indsim.editor.NetColors
import com.indsim.editor.Placed
import com.indsim.editor.Registry
import com.indsim.engine.Circuit
import com.indsim.engine.Component
import com.indsim.engine.Simulator
import com.indsim.engine.components.Coil
import com.indsim.engine.components.DualPushButton
import com.indsim.engine.components.Lamp
import com.indsim.engine.components.LinkedContact
import com.indsim.engine.components.Motor1Ph
import com.indsim.engine.components.Motor3Ph
import com.indsim.engine.components.PowerPoles
import com.indsim.engine.components.ProtectiveDevice
import com.indsim.engine.components.PushButton
import com.indsim.engine.components.Selector
import com.indsim.engine.components.Source
import com.indsim.engine.components.ThermalRelay
import com.indsim.engine.components.TimerMode
import com.indsim.engine.components.TimerRelay
import com.indsim.engine.components.ToggleSwitch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

val WORLD = WorldBounds(5000.0, 3500.0)

/** هرچه برای رسم یک فریم لازم است؛ فقط خواندنی. */
class RenderState(
    val doc: Document,
    val camera: Camera,
    val real: Boolean,
    val circuit: Circuit? = null,
    val sim: Simulator? = null,
    val spins: Map<String, Float> = emptyMap(),
    val wireStates: IntArray? = null,
    val selection: Set<String> = emptySet(),
    val selectedWire: Int? = null,
    val showGrid: Boolean = true,
    val wireTool: Boolean = false,
    val wireStart: String? = null,
    val rubber: Offset? = null,
    val snapPin: String? = null,
    val inspectPin: String? = null,
    val inspectId: String? = null,
    val background: Color = Pal.bg,
)

object Pal {
    val bg = Color(0xFFF4F6F8)
    val gridMinor = Color(0xFFE4E8EC)
    val gridMajor = Color(0xFFCFD6DD)
    val border = Color(0xFFE0A100)
    val ink = Color(0xFF263238)
    val inkSoft = Color(0xFF78909C)
    val paper = Color(0xFFFFFFFF)
    val hot = Color(0xFFFFB300)
    val flow = Color(0xFF00B8D4)
    val accent = Color(0xFF1976D2)
    val good = Color(0xFF2E7D32)
    val bad = Color(0xFFC62828)
    val copper = Color(0xFFB87333)
}

/** رسم نقشه با دو نمایش: نماد استاندارد (IEC) و ظاهر نزدیک به تجهیز واقعی. */
class Painter(private val ds: DrawScope, private val st: RenderState) {
    private val cam = st.camera
    private val real = st.real
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val comps: Map<String, Component> = st.circuit?.components?.associateBy { it.id } ?: emptyMap()
    private val unit = (cam.scale * Grid.BASE_STEP).toFloat()

    /** چارچوب محلی یک قطعه: مختصات شبکه‌ی محلی (قبل از چرخش) به پیکسل صفحه. */
    private class Fr(val ox: Int, val oy: Int, val rot: Int, val cam: Camera) {
        val s: Float = (cam.scale * Grid.BASE_STEP).toFloat()
        fun p(lx: Double, ly: Double): Offset {
            val rx: Double
            val ry: Double
            when (((rot % 360) + 360) % 360) {
                0 -> { rx = lx; ry = ly }
                90 -> { rx = -ly; ry = lx }
                180 -> { rx = -lx; ry = -ly }
                else -> { rx = ly; ry = -lx }
            }
            return Offset(
                cam.toScreenX((ox + rx) * Grid.BASE_STEP).toFloat(),
                cam.toScreenY((oy + ry) * Grid.BASE_STEP).toFloat(),
            )
        }
    }

    // ---------------- ابزارهای رسم ----------------

    private fun line(a: Offset, b: Offset, c: Color, w: Float, dash: Boolean = false) {
        ds.drawLine(
            color = c, start = a, end = b, strokeWidth = w, cap = StrokeCap.Round,
            pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(w * 2.5f, w * 2f)) else null,
        )
    }

    private fun circle(center: Offset, r: Float, c: Color, fill: Boolean = true, w: Float = 2f) {
        if (fill) ds.drawCircle(color = c, radius = r, center = center)
        else ds.drawCircle(color = c, radius = r, center = center, style = Stroke(width = w))
    }

    private fun path(pts: List<Offset>): Path {
        val p = Path()
        pts.forEachIndexed { i, o -> if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
        p.close()
        return p
    }

    private fun quad(f: Fr, x0: Double, y0: Double, x1: Double, y1: Double) =
        listOf(f.p(x0, y0), f.p(x1, y0), f.p(x1, y1), f.p(x0, y1))

    private fun box(f: Fr, x0: Double, y0: Double, x1: Double, y1: Double, top: Color, bottom: Color, border: Color, w: Float) {
        val pts = quad(f, x0, y0, x1, y1)
        val pa = path(pts)
        val ymin = pts.minOf { it.y }
        val ymax = pts.maxOf { it.y }
        ds.drawPath(pa, brush = Brush.verticalGradient(listOf(top, bottom), startY = ymin, endY = max(ymax, ymin + 1f)))
        ds.drawPath(pa, color = border, style = Stroke(width = w))
    }

    private fun rectFill(f: Fr, x0: Double, y0: Double, x1: Double, y1: Double, c: Color) {
        ds.drawPath(path(quad(f, x0, y0, x1, y1)), color = c)
    }

    private fun rectLine(f: Fr, x0: Double, y0: Double, x1: Double, y1: Double, c: Color, w: Float) {
        ds.drawPath(path(quad(f, x0, y0, x1, y1)), color = c, style = Stroke(width = w))
    }

    private fun text(s: String, p: Offset, size: Float, c: Color, bold: Boolean = false) {
        paint.color = c.toArgb()
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        ds.drawContext.canvas.nativeCanvas.drawText(s, p.x, p.y, paint)
    }

    private fun lw(f: Fr) = max(1.6f, f.s * 0.11f)

    // ---------------- نقاط ورود ----------------

    fun draw() {
        drawBackground()
        drawWires()
        for (p in st.doc.placed) drawComponent(p)
        drawOverlay()
    }

    private fun drawBackground() {
        ds.drawRect(st.background)
        val vw = ds.size.width.toDouble()
        val vh = ds.size.height.toDouble()
        val x0 = cam.toScreenX(0.0).toFloat()
        val y0 = cam.toScreenY(0.0).toFloat()
        val x1 = cam.toScreenX(WORLD.width).toFloat()
        val y1 = cam.toScreenY(WORLD.height).toFloat()
        if (st.showGrid) {
            val step = Grid.adaptiveStep(cam.scale)
            val view = cam.visibleWorld(vw, vh)
            val l = view.left.coerceAtLeast(0.0); val t = view.top.coerceAtLeast(0.0)
            val r = view.right.coerceAtMost(WORLD.width); val b = view.bottom.coerceAtMost(WORLD.height)
            for (i in Grid.lines(l, r, step)) {
                val sx = cam.toScreenX(i * step).toFloat()
                ds.drawLine(if (i % 5L == 0L) Pal.gridMajor else Pal.gridMinor, Offset(sx, y0), Offset(sx, y1), strokeWidth = 1f)
            }
            for (j in Grid.lines(t, b, step)) {
                val sy = cam.toScreenY(j * step).toFloat()
                ds.drawLine(if (j % 5L == 0L) Pal.gridMajor else Pal.gridMinor, Offset(x0, sy), Offset(x1, sy), strokeWidth = 1f)
            }
            ds.drawRect(Pal.border, Offset(x0, y0), Size(x1 - x0, y1 - y0), style = Stroke(width = 3f))
        }
    }

    private fun gp(g: com.indsim.editor.GridPt) = Offset(
        cam.toScreenX(g.x * Grid.BASE_STEP).toFloat(), cam.toScreenY(g.y * Grid.BASE_STEP).toFloat(),
    )

    private fun drawWires() {
        val colors = NetColors.compute(st.doc)
        val w = max(2f, unit * 0.12f)
        st.doc.wires.forEachIndexed { i, wire ->
            val path = Geo.wirePath(st.doc, wire) ?: return@forEachIndexed
            val pts = path.map { gp(it) }
            val col = Color(colors[i])
            val state = st.wireStates?.getOrNull(i) ?: 0
            if (state != 0) {
                val halo = (if (state == 1) Pal.hot else Pal.flow).copy(alpha = 0.55f)
                for (k in 0 until pts.size - 1) line(pts[k], pts[k + 1], halo, w * 2.8f)
            }
            if (st.selectedWire == i) for (k in 0 until pts.size - 1) line(pts[k], pts[k + 1], Pal.accent.copy(alpha = 0.5f), w * 3.2f)
            for (k in 0 until pts.size - 1) line(pts[k], pts[k + 1], col, w)
            if (colors[i] == NetColors.GREEN_YELLOW) {
                for (k in 0 until pts.size - 1) line(pts[k], pts[k + 1], Color(0xFFFFD600), w * 0.6f, dash = true)
            }
        }
    }

    private fun drawOverlay() {
        val sel = st.doc.placed.filter { it.id in st.selection }
        for (p in sel) {
            val b = Geo.bounds(p)
            val tl = Offset(cam.toScreenX(b.minX * Grid.BASE_STEP.toDouble()).toFloat(), cam.toScreenY(b.minY * Grid.BASE_STEP.toDouble()).toFloat())
            val br = Offset(cam.toScreenX(b.maxX * Grid.BASE_STEP.toDouble()).toFloat(), cam.toScreenY(b.maxY * Grid.BASE_STEP.toDouble()).toFloat())
            ds.drawRect(
                Pal.accent, tl, Size(br.x - tl.x, br.y - tl.y),
                style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))),
            )
        }
        if (st.wireTool && unit >= 6f) {
            for ((_, g) in Geo.allPins(st.doc)) circle(gp(g), unit * 0.42f, Pal.accent.copy(alpha = 0.35f), fill = false, w = 2f)
        }
        st.wireStart?.let { id -> Geo.pinPos(st.doc, id)?.let { circle(gp(it), unit * 0.55f, Pal.hot, fill = false, w = 4f) } }
        if (st.wireStart != null && st.rubber != null) {
            Geo.pinPos(st.doc, st.wireStart)?.let { a ->
                val b = Offset(cam.toScreenX(st.rubber.x * Grid.BASE_STEP.toDouble()).toFloat(), cam.toScreenY(st.rubber.y * Grid.BASE_STEP.toDouble()).toFloat())
                val route = com.indsim.editor.Router.route(a, com.indsim.editor.GridPt(Math.round(st.rubber.x).toInt(), Math.round(st.rubber.y).toInt()))
                val pts = route.map { gp(it) }
                for (k in 0 until pts.size - 1) line(pts[k], pts[k + 1], Pal.hot, 4f, dash = true)
                circle(b, 6f, Pal.hot)
            }
        }
        st.snapPin?.let { id -> Geo.pinPos(st.doc, id)?.let { circle(gp(it), unit * 0.6f, Pal.good, fill = false, w = 5f) } }
        st.inspectPin?.let { id -> Geo.pinPos(st.doc, id)?.let { circle(gp(it), unit * 0.6f, Color(0xFFD81B60), fill = false, w = 5f) } }
        st.inspectId?.let { id ->
            st.doc.find(id)?.let { p ->
                val b = Geo.bounds(p)
                val tl = Offset(cam.toScreenX(b.minX * Grid.BASE_STEP.toDouble()).toFloat(), cam.toScreenY(b.minY * Grid.BASE_STEP.toDouble()).toFloat())
                val br = Offset(cam.toScreenX(b.maxX * Grid.BASE_STEP.toDouble()).toFloat(), cam.toScreenY(b.maxY * Grid.BASE_STEP.toDouble()).toFloat())
                ds.drawRect(Color(0xFFD81B60), tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(width = 3f))
            }
        }
    }

    // ---------------- قطعات ----------------

    private fun drawComponent(p: Placed) {
        val def = Registry.get(p.type)
        val f = Fr(p.x, p.y, p.rot, cam)
        val comp = comps[p.id]
        val b = Geo.bounds(p)
        val cx = cam.toScreenX((b.minX + b.maxX) / 2.0 * Grid.BASE_STEP).toFloat()
        val top = cam.toScreenY(b.minY * Grid.BASE_STEP.toDouble()).toFloat()

        if (real) drawReal(f, def.kind, p, comp) else drawSymbol(f, def.kind, p, comp)

        // پایانه‌ها
        if (def.kind != Kind.JUNCTION) {
            val r = if (real) f.s * 0.2f else f.s * 0.16f
            for (pin in def.pins) {
                val o = f.p(pin.dx.toDouble(), pin.dy.toDouble())
                if (real) {
                    circle(o, r * 1.3f, Color(0xFF9E9E9E)); circle(o, r * 0.6f, Color(0xFF424242))
                } else circle(o, r, Pal.ink)
            }
            if (f.s >= 15f) {
                val minDy = def.pins.minOf { it.dy }
                for (pin in def.pins) {
                    val o = f.p(pin.dx.toDouble(), pin.dy.toDouble() + if (pin.dy == minDy) -0.55 else 0.95)
                    text(pin.name, o, f.s * 0.42f, Pal.inkSoft)
                }
            }
        }
        if (def.kind != Kind.JUNCTION && f.s >= 7f) {
            val tag = if (p.type in setOf("pb_no", "pb_nc", "pb_dual", "estop")) {
                val index = st.doc.placed.filter { it.type in setOf("pb_no", "pb_nc", "pb_dual", "estop") }.indexOfFirst { it.id == p.id } + 1
                "SB" + index.toString().padStart(2, '0')
            } else p.id
            text(tag, Offset(cx, top - 4f), min(22f, max(9f, f.s * 0.6f)), Pal.ink, bold = true)
        }
        val warn = st.sim?.warnings?.get(p.id)
        val tripped = (comp as? ProtectiveDevice)?.tripped == true || (comp as? ThermalRelay)?.tripped == true
        if (warn != null || tripped) {
            val label = if (tripped) "TRIP" else "!"
            text(label, Offset(cx, top - 4f - min(22f, max(9f, f.s * 0.6f))), min(24f, max(10f, f.s * 0.7f)), Pal.bad, bold = true)
        }
    }

    /** تیغه‌ی تماس عمودی از y0 تا y1 در x. closed=بسته؛ nc=تیغه‌ی NC (با علامت قلاب). */
    private fun contact(f: Fr, x: Double, y0: Double, y1: Double, closed: Boolean, nc: Boolean, c: Color = Pal.ink) {
        val w = lw(f)
        val ya = y0 + (y1 - y0) * 0.25
        val yb = y0 + (y1 - y0) * 0.75
        line(f.p(x, y0), f.p(x, ya), c, w)
        line(f.p(x, yb), f.p(x, y1), c, w)
        if (closed) line(f.p(x, ya), f.p(x, yb), c, w * 1.3f)
        else line(f.p(x, yb), f.p(x - 0.75, ya + (yb - ya) * 0.15), c, w * 1.3f)
        if (nc) line(f.p(x - 0.3, ya + (yb - ya) * 0.12), f.p(x + 0.3, ya + (yb - ya) * 0.12), c, w)
    }

    private fun actuator(f: Fr, x: Double, ymid: Double, c: Color = Pal.ink) {
        val w = lw(f) * 0.8f
        line(f.p(x, ymid), f.p(x + 1.0, ymid), c, w, dash = true)
        line(f.p(x + 1.0, ymid - 0.35), f.p(x + 1.0, ymid + 0.35), c, w)
    }

    private fun lampColor(name: String): Color = when (name) {
        "red" -> Color(0xFFE53935)
        "green" -> Color(0xFF43A047)
        "yellow" -> Color(0xFFFDD835)
        "white" -> Color(0xFFF5F5F5)
        else -> Color(0xFF90A4AE)
    }

    private fun drawSymbol(f: Fr, kind: Kind, p: Placed, comp: Component?) {
        val w = lw(f)
        val ink = Pal.ink
        when (kind) {
            Kind.SOURCE3, Kind.SOURCE1, Kind.SOURCE_DC -> {
                val def = Registry.get(p.type)
                val maxX = def.pins.maxOf { it.dx }.toDouble()
                val on = (comp as? Source)?.on != false
                rectFill(f, -1.0, 0.0, maxX + 1.0, 2.0, if (on) Color(0xFFFFF3E0) else Color(0xFFECEFF1))
                rectLine(f, -1.0, 0.0, maxX + 1.0, 2.0, ink, w)
                val label = when (kind) { Kind.SOURCE3 -> "3~ L1 L2 L3 N PE"; Kind.SOURCE1 -> "1~ L N PE"; else -> "DC" }
                text(label, f.p(maxX / 2.0, 1.1), f.s * 0.55f, ink, bold = true)
            }
            Kind.BREAKER -> {
                val def = Registry.get(p.type)
                val poles = def.pins.size / 2
                val dev = comp as? ProtectiveDevice
                val closed = dev?.conducting != false
                val fuse = p.type.startsWith("fuse")
                for (i in 0 until poles) {
                    val x = 2.0 * i
                    line(f.p(x, 0.0), f.p(x, 1.0), ink, w)
                    line(f.p(x, 3.0), f.p(x, 4.0), ink, w)
                    if (fuse) {
                        rectFill(f, x - 0.35, 1.0, x + 0.35, 3.0, if (dev?.tripped == true) Color(0xFFFFCDD2) else Pal.paper)
                        rectLine(f, x - 0.35, 1.0, x + 0.35, 3.0, ink, w)
                        if (dev?.tripped != true) line(f.p(x, 1.0), f.p(x, 3.0), ink, w * 0.8f)
                    } else {
                        if (closed) line(f.p(x, 1.0), f.p(x, 3.0), ink, w * 1.3f)
                        else line(f.p(x, 3.0), f.p(x - 0.8, 1.2), ink, w * 1.3f)
                        rectLine(f, x - 0.3, 1.5, x + 0.3, 2.2, ink, w * 0.7f)
                    }
                }
                if (poles > 1) line(f.p(0.0, 2.0), f.p(2.0 * (poles - 1), 2.0), ink, w * 0.7f, dash = true)
            }
            Kind.PUSH -> {
                val pb = comp as? PushButton
                val no = p.type == "pb_no"
                val closed = pb?.closed ?: !no
                contact(f, 0.0, 0.0, 3.0, closed, !no)
                actuator(f, 0.0, 1.5)
                if (pb?.pressed == true) circle(f.p(1.0, 1.5), f.s * 0.28f, Pal.hot)
            }
            Kind.PUSH_DUAL -> {
                val d = comp as? DualPushButton
                val pressed = d?.pressed == true
                contact(f, 0.0, 0.0, 3.0, !pressed, true)
                contact(f, 2.0, 0.0, 3.0, pressed, false)
                line(f.p(0.0, 1.5), f.p(2.0, 1.5), ink, w * 0.7f, dash = true)
                if (pressed) circle(f.p(1.0, 1.5), f.s * 0.28f, Pal.hot)
            }
            Kind.ESTOP -> {
                val pb = comp as? PushButton
                contact(f, 0.0, 0.0, 3.0, pb?.closed ?: true, true)
                line(f.p(0.0, 1.5), f.p(1.0, 1.5), ink, w * 0.8f, dash = true)
                circle(f.p(1.5, 1.5), f.s * 0.55f, if (pb?.pressed == true) Color(0xFF8E0000) else Pal.bad)
            }
            Kind.TOGGLE -> {
                val t = comp as? ToggleSwitch
                val no = p.type.endsWith("_no") || p.type.startsWith("sensor") || p.type == "photocell"
                contact(f, 0.0, 0.0, 3.0, t?.closed ?: !no, !no)
                line(f.p(0.0, 1.5), f.p(1.0, 1.5), ink, w * 0.8f, dash = true)
                if (p.type.startsWith("sensor") || p.type == "photocell") {
                    poly4(f, 1.5, 1.5, 0.6, if (t?.active == true) Pal.hot else Pal.paper, ink, w)
                } else circle(f.p(1.5, 1.5), f.s * 0.45f, if (t?.active == true) Pal.hot else Pal.paper)
                if (!p.type.startsWith("sensor") && p.type != "photocell")
                    circle(f.p(1.5, 1.5), f.s * 0.45f, ink, fill = false, w = w * 0.8f)
            }
            Kind.SELECTOR -> {
                val s = comp as? Selector
                val three = p.type == "sel3"
                val pos = s?.position ?: if (three) 1 else 0
                contact(f, 0.0, 0.0, 3.0, if (three) pos == 0 else pos == 1, false)
                if (three) contact(f, 2.0, 0.0, 3.0, pos == 2, false)
                val kx = if (three) 1.0 else 0.0
                circle(f.p(kx, 1.5), f.s * 0.5f, Pal.paper); circle(f.p(kx, 1.5), f.s * 0.5f, ink, fill = false, w = w)
                val ang = if (three) (pos - 1) * 0.9 else if (pos == 1) 0.9 else -0.9
                line(f.p(kx, 1.5), f.p(kx + sin(ang) * 0.5, 1.5 - cos(ang) * 0.5), ink, w)
            }
            Kind.COIL -> {
                val c = comp as? Coil
                line(f.p(0.0, 0.0), f.p(0.0, 1.0), ink, w); line(f.p(0.0, 3.0), f.p(0.0, 4.0), ink, w)
                rectFill(f, -0.8, 1.0, 0.8, 3.0, if (c?.energized == true) Color(0xFFFFE082) else Pal.paper)
                rectLine(f, -0.8, 1.0, 0.8, 3.0, ink, w)
                if (c?.energized == true) { line(f.p(-0.8, 1.0), f.p(0.8, 3.0), ink, w * 0.6f); line(f.p(0.8, 1.0), f.p(-0.8, 3.0), ink, w * 0.6f) }
            }
            Kind.POLES -> {
                val pl = comp as? PowerPoles
                for (i in 0 until 3) contact(f, 2.0 * i, 0.0, 4.0, pl?.closed == true, false)
                line(f.p(0.0, 2.0), f.p(4.0, 2.0), ink, w * 0.7f, dash = true)
            }
            Kind.AUX -> {
                val a = comp as? LinkedContact
                val no = p.type == "aux_no"
                contact(f, 0.0, 0.0, 3.0, a?.closed ?: !no, !no)
                text(p.params["coil"].orEmpty(), f.p(1.6, 1.6), f.s * 0.45f, Pal.inkSoft)
            }
            Kind.THERMAL -> {
                val t = comp as? ThermalRelay
                for (i in 0 until 3) {
                    val x = 2.0 * i
                    line(f.p(x, 0.0), f.p(x, 1.0), ink, w); line(f.p(x, 3.0), f.p(x, 4.0), ink, w)
                    rectFill(f, x - 0.4, 1.0, x + 0.4, 3.0, Pal.paper); rectLine(f, x - 0.4, 1.0, x + 0.4, 3.0, ink, w)
                }
                val tr = t?.tripped == true
                contact(f, 6.0, 1.0, 3.0, !tr, true)
                contact(f, 8.0, 1.0, 3.0, tr, false)
                if (t != null) rectFill(f, -0.4, 3.35, -0.4 + 4.8 * min(1.0, t.heat), 3.6, Color(0xFFFF7043))
            }
            Kind.TIMER -> {
                val t = comp as? TimerRelay
                val mode = if (p.type == "ton") TimerMode.TON else if (p.type == "tof") TimerMode.TOF else TimerMode.STAR_DELTA
                line(f.p(0.0, 0.0), f.p(0.0, 1.0), ink, w); line(f.p(0.0, 3.0), f.p(0.0, 4.0), ink, w)
                rectFill(f, -0.8, 1.0, 0.8, 3.0, if (t?.supplied == true) Color(0xFFFFE082) else Pal.paper)
                rectLine(f, -0.8, 1.0, 0.8, 3.0, ink, w)
                val d = p.params["delay"] ?: "5"
                text("${if (mode == TimerMode.STAR_DELTA) "Y/Δ" else mode.name} ${d}s", f.p(1.0, 4.9), f.s * 0.5f, Pal.inkSoft)
                if (mode == TimerMode.STAR_DELTA) {
                    contact(f, 2.0, 0.0, 4.0, t?.starOn == true, false)
                    contact(f, 4.0, 0.0, 4.0, t?.deltaOn == true, false)
                } else {
                    val on = t?.outputOn == true
                    line(f.p(3.0, 0.0), f.p(3.0, 1.0), ink, w)
                    line(f.p(2.0, 3.0), f.p(2.0, 4.0), ink, w); line(f.p(4.0, 3.0), f.p(4.0, 4.0), ink, w)
                    line(f.p(3.0, 1.0), if (on) f.p(4.0, 3.0) else f.p(2.0, 3.0), ink, w * 1.3f)
                    line(f.p(2.0, 3.0), f.p(2.0, 3.0), ink, w)
                }
            }
            Kind.LAMP, Kind.BUZZER -> {
                val l = comp as? Lamp
                val lit = l?.lit == true
                line(f.p(0.0, 0.0), f.p(0.0, 0.8), ink, w); line(f.p(0.0, 2.2), f.p(0.0, 3.0), ink, w)
                val col = lampColor(l?.colorName ?: Registry.get(p.type).type.removePrefix("lamp_").let {
                    when (it) { "r" -> "red"; "g" -> "green"; "y" -> "yellow"; "w" -> "white"; else -> "x" }
                })
                if (lit) circle(f.p(0.0, 1.5), f.s * 1.2f, col.copy(alpha = 0.35f))
                circle(f.p(0.0, 1.5), f.s * 0.7f, if (lit) col else Pal.paper)
                circle(f.p(0.0, 1.5), f.s * 0.7f, ink, fill = false, w = w)
                if (kind == Kind.LAMP) {
                    line(f.p(-0.5, 1.0), f.p(0.5, 2.0), ink, w * 0.8f); line(f.p(0.5, 1.0), f.p(-0.5, 2.0), ink, w * 0.8f)
                } else {
                    text("♪", f.p(0.0, 1.8), f.s * 0.9f, ink, bold = true)
                    if (lit) circle(f.p(0.0, 1.5), f.s * 1.1f, Pal.bad, fill = false, w = w)
                }
            }
            Kind.LOAD -> {
                line(f.p(0.0, 0.0), f.p(0.0, 0.7), ink, w); line(f.p(0.0, 2.3), f.p(0.0, 3.0), ink, w)
                rectFill(f, -0.5, 0.7, 0.5, 2.3, Pal.paper); rectLine(f, -0.5, 0.7, 0.5, 2.3, ink, w)
            }
            Kind.MOTOR3 -> drawMotorSymbol(f, p, comp, 2.0, 2.0, "3~")
            Kind.MOTOR1 -> drawMotorSymbol(f, p, comp, 0.0, 2.0, "1~")
            Kind.JUNCTION -> circle(f.p(0.0, 0.0), f.s * 0.28f, ink)
            Kind.GROUND -> {
                line(f.p(0.0, 0.0), f.p(0.0, 0.8), ink, w)
                line(f.p(-0.7, 0.8), f.p(0.7, 0.8), ink, w); line(f.p(-0.45, 1.2), f.p(0.45, 1.2), ink, w); line(f.p(-0.2, 1.6), f.p(0.2, 1.6), ink, w)
            }
        }
    }

    private fun poly4(f: Fr, cx: Double, cy: Double, r: Double, fill: Color, border: Color, w: Float) {
        val pts = listOf(f.p(cx, cy - r), f.p(cx + r, cy), f.p(cx, cy + r), f.p(cx - r, cy))
        val pa = path(pts)
        ds.drawPath(pa, color = fill); ds.drawPath(pa, color = border, style = Stroke(width = w))
    }

    private fun drawMotorSymbol(f: Fr, p: Placed, comp: Component?, cx: Double, cy: Double, label: String) {
        val w = lw(f)
        val three = label == "3~"
        val r = if (three) 1.5 else 1.3
        if (three) {
            for (x in listOf(0.0, 2.0, 4.0)) { line(f.p(x, 0.0), f.p(x, 0.7), Pal.ink, w); line(f.p(x, 3.3), f.p(x, 4.0), Pal.ink, w) }
            line(f.p(0.0, 0.7), f.p(4.0, 0.7), Pal.ink, w * 0.7f); line(f.p(0.0, 3.3), f.p(4.0, 3.3), Pal.ink, w * 0.7f)
        } else {
            line(f.p(0.0, 0.0), f.p(0.0, 0.7), Pal.ink, w); line(f.p(0.0, 3.3), f.p(0.0, 4.0), Pal.ink, w)
        }
        val running = (comp as? Motor3Ph)?.running == true || (comp as? Motor1Ph)?.running == true
        val c = f.p(cx, cy)
        circle(c, f.s * r.toFloat(), if (running) Color(0xFFE8F5E9) else Pal.paper)
        circle(c, f.s * r.toFloat(), Pal.ink, fill = false, w = w)
        text("M", Offset(c.x, c.y), f.s * 0.8f, Pal.ink, bold = true)
        text(label, Offset(c.x, c.y + f.s * 0.75f), f.s * 0.55f, Pal.ink)
        val a = (st.spins[p.id] ?: 0f) * Math.PI.toFloat() / 180f
        val om = (comp as? Motor3Ph)?.omega ?: (comp as? Motor1Ph)?.omega ?: 0.0
        if (running) {
            val rr = f.s * r.toFloat() * 0.85f
            line(Offset(c.x + cos(a) * rr * 0.6f, c.y + sin(a) * rr * 0.6f), Offset(c.x + cos(a) * rr, c.y + sin(a) * rr), Pal.good, w * 1.4f)
            text(if (om >= 0) "↻" else "↺", Offset(c.x, c.y - f.s * 0.95f), f.s * 0.8f, Pal.good, bold = true)
        }
    }

    // ---------------- نمایش واقعی ----------------

    private fun drawReal(f: Fr, kind: Kind, p: Placed, comp: Component?) {
        val w = lw(f)
        when (kind) {
            Kind.SOURCE3, Kind.SOURCE1, Kind.SOURCE_DC -> {
                val maxX = Registry.get(p.type).pins.maxOf { it.dx }.toDouble()
                val on = (comp as? Source)?.on != false
                box(f, -1.0, 0.0, maxX + 1.0, 2.0, if (on) Color(0xFF546E7A) else Color(0xFF90A4AE), Color(0xFF263238), Pal.ink, w)
                text(if (kind == Kind.SOURCE3) "3~ 400/230V" else if (kind == Kind.SOURCE1) "1~ 230V" else "DC", f.p(maxX / 2.0, 1.0), f.s * 0.6f, Color.White, bold = true)
            }
            Kind.BREAKER -> {
                val poles = Registry.get(p.type).pins.size / 2
                val dev = comp as? ProtectiveDevice
                val fuse = p.type.startsWith("fuse")
                for (i in 0 until poles) {
                    val x = 2.0 * i
                    if (fuse) {
                        val blown = dev?.tripped == true
                        box(f, x - 0.55, 0.8, x + 0.55, 3.2, if (blown) Color(0xFFFFCDD2) else Color(0xFFFFF8E1), Color(0xFFD7CCC8), Pal.ink, w)
                        rectFill(f, x - 0.55, 0.8, x + 0.55, 1.2, Color(0xFFB0BEC5)); rectFill(f, x - 0.55, 2.8, x + 0.55, 3.2, Color(0xFFB0BEC5))
                    } else {
                        box(f, x - 0.9, 0.6, x + 0.9, 3.4, Color(0xFFFAFAFA), Color(0xFFCFD8DC), Pal.ink, w)
                        val on = dev?.conducting != false
                        rectFill(f, x - 0.3, if (on) 1.0 else 2.0, x + 0.3, if (on) 2.0 else 3.0, Color(0xFF212121))
                    }
                }
                text(p.params["amps"] ?: "", f.p(if (fuse) 0.0 else 0.0, 3.0), f.s * 0.55f, Pal.ink, bold = true)
            }
            Kind.PUSH -> {
                val pb = comp as? PushButton
                val no = p.type == "pb_no"
                box(f, -0.9, 0.4, 0.9, 2.6, Color(0xFFCFD8DC), Color(0xFF90A4AE), Pal.ink, w)
                val base = if (no) Color(0xFF43A047) else Color(0xFFE53935)
                val c = f.p(0.0, 1.5)
                circle(c, f.s * (if (pb?.pressed == true) 0.5f else 0.65f), if (pb?.pressed == true) base.copy(alpha = 0.65f) else base)
                circle(c, f.s * 0.65f, Pal.ink, fill = false, w = w * 0.7f)
                text(if (no) "I" else "O", c.copy(y = c.y + f.s * 0.28f), f.s * 0.8f, Color.White, bold = true)
            }
            Kind.PUSH_DUAL -> {
                val d = comp as? DualPushButton
                box(f, -0.9, 0.4, 2.9, 2.6, Color(0xFFCFD8DC), Color(0xFF90A4AE), Pal.ink, w)
                circle(f.p(0.0, 1.5), f.s * 0.6f, Color(0xFFE53935)); circle(f.p(2.0, 1.5), f.s * 0.6f, if (d?.pressed == true) Color(0xFF2E7D32) else Color(0xFF43A047))
            }
            Kind.ESTOP -> {
                val pb = comp as? PushButton
                box(f, -1.0, 0.3, 1.0, 2.7, Color(0xFFFFEE58), Color(0xFFF9A825), Pal.ink, w)
                circle(f.p(0.0, 1.5), f.s * 0.9f, if (pb?.pressed == true) Color(0xFF8E0000) else Color(0xFFD32F2F))
                circle(f.p(0.0, 1.5), f.s * 0.9f, Pal.ink, fill = false, w = w * 0.7f)
            }
            Kind.COIL -> {
                val c = comp as? Coil
                box(f, -1.3, 0.6, 1.3, 3.4, Color(0xFF546E7A), Color(0xFF263238), Pal.ink, w)
                rectFill(f, -1.0, 1.6, 1.0, 2.5, Color(0xFFECEFF1))
                text(p.id, f.p(0.0, 2.2), f.s * 0.55f, Pal.ink, bold = true)
                circle(f.p(0.85, 1.0), f.s * 0.22f, if (c?.energized == true) Color(0xFF69F0AE) else Color(0xFF455A64))
            }
            Kind.POLES -> {
                val pl = comp as? PowerPoles
                box(f, -1.0, 0.6, 5.0, 3.4, Color(0xFF546E7A), Color(0xFF263238), Pal.ink, w)
                for (i in 0 until 3) {
                    val x = 2.0 * i
                    rectFill(f, x - 0.5, 1.2, x + 0.5, 2.8, Color(0xFF37474F))
                    if (pl?.closed == true) rectFill(f, x - 0.4, 1.7, x + 0.4, 2.3, Pal.copper) else rectFill(f, x - 0.4, 1.25, x + 0.4, 1.65, Pal.copper)
                }
                text(p.params["coil"].orEmpty(), f.p(2.0, 3.2), f.s * 0.45f, Color.White)
            }
            Kind.AUX -> {
                val a = comp as? LinkedContact
                val no = p.type == "aux_no"
                box(f, -0.9, 0.4, 0.9, 2.6, Color(0xFF607D8B), Color(0xFF37474F), Pal.ink, w)
                text(if (no) "NO" else "NC", f.p(0.0, 1.3), f.s * 0.5f, Color.White, bold = true)
                circle(f.p(0.0, 2.0), f.s * 0.2f, if ((a?.closed ?: !no)) Color(0xFF69F0AE) else Color(0xFF455A64))
            }
            Kind.THERMAL -> {
                val t = comp as? ThermalRelay
                box(f, -1.0, 0.6, 9.0, 3.4, Color(0xFFB0BEC5), Color(0xFF78909C), Pal.ink, w)
                circle(f.p(2.0, 2.0), f.s * 0.6f, Color(0xFF1E88E5)); text("RESET", f.p(2.0, 2.15), f.s * 0.3f, Color.White)
                rectFill(f, -0.6, 2.8, -0.6 + 3.4 * min(1.0, t?.heat ?: 0.0), 3.1, Color(0xFFFF7043))
                circle(f.p(7.0, 2.0), f.s * 0.35f, if (t?.tripped == true) Pal.bad else Color(0xFF455A64))
            }
            Kind.TIMER -> {
                val t = comp as? TimerRelay
                box(f, -1.0, 0.6, 5.0, 3.4, Color(0xFF37474F), Color(0xFF263238), Pal.ink, w)
                rectFill(f, -0.5, 1.0, 3.5, 2.2, Color(0xFFC8E6C9))
                val d = p.params["delay"]?.toDoubleOrNull() ?: 5.0
                val e = t?.elapsed ?: 0.0
                text(String.format("%.1f/%.0f s", e, d), f.p(1.5, 1.85), f.s * 0.55f, Pal.ink, bold = true)
                rectFill(f, -0.5, 2.5, -0.5 + 4.0 * min(1.0, e / d), 2.8, Pal.hot)
                circle(f.p(4.2, 1.2), f.s * 0.2f, if (t?.supplied == true) Color(0xFF69F0AE) else Color(0xFF455A64))
            }
            Kind.LAMP, Kind.BUZZER -> {
                val l = comp as? Lamp
                val lit = l?.lit == true
                val name = l?.colorName ?: when (p.type) { "lamp_r" -> "red"; "lamp_g" -> "green"; "lamp_y" -> "yellow"; "lamp_w" -> "white"; else -> "x" }
                val col = lampColor(name)
                val c = f.p(0.0, 1.5)
                if (lit) ds.drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.6f), Color.Transparent), center = c, radius = f.s * 2.2f), radius = f.s * 2.2f, center = c)
                circle(c, f.s * 0.95f, Color(0xFF455A64))
                ds.drawCircle(
                    Brush.radialGradient(
                        listOf(if (lit) Color.White else col.copy(alpha = 0.55f), if (lit) col else col.copy(alpha = 0.3f)),
                        center = c, radius = f.s * 0.8f,
                    ), radius = f.s * 0.8f, center = c,
                )
                if (kind == Kind.BUZZER) text(if (name == "bell") "🔔" else "♪", Offset(c.x, c.y + f.s * 0.3f), f.s * 0.9f, Pal.ink, bold = true)
            }
            Kind.MOTOR3, Kind.MOTOR1 -> {
                val three = kind == Kind.MOTOR3
                val cx = if (three) 2.0 else 0.0
                val rr = if (three) 1.6 else 1.3
                if (three) {
                    box(f, 0.3, -0.2, 3.7, 0.8, Color(0xFF90A4AE), Color(0xFF607D8B), Pal.ink, w)
                    box(f, -0.9, 0.8, 4.9, 3.4, Color(0xFF5C7C99), Color(0xFF34495E), Pal.ink, w)
                    for (k in 0..6) line(f.p(-0.9 + 0.85 * k, 0.9), f.p(-0.9 + 0.85 * k, 3.3), Color(0x44FFFFFF), w * 0.8f)
                } else {
                    box(f, -1.4, 0.8, 1.4, 3.4, Color(0xFF5C7C99), Color(0xFF34495E), Pal.ink, w)
                }
                val c = f.p(cx, 2.1)
                circle(c, f.s * rr.toFloat() * 0.6f, Color(0xFFCFD8DC)); circle(c, f.s * rr.toFloat() * 0.6f, Pal.ink, fill = false, w = w * 0.7f)
                val a = (st.spins[p.id] ?: 0f) * Math.PI.toFloat() / 180f
                for (k in 0 until 4) {
                    val ak = a + k * Math.PI.toFloat() / 2f
                    line(c, Offset(c.x + cos(ak) * f.s * rr.toFloat() * 0.58f, c.y + sin(ak) * f.s * rr.toFloat() * 0.58f), Pal.ink, w)
                }
                circle(c, f.s * 0.12f, Pal.ink)
            }
            else -> drawSymbol(f, kind, p, comp)
        }
    }
}

fun DrawScope.drawEditor(st: RenderState) = Painter(this, st).draw()
