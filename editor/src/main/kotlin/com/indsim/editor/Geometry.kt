package com.indsim.editor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Box(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
    fun contains(x: Double, y: Double) = x >= minX && x <= maxX && y >= minY && y <= maxY
}

object Geo {
    fun rotate(dx: Int, dy: Int, rot: Int): GridPt = when (((rot % 360) + 360) % 360) {
        0 -> GridPt(dx, dy)
        90 -> GridPt(-dy, dx)
        180 -> GridPt(-dx, -dy)
        else -> GridPt(dy, -dx)
    }

    fun pinPos(p: Placed, pin: String): GridPt {
        val d = Registry.get(p.type).pin(pin)
        val r = rotate(d.dx, d.dy, p.rot)
        return GridPt(p.x + r.x, p.y + r.y)
    }

    /** پین به شکل "K1:A1". null اگر وجود نداشته باشد. */
    fun pinPos(doc: Document, pinId: String): GridPt? {
        val i = pinId.indexOf(':')
        if (i <= 0) return null
        val p = doc.find(pinId.substring(0, i)) ?: return null
        val name = pinId.substring(i + 1)
        if (Registry.find(p.type)?.pins?.any { it.name == name } != true) return null
        return pinPos(p, name)
    }

    fun allPins(doc: Document): List<Pair<String, GridPt>> =
        doc.placed.flatMap { p -> Registry.get(p.type).pins.map { "${p.id}:${it.name}" to pinPos(p, it.name) } }

    /** جعبه‌ی دورِ قطعه برای لمس و رسم بدنه. */
    fun bounds(p: Placed): Box {
        val def = Registry.get(p.type)
        val pts = def.pins.map { pinPos(p, it.name) }
        var b = Box(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        if (def.kind == Kind.JUNCTION) return Box(b.minX, b.minY, b.maxX, b.maxY)
        b = Box(b.minX - 1, b.minY - 1, b.maxX + 1, b.maxY + 1)
        return b
    }

    fun contentBounds(doc: Document): Box? {
        if (doc.placed.isEmpty()) return null
        val bs = doc.placed.map { bounds(it) }
        return Box(bs.minOf { it.minX }, bs.minOf { it.minY }, bs.maxOf { it.maxX }, bs.maxOf { it.maxY })
    }

    fun wirePath(doc: Document, w: Wire): List<GridPt>? {
        val a = pinPos(doc, w.a) ?: return null
        val b = pinPos(doc, w.b) ?: return null
        return Router.route(a, b, w.lane)
    }
}

/** مسیریاب متعامد (افقی/عمودی). */
object Router {
    fun route(a: GridPt, b: GridPt, lane: Int = 0): List<GridPt> {
        if (a == b) return listOf(a)
        val pts = when {
            a.x == b.x && lane != 0 -> listOf(a, GridPt(a.x + lane, a.y), GridPt(b.x + lane, b.y), b)
            a.y == b.y && lane != 0 -> listOf(a, GridPt(a.x, a.y + lane), GridPt(b.x, b.y + lane), b)
            a.x == b.x || a.y == b.y -> listOf(a, b)
            else -> {
                val midY = (a.y + b.y) / 2 + lane
                listOf(a, GridPt(a.x, midY), GridPt(b.x, midY), b)
            }
        }
        return pts.filterIndexed { i, p -> i == 0 || p != pts[i - 1] }
    }

    /** بهترین مسیر متعامد: مسیرها از روی بدنه‌ی سایر قطعات عبور نمی‌کنند و
     * هم‌پوشانی/تقاطع با سیم‌های موجود تا حد ممکن حذف می‌شود. */
    fun bestLane(doc: Document, a: GridPt, b: GridPt, ownA: String, ownB: String): Int {
        val ownerA = ownA.substringBefore(':')
        val ownerB = ownB.substringBefore(':')
        val obstacles = doc.placed.filter { it.id != ownerA && it.id != ownerB && Registry.get(it.type).kind != Kind.JUNCTION }
            .map { Geo.bounds(it) }
        val others = doc.wires.mapNotNull { Geo.wirePath(doc, it) }
        if (a.x == b.x || a.y == b.y) {
            val direct = route(a, b, 0)
            val clearOfParts = direct.zipWithNext().none { (p0, p1) -> obstacles.any { segmentHitsBox(p0, p1, it) } }
            val clearOfWires = direct.zipWithNext().none { (p0, p1) -> others.any { path -> path.zipWithNext().any { (q0, q1) -> overlap(p0, p1, q0, q1) > 0 || crosses(p0, p1, q0, q1) } } }
            if (clearOfParts && clearOfWires) return 0
        }
        var bestLane = 0
        var bestCost = Long.MAX_VALUE
        for (offset in -24..24) {
            val path = route(a, b, offset)
            run {
                var cost = kotlin.math.abs(offset).toLong() * 2 + path.zipWithNext().sumOf { (x, y) -> kotlin.math.abs(x.x-y.x).toLong() + kotlin.math.abs(x.y-y.y).toLong() }
                for ((s0, s1) in path.zipWithNext()) {
                    for (box in obstacles) if (segmentHitsBox(s0, s1, box)) cost += 100_000
                    for (other in others) for ((o0, o1) in other.zipWithNext()) {
                        val overlap = overlap(s0, s1, o0, o1)
                        if (overlap > 0) cost += 20_000L + overlap
                        else if (crosses(s0, s1, o0, o1)) cost += 8_000
                    }
                }
                if (cost < bestCost) { bestCost = cost; bestLane = offset }
                if (cost < 100) return offset
            }
        }
        return bestLane
    }

    private fun segmentHitsBox(a: GridPt, b: GridPt, box: Box): Boolean {
        // Bounds are expanded by one grid unit; strict interior avoids touching a body edge.
        if (a.x == b.x) return a.x > box.minX && a.x < box.maxX && max(min(a.y,b.y),box.minY) < min(max(a.y,b.y),box.maxY)
        if (a.y == b.y) return a.y > box.minY && a.y < box.maxY && max(min(a.x,b.x),box.minX) < min(max(a.x,b.x),box.maxX)
        return false
    }

    private fun crosses(a: GridPt, b: GridPt, c: GridPt, d: GridPt): Boolean =
        (a.x == b.x && c.y == d.y && a.x > min(c.x,d.x) && a.x < max(c.x,d.x) && c.y > min(a.y,b.y) && c.y < max(a.y,b.y)) ||
        (a.y == b.y && c.x == d.x && c.x > min(a.x,b.x) && c.x < max(a.x,b.x) && a.y > min(c.y,d.y) && a.y < max(c.y,d.y))

    private fun onSegmentInterior(q: GridPt, s0: GridPt, s1: GridPt): Boolean {
        if (s0.x == s1.x && q.x == s0.x) return q.y > min(s0.y, s1.y) && q.y < max(s0.y, s1.y)
        if (s0.y == s1.y && q.y == s0.y) return q.x > min(s0.x, s1.x) && q.x < max(s0.x, s1.x)
        return false
    }

    /** طول هم‌پوشانی دو پاره‌خط هم‌راستا (بیش از صفر یعنی روی هم افتاده‌اند). */
    private fun overlap(a0: GridPt, a1: GridPt, b0: GridPt, b1: GridPt): Int {
        if (a0.x == a1.x && b0.x == b1.x && a0.x == b0.x)
            return max(0, min(max(a0.y, a1.y), max(b0.y, b1.y)) - max(min(a0.y, a1.y), min(b0.y, b1.y)))
        if (a0.y == a1.y && b0.y == b1.y && a0.y == b0.y)
            return max(0, min(max(a0.x, a1.x), max(b0.x, b1.x)) - max(min(a0.x, a1.x), min(b0.x, b1.x)))
        return 0
    }
}

/** تشخیص لمس. مختصات‌ها به واحد شبکه (اعشاری). */
object Hit {
    fun pinAt(doc: Document, x: Double, y: Double, radius: Double): String? {
        var best: String? = null
        var bd = radius
        for ((id, p) in Geo.allPins(doc)) {
            val d = hypot(p.x - x, p.y - y)
            if (d <= bd) { bd = d; best = id }
        }
        return best
    }

    fun componentAt(doc: Document, x: Double, y: Double): Placed? =
        doc.placed.lastOrNull { p ->
            val b = Geo.bounds(p)
            if (Registry.get(p.type).kind == Kind.JUNCTION) hypot(b.minX - x, b.minY - y) <= 0.7
            else b.contains(x, y)
        }

    data class WireHit(val index: Int, val point: GridPt, val distance: Double)

    fun wireAt(doc: Document, x: Double, y: Double, tol: Double): WireHit? {
        var best: WireHit? = null
        doc.wires.forEachIndexed { idx, w ->
            val path = Geo.wirePath(doc, w) ?: return@forEachIndexed
            for (i in 0 until path.size - 1) {
                val a = path[i]; val b = path[i + 1]
                val cx = x.coerceIn(min(a.x, b.x).toDouble(), max(a.x, b.x).toDouble())
                val cy = y.coerceIn(min(a.y, b.y).toDouble(), max(a.y, b.y).toDouble())
                val d = hypot(cx - x, cy - y)
                if (d <= tol && (best == null || d < best!!.distance)) {
                    best = WireHit(idx, GridPt(Math.round(cx).toInt(), Math.round(cy).toInt()), d)
                }
            }
        }
        return best
    }
}
